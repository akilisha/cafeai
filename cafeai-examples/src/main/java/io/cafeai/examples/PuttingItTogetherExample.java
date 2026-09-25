package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.session.SessionStore;
import io.cafeai.flight.FlightBridge;
import io.cafeai.flight.FlightCategory;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PuttingItTogetherExample -- a session-backed shopping cart, watched by
 * Flight Recorder while it runs. Combines every piece shipped today:
 * {@code cafeai-session}'s real default (not the Redis illustration,
 * not the encrypted-cookie variant -- {@code SessionStore.sqlite()} itself,
 * demonstrated directly for the first time in an example) and
 * {@code cafeai-flight}, with a realistic reason for them to be running
 * together, not just side by side.
 *
 * <h2>The story</h2>
 * {@code /checkout} does what a lot of real inventory-reservation code
 * does: guards a shared critical section with {@code synchronized} to avoid
 * overselling the last unit of something. That's exactly the shape of code
 * that pins a virtual thread to its carrier -- and because the session
 * middleware is running on the very same virtual-thread-per-request model,
 * a `synchronized` block anywhere in a session-backed request path is a
 * realistic, easy-to-introduce production risk, not a contrived one.
 * {@code FlightCategory.VIRTUAL_THREADS} catches it, live, in the same
 * process that's serving the cart traffic that triggered it.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>{@code SessionStore.sqlite()} persists real session state (a cart)
 *       across requests, keyed by the {@code cafeai.sid} cookie</li>
 *   <li>{@code FlightBridge} observes the same running process the session
 *       traffic is hitting -- not a separate demo, the *same* app</li>
 *   <li>A `synchronized` critical section inside a session-backed handler
 *       shows up as a real {@code cafeai.flight.vthread.pinned} metric,
 *       tying the day's two features to one coherent, realistic scenario</li>
 * </ol>
 *
 * <h2>Running</h2>
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.PuttingItTogetherExample
 * </pre>
 *
 * <h2>Trying it</h2>
 * <pre>
 *   curl -c cookies.txt -X POST http://localhost:8080/cart/add \
 *        -H "Content-Type: application/json" -d '{"item":"espresso machine"}'
 *
 *   curl -b cookies.txt http://localhost:8080/cart
 *   # -&gt; {"items":["espresso machine"]}
 *
 *   curl -b cookies.txt -X POST http://localhost:8080/checkout
 *   # -&gt; {"status":"ok","charged":["espresso machine"]}, cart is now empty
 *
 *   curl -b cookies.txt http://localhost:8080/cart
 *   # -&gt; {"items":[]} -- the same SQLite-backed session, cart cleared by checkout
 *
 *   # within ~5s of the checkout call, the console prints a
 *   # cafeai.flight.vthread.pinned metric -- the synchronized block in
 *   # /checkout pinned the request's virtual thread, and FlightBridge caught it.
 * </pre>
 */
public class PuttingItTogetherExample {

    /** Simulates a shared inventory ledger -- the reason /checkout needs a critical section. */
    private static final Object INVENTORY_LOCK = new Object();

    public static void main(String[] args) {
        // ── OTel wiring (console-only, for this demo) -- see FlightRecorderExample ──
        var meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create())
                .setInterval(Duration.ofSeconds(5))
                .build())
            .build();
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();

        // ── Flight Recorder, watching the same process the cart traffic hits ────────
        var flight = FlightBridge.builder()
            .categories(FlightCategory.GC, FlightCategory.VIRTUAL_THREADS)
            .build();
        flight.start();
        Runtime.getRuntime().addShutdownHook(new Thread(flight::close));

        // ── App ───────────────────────────────────────────────────────────────────
        var app = CafeAI.create();

        app.filter(CafeAI.json());
        // The real cafeai-session default -- not the Redis illustration, not a
        // cookie-session variant. Cart state lives server-side, survives a restart.
        app.filter(Middleware.session(SessionStore.sqlite()));

        app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));

        app.post("/cart/add", (req, res, next) -> {
            String item = req.body("item");
            if (item == null || item.isBlank()) {
                res.status(400).json(Map.of("error", "item field required"));
                return;
            }
            List<String> cart = new ArrayList<>(cartOf(req.session().get("cart", List.class)));
            cart.add(item);
            req.session().set("cart", cart);
            res.json(Map.of("items", cart));
        });

        app.get("/cart", (req, res, next) ->
            res.json(Map.of("items", cartOf(req.session().get("cart", List.class)))));

        app.post("/checkout", (req, res, next) -> {
            List<String> cart = cartOf(req.session().get("cart", List.class));
            if (cart.isEmpty()) {
                res.status(400).json(Map.of("error", "cart is empty"));
                return;
            }

            // The critical section: reserve inventory for everything in the cart before
            // charging. synchronized pins the handling virtual thread to its carrier for
            // as long as this block runs -- realistic, not contrived, and exactly what
            // FlightCategory.VIRTUAL_THREADS exists to catch.
            synchronized (INVENTORY_LOCK) {
                try {
                    Thread.sleep(50); // simulated inventory-ledger work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            req.session().set("cart", List.of());
            res.json(Map.of("status", "ok", "charged", cart));
        });

        app.listen(8080, () -> System.out.println("""
            ☕ PuttingItTogetherExample running on http://localhost:8080

               GET  /health       → health check
               POST /cart/add     → {"item": "..."} adds to the SQLite-backed session cart
               GET  /cart         → reads the cart back
               POST /checkout     → charges the cart (a synchronized critical section --
                                     watch for cafeai.flight.vthread.pinned within ~5s)

            Try:
              curl -c cookies.txt -X POST http://localhost:8080/cart/add \\
                   -H "Content-Type: application/json" -d '{"item":"espresso machine"}'
              curl -b cookies.txt http://localhost:8080/cart
              curl -b cookies.txt -X POST http://localhost:8080/checkout
              curl -b cookies.txt http://localhost:8080/cart

            Press Ctrl+C to stop.
            """));
    }

    @SuppressWarnings("unchecked")
    private static List<String> cartOf(List<?> raw) {
        return raw == null ? List.of() : (List<String>) raw;
    }
}
