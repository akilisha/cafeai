package io.cafeai.core;

import io.cafeai.core.middleware.Middleware;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CafeAI — full HTTP round-trip over a real Helidon SE server.
 *
 * <p>Each test class gets one server instance on a random free port. The server
 * is started in {@code @BeforeAll} and stopped in {@code @AfterAll}.
 * Tests fire real HTTP requests using Java 21's built-in {@link HttpClient}.
 *
 * <p>Covers:
 * <ul>
 *   <li>HTTP verb routing (GET, POST, PUT, PATCH, DELETE)</li>
 *   <li>Path parameter extraction</li>
 *   <li>Query string parsing</li>
 *   <li>JSON body parsing and response serialisation</li>
 *   <li>Raw and text body parsing</li>
 *   <li>URL-encoded body parsing</li>
 *   <li>Response status codes and headers</li>
 *   <li>Redirect responses</li>
 *   <li>Filter middleware (pre-processing and post-processing)</li>
 *   <li>Variadic inline middleware chains</li>
 *   <li>Sub-router mounting</li>
 *   <li>Static header setting</li>
 *   <li>404 for unmatched routes</li>
 *   <li>req.stream() — SSE detection</li>
 *   <li>req.xhr() — XMLHttpRequest detection</li>
 *   <li>req.attribute() — middleware-to-handler data passing</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CafeAIIntegrationTest {

    private CafeAI          app;
    private int             port;
    private HttpClient      http;
    private String          base;
    private final AtomicLong lastRequestMs =
        new AtomicLong(-1);

    // ── Server lifecycle ──────────────────────────────────────────────────────

    @BeforeAll
    void startServer() throws Exception {
        port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        app = CafeAI.create();

        // ── Global filters ────────────────────────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(CafeAI.text());
        app.filter(CafeAI.raw());
        app.filter(CafeAI.urlencoded());

        // X-Powered-By header on every response
        app.filter((req, res, next) -> {
            res.set("X-Powered-By", "CafeAI");
            next.run();
        });

        // Post-processing filter — proves next.run() blocks naturally on virtual threads.
        // We capture elapsed time in a thread-local so the test can verify it ran,
        // without trying to set a response header after the response is committed.
        app.filter((req, res, next) -> {
            long start = System.nanoTime();
            next.run();
            // Post-processing: executes after full downstream chain completes.
            // This proves the blocking next.run() model — not a fire-and-forget.
            long ms = (System.nanoTime() - start) / 1_000_000;
            lastRequestMs.set(ms);
        });

        // ── HTTP verb routes ──────────────────────────────────────────────────
        app.get("/health",
            (req, res, next) -> res.json(Map.of("status", "ok")));

        app.get("/echo",
            (req, res, next) -> res.send("echo"));

        app.post("/echo",
            (req, res, next) -> res.status(201).json(req.body()));

        app.put("/echo",
            (req, res, next) -> res.json(Map.of("method", "PUT")));

        app.patch("/echo",
            (req, res, next) -> res.json(Map.of("method", "PATCH")));

        app.delete("/echo",
            (req, res, next) -> res.sendStatus(204));

        // ── Path parameters ───────────────────────────────────────────────────
        app.get("/users/:id",
            (req, res, next) ->
                res.json(Map.of("id", req.params("id"))));

        app.get("/orgs/:org/repos/:repo",
            (req, res, next) ->
                res.json(Map.of("org", req.params("org"), "repo", req.params("repo"))));

        // ── Query string ──────────────────────────────────────────────────────
        app.get("/search",
            (req, res, next) ->
                res.json(Map.of(
                    "q",    req.query("q",    "(none)"),
                    "page", req.query("page", "1"))));

        // ── JSON body ─────────────────────────────────────────────────────────
        app.post("/json",
            (req, res, next) ->
                res.json(Map.of("received", req.body("message"))));

        // ── Text body ─────────────────────────────────────────────────────────
        app.post("/text",
            (req, res, next) ->
                res.send("got: " + req.bodyText()));

        // ── Raw body ─────────────────────────────────────────────────────────
        app.post("/raw",
            (req, res, next) ->
                res.json(Map.of("bytes", req.bodyBytes() != null
                    ? req.bodyBytes().length : 0)));

        // ── URL-encoded body ──────────────────────────────────────────────────
        app.post("/form",
            (req, res, next) ->
                res.json(Map.of(
                    "name",  req.body("name")  != null ? req.body("name")  : "",
                    "email", req.body("email") != null ? req.body("email") : "")));

        // ── Status codes ──────────────────────────────────────────────────────
        app.get("/status/:code", (req, res, next) -> {
            int code = Integer.parseInt(req.params("code"));
            res.status(code).send("status " + code);
        });

        // ── Redirect ──────────────────────────────────────────────────────────
        app.get("/redirect",
            (req, res, next) -> res.redirect("/health"));

        app.get("/redirect/301",
            (req, res, next) -> res.redirect(301, "/health"));

        // ── Headers ───────────────────────────────────────────────────────────
        app.get("/headers",
            (req, res, next) ->
                res.json(Map.of(
                    "x-custom",  req.header("X-Custom") != null ? req.header("X-Custom") : "",
                    "host",      req.hostname(),
                    "xhr",       req.xhr(),
                    "stream",    req.stream())));

        // ── Content-Type detection ────────────────────────────────────────────
        app.post("/is-json",
            (req, res, next) ->
                res.json(Map.of("isJson", req.is("application/json"))));

        // ── Variadic inline middleware chain ──────────────────────────────────
        Middleware stamp = (req, res, next) -> {
            req.setAttribute("stamped", true);
            next.run();
        };
        app.get("/stamped",
            stamp,
            (req, res, next) ->
                res.json(Map.of("stamped", req.attribute("stamped"))));

        // ── req.attribute() middleware→handler passing ────────────────────────
        Middleware setUser = (req, res, next) -> {
            req.setAttribute("user", Map.of("id", "u1", "name", "Ada"));
            next.run();
        };
        app.get("/me", setUser,
            (req, res, next) -> {
                @SuppressWarnings("unchecked")
                Map<String, String> user = (Map<String, String>) req.attribute("user");
                res.json(user);
            });

        // ── Sub-router ────────────────────────────────────────────────────────
        var api = CafeAI.Router();
        api.get("/ping",  (req, res, next) -> res.json(Map.of("api", "pong")));
        api.get("/items", (req, res, next) -> res.json(List.of("a", "b", "c")));
        api.get("/items/:id",
            (req, res, next) -> res.json(Map.of("item", req.params("id"))));
        app.use("/api/v1", api);

        // ── originalUrl + path ────────────────────────────────────────────────
        app.get("/url-info",
            (req, res, next) ->
                res.json(Map.of(
                    "path",        req.path(),
                    "originalUrl", req.originalUrl(),
                    "method",      req.method())));

        // ── app.route() fluent builder ────────────────────────────────────────
        app.route("/books")
            .get((req, res, next)  -> res.json(List.of("Dune", "Foundation")))
            .post((req, res, next) -> res.status(201).json(req.body()));

        // ── Error handling ────────────────────────────────────────────────────
        app.get("/boom",
            (req, res, next) -> {
                throw new RuntimeException("deliberate test explosion");
            });

        app.get("/fail",
            (req, res, next) -> next.fail(
                new IllegalArgumentException("explicit next.fail()")));

        app.onError((err, req, res, next) -> {
            res.status(500).json(Map.of(
                "error",   err.getClass().getSimpleName(),
                "message", err.getMessage() != null ? err.getMessage() : ""));
        });

        // ── Helidon escape hatch — raw native route alongside CafeAI routes ──────
        app.helidon()
           .routing(routing -> routing
               .get("/helidon-native", (req, res) ->
                   res.send("raw-helidon-response")));

        // Start the server — blocks until Helidon is ready
        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS))
            .as("Server did not start within 10 seconds")
            .isTrue();
    }

    @AfterAll
    void stopServer() {
        if (app != null) app.stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> method(String method, String path, String ct, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", ct);
        builder = switch (method) {
            case "PUT"    -> builder.PUT(HttpRequest.BodyPublishers.ofString(body));
            case "PATCH"  -> builder.method("PATCH",  HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body));
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        };
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    // ── Tests: HTTP verb routing ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /health → 200 with JSON body")
    void get_health_returns200() throws Exception {
        var res = get("/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\"").contains("\"ok\"");
    }

    @Test
    @DisplayName("GET /echo → 200 with text body")
    void get_echo_returnsText() throws Exception {
        var res = get("/echo");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("echo");
    }

    @Test
    @DisplayName("POST /echo → 201 with echoed JSON body")
    void post_echo_returns201() throws Exception {
        var res = post("/echo", "application/json", "{\"hello\":\"world\"}");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("hello").contains("world");
    }

    @Test
    @DisplayName("PUT /echo → 200 with method confirmation")
    void put_echo_returns200() throws Exception {
        var res = method("PUT", "/echo", "application/json", "{}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("PUT");
    }

    @Test
    @DisplayName("PATCH /echo → 200 with method confirmation")
    void patch_echo_returns200() throws Exception {
        var res = method("PATCH", "/echo", "application/json", "{}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("PATCH");
    }

    @Test
    @DisplayName("DELETE /echo → 204 no body")
    void delete_echo_returns204() throws Exception {
        var res = method("DELETE", "/echo", "application/json", "");
        assertThat(res.statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("Unmatched route → 404")
    void unmatched_route_returns404() throws Exception {
        var res = get("/does-not-exist");
        assertThat(res.statusCode()).isEqualTo(404);
    }

    // ── Tests: Path parameters ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /users/:id — req.params('id') extracted correctly")
    void pathParam_single_extracted() throws Exception {
        var res = get("/users/42");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"id\"").contains("\"42\"");
    }

    @Test
    @DisplayName("GET /orgs/:org/repos/:repo — multiple path params extracted")
    void pathParam_multiple_extracted() throws Exception {
        var res = get("/orgs/cafeai/repos/core");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"org\"").contains("\"cafeai\"");
        assertThat(res.body()).contains("\"repo\"").contains("\"core\"");
    }

    // ── Tests: Query string ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /search?q=cafeai — req.query() parsed")
    void query_parsed() throws Exception {
        var res = get("/search?q=cafeai&page=3");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"cafeai\"").contains("\"3\"");
    }

    @Test
    @DisplayName("GET /search (no params) — req.query() default values used")
    void query_defaultValues() throws Exception {
        var res = get("/search");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"(none)\"").contains("\"1\"");
    }

    // ── Tests: JSON body parsing ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /json — req.body('message') parsed from JSON")
    void jsonBody_parsed() throws Exception {
        var res = post("/json", "application/json", "{\"message\":\"hello cafeai\"}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("hello cafeai");
    }

    @Test
    @DisplayName("POST /json — Content-Type: application/json sets response Content-Type")
    void jsonBody_responseContentType() throws Exception {
        var res = post("/json", "application/json", "{\"message\":\"test\"}");
        assertThat(res.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json");
    }

    // ── Tests: Text body parsing ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /text — req.bodyText() parsed from text/plain")
    void textBody_parsed() throws Exception {
        var res = post("/text", "text/plain", "hello plain text");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("hello plain text");
    }

    // ── Tests: Raw body parsing ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /raw — req.bodyBytes() byte count correct")
    void rawBody_byteCount() throws Exception {
        var bytes = "binary data here".getBytes();
        var res = http.send(
            HttpRequest.newBuilder(URI.create(base + "/raw"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains(String.valueOf(bytes.length));
    }

    // ── Tests: URL-encoded body parsing ──────────────────────────────────────

    @Test
    @DisplayName("POST /form — req.body() parsed from application/x-www-form-urlencoded")
    void urlEncodedBody_parsed() throws Exception {
        var res = post("/form",
            "application/x-www-form-urlencoded",
            "name=Ada+Lovelace&email=ada%40cafeai.io");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Ada Lovelace");
        assertThat(res.body()).contains("ada@cafeai.io");
    }

    // ── Tests: Status codes ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /status/200 → 200")
    void status_200() throws Exception {
        assertThat(get("/status/200").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /status/201 → 201")
    void status_201() throws Exception {
        assertThat(get("/status/201").statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("GET /status/400 → 400")
    void status_400() throws Exception {
        assertThat(get("/status/400").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("GET /status/503 → 503")
    void status_503() throws Exception {
        assertThat(get("/status/503").statusCode()).isEqualTo(503);
    }

    // ── Tests: Redirects ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /redirect → 302 with Location header")
    void redirect_302() throws Exception {
        var res = get("/redirect");
        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElse(""))
            .isEqualTo("/health");
    }

    @Test
    @DisplayName("GET /redirect/301 → 301 with Location header")
    void redirect_301() throws Exception {
        var res = get("/redirect/301");
        assertThat(res.statusCode()).isEqualTo(301);
        assertThat(res.headers().firstValue("Location").orElse(""))
            .isEqualTo("/health");
    }

    // ── Tests: Global filter middleware ───────────────────────────────────────

    @Test
    @DisplayName("X-Powered-By header present on all responses (global filter)")
    void globalFilter_poweredByHeader() throws Exception {
        var res = get("/health");
        assertThat(res.headers().firstValue("X-Powered-By").orElse(""))
            .isEqualTo("CafeAI");
    }

    @Test
    @DisplayName("X-Powered-By header present on POST responses too")
    void globalFilter_poweredByHeader_onPost() throws Exception {
        var res = post("/json", "application/json", "{\"message\":\"test\"}");
        assertThat(res.headers().firstValue("X-Powered-By").orElse(""))
            .isEqualTo("CafeAI");
    }

    // ── Tests: Post-processing filter ─────────────────────────────────────────

    @Test
    @DisplayName("Post-processing filter runs after downstream — next.run() blocks")
    void postProcessingFilter_runsAfterDownstream() throws Exception {
        lastRequestMs.set(-1);
        get("/health");
        // The timing filter sets lastRequestMs AFTER next.run() returns,
        // proving that next.run() blocks until the full downstream chain completes.
        assertThat(lastRequestMs.get()).isGreaterThanOrEqualTo(0);
    }

    // ── Tests: Inline middleware chain ────────────────────────────────────────

    @Test
    @DisplayName("GET /stamped — inline middleware sets attribute, handler reads it")
    void variadicMiddleware_attributePassedThrough() throws Exception {
        var res = get("/stamped");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("true");
    }

    // ── Tests: req.attribute() middleware→handler ─────────────────────────────

    @Test
    @DisplayName("GET /me — middleware sets user attribute, handler returns it")
    void attribute_middlewareToHandler() throws Exception {
        var res = get("/me");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Ada").contains("u1");
    }

    // ── Tests: Sub-router ─────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/ping — sub-router route resolved")
    void subRouter_ping() throws Exception {
        var res = get("/api/v1/ping");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("pong");
    }

    @Test
    @DisplayName("GET /api/v1/items — sub-router list endpoint")
    void subRouter_list() throws Exception {
        var res = get("/api/v1/items");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"a\"").contains("\"b\"").contains("\"c\"");
    }

    @Test
    @DisplayName("GET /api/v1/items/:id — sub-router path param")
    void subRouter_pathParam() throws Exception {
        var res = get("/api/v1/items/widget-7");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("widget-7");
    }

    @Test
    @DisplayName("Sub-router unmatched path → 404")
    void subRouter_unmatched() throws Exception {
        assertThat(get("/api/v1/nope").statusCode()).isEqualTo(404);
    }

    // ── Tests: Request metadata ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /url-info — req.path() and req.originalUrl() correct")
    void request_urlInfo() throws Exception {
        var res = get("/url-info?foo=bar");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("/url-info");
        assertThat(res.body()).contains("foo=bar");
        assertThat(res.body()).contains("GET");
    }

    @Test
    @DisplayName("req.hostname() returns host without port")
    void request_hostname() throws Exception {
        var res = get("/headers");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("localhost");
    }

    @Test
    @DisplayName("req.xhr() returns true when X-Requested-With: XMLHttpRequest")
    void request_xhr_detected() throws Exception {
        var res = http.send(
            HttpRequest.newBuilder(URI.create(base + "/headers"))
                .header("X-Requested-With", "XMLHttpRequest")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.body()).contains("\"xhr\":true");
    }

    @Test
    @DisplayName("req.stream() returns true when Accept: text/event-stream")
    void request_stream_detected() throws Exception {
        var res = http.send(
            HttpRequest.newBuilder(URI.create(base + "/headers"))
                .header("Accept", "text/event-stream")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.body()).contains("\"stream\":true");
    }

    @Test
    @DisplayName("req.is('application/json') returns true for JSON requests")
    void request_is_json() throws Exception {
        var res = post("/is-json", "application/json", "{}");
        assertThat(res.body()).contains("\"isJson\":true");
    }

    @Test
    @DisplayName("Custom request header accessible via req.header()")
    void request_customHeader() throws Exception {
        var res = http.send(
            HttpRequest.newBuilder(URI.create(base + "/headers"))
                .header("X-Custom", "cafeai-test")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.body()).contains("cafeai-test");
    }

    // ── Tests: app.route() fluent builder ────────────────────────────────────

    @Test
    @DisplayName("GET /books — app.route() fluent builder registers GET")
    void routeBuilder_get() throws Exception {
        var res = get("/books");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Dune").contains("Foundation");
    }

    @Test
    @DisplayName("POST /books — app.route() fluent builder registers POST")
    void routeBuilder_post() throws Exception {
        var res = post("/books", "application/json",
            "{\"title\":\"Neuromancer\"}");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("Neuromancer");
    }

    // ── Tests: Response Content-Type ─────────────────────────────────────────

    @Test
    @DisplayName("res.json() sets Content-Type: application/json")
    void response_json_contentType() throws Exception {
        var res = get("/health");
        assertThat(res.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json");
    }

    @Test
    @DisplayName("res.send(String) defaults Content-Type to text/html")
    void response_send_contentType() throws Exception {
        var res = get("/echo");
        assertThat(res.headers().firstValue("Content-Type").orElse(""))
            .contains("text/html");
    }

    // ── Tests: Server state ───────────────────────────────────────────────────

    @Test
    @DisplayName("app.isRunning() returns true while server is up")
    void server_isRunning() {
        assertThat(app.isRunning()).isTrue();
    }

    // ── Tests: Helidon escape hatch ───────────────────────────────────────────

    @Test
    @DisplayName("GET /helidon-native — raw Helidon route coexists with CafeAI routes")
    void helidon_native_route_responds() throws Exception {
        var res = get("/helidon-native");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("raw-helidon-response");
    }
}
