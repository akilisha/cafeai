package io.cafeai.core;

import io.cafeai.core.routing.ContentMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request/response pair is wired, and {@code res.format()} / {@code res.render()} work.
 *
 * <p>Before this, {@code res.request()}, {@code req.response()} and {@code res.app()} all
 * returned {@code null} (nothing ever set them), {@code res.format()} therefore never saw
 * the {@code Accept} header and always picked the first handler, and {@code res.render()}
 * threw {@code UnsupportedOperationException} even with a view engine registered.
 * Everything here goes over a real Helidon server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseWiringIntegrationTest {

    private CafeAI     app;
    private String     base;
    private HttpClient http;
    private Path       views;

    @BeforeAll
    void start() throws Exception {
        int port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        views = Files.createTempDirectory("cafeai-views");
        Files.writeString(views.resolve("welcome.html"), "Hello {{name}}!");
        Files.writeString(views.resolve("layers.html"),  "{{a}}-{{b}}-{{c}}");

        app = CafeAI.create();
        app.set(Setting.VIEWS, views.toString());
        app.set(Setting.VIEW_ENGINE, "html");
        app.engine("html", ResponseFormatter.template());
        app.local("a", "app");
        app.local("b", "app");
        app.local("c", "app");

        app.get("/pairing", (req, res, next) -> res.json(Map.of(
            "resAppIsApp",      res.app() == app,
            "reqAppIsApp",      req.app() == app,
            "resRequestIsReq",  res.request() == req,
            "reqResponseIsRes", req.response() == res)));

        app.get("/format", (req, res, next) -> res.format(ContentMap.of()
            .text(() -> res.send("plain"))
            .json(() -> res.json(Map.of("k", "v")))
            .build()));

        app.get("/render", (req, res, next) -> res.render("welcome", Map.of("name", "Ada")));
        app.get("/render-res-locals", (req, res, next) -> {
            res.local("name", "Grace");
            res.render("welcome");
        });
        // app.locals() < res.locals() < the locals passed to render()
        app.get("/render-layers", (req, res, next) -> {
            res.local("b", "res");
            res.local("c", "res");
            res.render("layers", Map.of("c", "view"));
        });
        app.get("/render-missing", (req, res, next) -> res.render("does-not-exist"));

        app.onError((err, req, res, next) -> res.status(500).json(Map.of(
            "error", err.getClass().getSimpleName(),
            "message", String.valueOf(err.getMessage()))));

        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
    }

    @AfterAll
    void stop() throws IOException {
        if (app != null) app.stop();
        if (views != null) {
            try (var files = Files.list(views)) {
                for (Path f : (Iterable<Path>) files::iterator) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(views);
        }
    }

    // ── the pair is wired ─────────────────────────────────────────────────────

    @Test
    @DisplayName("res.app(), res.request() and req.response() return the live objects, not null")
    void requestAndResponseArePaired() throws Exception {
        var res = get("/pairing", null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
            .contains("\"resAppIsApp\":true").contains("\"reqAppIsApp\":true")
            .contains("\"resRequestIsReq\":true").contains("\"reqResponseIsRes\":true");
    }

    // ── res.format() honours Accept ───────────────────────────────────────────

    @Test
    @DisplayName("res.format() picks the handler matching the request's Accept header")
    void formatHonoursAccept() throws Exception {
        assertThat(get("/format", "application/json").body()).isEqualTo("{\"k\":\"v\"}");
        assertThat(get("/format", "text/plain").body()).isEqualTo("plain");
    }

    @Test
    @DisplayName("res.format() answers 406 when no handler matches the Accept header")
    void formatUnacceptable() throws Exception {
        assertThat(get("/format", "image/png").statusCode()).isEqualTo(406);
    }

    // ── res.render() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("res.render(view, locals) renders through the registered engine as text/html")
    void renderWithLocals() throws Exception {
        var res = get("/render", null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("Hello Ada!");
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    }

    @Test
    @DisplayName("res.render(view) uses res.locals()")
    void renderUsesResponseLocals() throws Exception {
        assertThat(get("/render-res-locals", null).body()).isEqualTo("Hello Grace!");
    }

    @Test
    @DisplayName("render precedence is app.locals() < res.locals() < the locals passed in")
    void renderLocalsPrecedence() throws Exception {
        // a: app only -> "app"; b: app overridden by res -> "res"; c: res overridden by view -> "view"
        assertThat(get("/render-layers", null).body()).isEqualTo("app-res-view");
    }

    @Test
    @DisplayName("res.render() of a missing view reaches the error handler with a clear cause")
    void renderMissingView() throws Exception {
        var res = get("/render-missing", null);

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).contains("RenderException").contains("does-not-exist");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path, String accept) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (accept != null) req.header("Accept", accept);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
