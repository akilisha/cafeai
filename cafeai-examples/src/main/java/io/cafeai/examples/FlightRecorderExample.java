package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.flight.FlightBridge;
import io.cafeai.flight.FlightCategory;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

import java.time.Duration;
import java.util.Map;

/**
 * FlightRecorderExample -- JVM-level visibility via Java Flight Recorder.
 *
 * <p>Demonstrates {@link FlightBridge}: JFR events, translated into OpenTelemetry
 * metrics, with no dependency on {@code cafeai-observability} (both simply call
 * {@code GlobalOpenTelemetry.get()} and share whatever's registered).
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>{@code FlightBridge} captures real JFR events with no JVM flags needed</li>
 *   <li>A virtual thread pinned inside a {@code synchronized} block shows up as a
 *       {@code cafeai.flight.vthread.pinned} metric -- the headline use case for a
 *       framework built entirely on virtual threads</li>
 *   <li>No dashboard is shipped -- this example prints metrics to the console via
 *       OTel's logging exporter, standing in for whatever real backend
 *       (Grafana, etc.) a production OTLP exporter would feed</li>
 * </ol>
 *
 * <h2>Running</h2>
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.FlightRecorderExample
 * </pre>
 *
 * <h2>Proving virtual thread pinning is captured</h2>
 * <pre>
 *   curl http://localhost:8080/pin
 *   # within ~5s, the console prints a cafeai.flight.vthread.pinned metric
 * </pre>
 */
public class FlightRecorderExample {

    private static final Object PINNING_LOCK = new Object();

    public static void main(String[] args) {
        // ── OTel wiring (console-only, for this demo) ────────────────────────────
        //
        // A real deployment configures its own exporter (OTLP, etc.) the same way
        // it would for cafeai-observability -- CafeAI does not manage the SDK
        // lifecycle for either module.
        var meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create())
                .setInterval(Duration.ofSeconds(5))
                .build())
            .build();
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();

        // ── Flight Recorder bridge ────────────────────────────────────────────────
        //
        // GC + VIRTUAL_THREADS are the defaults; listed explicitly here for clarity.
        var flight = FlightBridge.builder()
            .categories(FlightCategory.GC, FlightCategory.VIRTUAL_THREADS)
            .build();
        flight.start();
        Runtime.getRuntime().addShutdownHook(new Thread(flight::close));

        // ── App ───────────────────────────────────────────────────────────────────
        var app = CafeAI.create();

        app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));

        // Deliberately pins the handling virtual thread to its carrier, so hitting
        // this route produces a cafeai.flight.vthread.pinned metric.
        app.get("/pin", (req, res, next) -> {
            synchronized (PINNING_LOCK) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            res.json(Map.of("status", "pinned briefly"));
        });

        app.listen(8080, () -> System.out.println("""
            ☕ FlightRecorderExample running on http://localhost:8080

               GET  /health   → health check
               GET  /pin      → deliberately pins a virtual thread

            JFR categories active: GC, VIRTUAL_THREADS
            Metrics print to the console every 5s via OTel's logging exporter.

            Try:
              curl http://localhost:8080/pin

            Press Ctrl+C to stop.
            """));
    }
}
