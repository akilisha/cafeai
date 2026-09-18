package io.cafeai.core;

import io.cafeai.core.routing.CookieOptions;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code req.cookies()}, {@code req.accepts*()}, {@code req.fresh()/stale()} and the
 * {@code Expires} cookie attribute, over a real server.
 *
 * <p>Before this, {@code cookies()} was {@code Map.of()} and {@code cookie()} was
 * {@code null} whatever the client sent (the "cookieParser middleware" its Javadoc
 * required never existed), {@code accepts*()} used substring matching that ignored
 * {@code q}-values, {@code fresh()} was hard-wired to {@code false}, and
 * {@code CookieOptions.expires()} was accepted and never written.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestHelpersIntegrationTest {

    private static final String LAST_MODIFIED = "Wed, 01 Jan 2025 00:00:00 GMT";

    private CafeAI     app;
    private String     base;
    private HttpClient http;

    @BeforeAll
    void start() throws Exception {
        int port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        app = CafeAI.create();

        app.get("/cookies", (req, res, next) -> res.json(new LinkedHashMap<>(req.cookies())));
        app.get("/cookie",  (req, res, next) ->
            res.json(Map.of("session", String.valueOf(req.cookie("session")))));

        app.get("/accepts",     (req, res, next) ->
            res.json(Map.of("v", String.valueOf(req.accepts("html", "json")))));
        app.get("/accepts-lang", (req, res, next) ->
            res.json(Map.of("v", String.valueOf(req.acceptsLanguages("en", "fr")))));

        // The response's validators must be set BEFORE asking fresh() — it reads them.
        app.get("/fresh", (req, res, next) -> {
            res.set("ETag", "\"v1\"");
            res.set("Last-Modified", LAST_MODIFIED);
            res.json(Map.of("fresh", req.fresh(), "stale", req.stale()));
        });
        app.get("/fresh-lastmod-only", (req, res, next) -> {
            res.set("Last-Modified", LAST_MODIFIED);
            res.json(Map.of("fresh", req.fresh()));
        });
        app.post("/fresh-post", (req, res, next) -> {
            res.set("ETag", "\"v1\"");
            res.json(Map.of("fresh", req.fresh()));
        });

        app.get("/set-cookie", (req, res, next) -> {
            res.cookie("pref", "dark", CookieOptions.builder()
                .expires(Instant.parse("2030-01-01T00:00:00Z")).build());
            res.end();
        });

        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
    }

    @AfterAll
    void stop() {
        if (app != null) app.stop();
    }

    // ── cookies ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("req.cookies() returns what the client sent (it used to be an empty map)")
    void cookiesAreParsed() throws Exception {
        var res = get("/cookies", "Cookie", "a=1; b=hello%20world");

        assertThat(res.body()).isEqualTo("{\"a\":\"1\",\"b\":\"hello world\"}");
    }

    @Test
    @DisplayName("req.cookie(name) returns the value, and null when the cookie is absent")
    void singleCookie() throws Exception {
        assertThat(get("/cookie", "Cookie", "session=abc123; other=x").body())
            .isEqualTo("{\"session\":\"abc123\"}");
        assertThat(get("/cookie", null, null).body()).isEqualTo("{\"session\":\"null\"}");
    }

    @Test
    @DisplayName("res.cookie(...) writes the Expires attribute in HTTP-date form")
    void expiresIsWritten() throws Exception {
        var res = get("/set-cookie", null, null);

        assertThat(res.headers().firstValue("Set-Cookie").orElse(""))
            .contains("pref=dark").contains("Expires=Tue, 01 Jan 2030 00:00:00 GMT");
    }

    // ── content negotiation ───────────────────────────────────────────────────

    @Test
    @DisplayName("req.accepts() honours q-values")
    void acceptsHonoursQ() throws Exception {
        assertThat(get("/accepts", "Accept", "text/html;q=0.1, application/json;q=0.9").body())
            .isEqualTo("{\"v\":\"json\"}");
        assertThat(get("/accepts", "Accept", "text/html").body()).isEqualTo("{\"v\":\"html\"}");
        assertThat(get("/accepts", "Accept", "image/png").body()).isEqualTo("{\"v\":\"null\"}");
    }

    @Test
    @DisplayName("req.acceptsLanguages() honours the header instead of returning the first offer")
    void acceptsLanguages() throws Exception {
        assertThat(get("/accepts-lang", "Accept-Language", "fr, en;q=0.5").body())
            .isEqualTo("{\"v\":\"fr\"}");
        assertThat(get("/accepts-lang", "Accept-Language", "de").body())
            .isEqualTo("{\"v\":\"null\"}");
    }

    // ── freshness ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fresh() is true when If-None-Match matches the response's ETag (weakly)")
    void freshByEtag() throws Exception {
        assertThat(get("/fresh", "If-None-Match", "\"v1\"").body())
            .contains("\"fresh\":true").contains("\"stale\":false");
        assertThat(get("/fresh", "If-None-Match", "W/\"v1\"").body()).contains("\"fresh\":true");
        assertThat(get("/fresh", "If-None-Match", "\"other\", \"v1\"").body()).contains("\"fresh\":true");
        assertThat(get("/fresh", "If-None-Match", "*").body()).contains("\"fresh\":true");
    }

    @Test
    @DisplayName("fresh() is false when the ETag differs, or there is no conditional header")
    void staleWhenNoMatch() throws Exception {
        assertThat(get("/fresh", "If-None-Match", "\"v2\"").body())
            .contains("\"fresh\":false").contains("\"stale\":true");
        assertThat(get("/fresh", null, null).body())
            .contains("\"fresh\":false").contains("\"stale\":true");
    }

    @Test
    @DisplayName("fresh() uses If-Modified-Since against Last-Modified when there is no If-None-Match")
    void freshByLastModified() throws Exception {
        assertThat(get("/fresh-lastmod-only", "If-Modified-Since", "Thu, 02 Jan 2025 00:00:00 GMT").body())
            .contains("\"fresh\":true");
        assertThat(get("/fresh-lastmod-only", "If-Modified-Since", "Tue, 31 Dec 2024 00:00:00 GMT").body())
            .contains("\"fresh\":false");
    }

    @Test
    @DisplayName("fresh() is false for Cache-Control: no-cache and for non-GET methods")
    void neverFreshWhenForbidden() throws Exception {
        var noCache = HttpRequest.newBuilder(URI.create(base + "/fresh")).GET()
            .header("If-None-Match", "\"v1\"").header("Cache-Control", "no-cache").build();
        assertThat(http.send(noCache, HttpResponse.BodyHandlers.ofString()).body())
            .contains("\"fresh\":false");

        var post = HttpRequest.newBuilder(URI.create(base + "/fresh-post"))
            .POST(HttpRequest.BodyPublishers.noBody()).header("If-None-Match", "\"v1\"").build();
        assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).body())
            .contains("\"fresh\":false");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path, String header, String value) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (header != null) req.header(header, value);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
