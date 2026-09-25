package io.cafeai.core.middleware;

import io.cafeai.core.CafeAI;
import io.cafeai.core.session.Session;
import io.cafeai.core.session.SessionOptions;
import io.cafeai.core.session.SessionStore;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Middleware.session(...)} over a real server -- cookie issuance,
 * cross-request persistence, idle expiry, and invalidation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionMiddlewareTest {

    private CafeAI     app;
    private String     base;
    private HttpClient http;
    private SessionStore store;

    @BeforeAll
    void start() throws Exception {
        int port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        store = SessionStore.inMemory();

        app = CafeAI.create();
        app.filter(CafeAI.json());
        app.filter(Middleware.session(store));

        app.get("/whoami", (req, res, next) ->
            res.json(Map.of(
                "id",   req.session().id(),
                "name", String.valueOf(req.session().get("name")))));

        app.post("/login", (req, res, next) -> {
            req.session().set("name", req.body("name"));
            res.json(Map.of("status", "ok"));
        });

        app.post("/logout", (req, res, next) -> {
            req.session().invalidate();
            res.json(Map.of("status", "ok"));
        });

        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
    }

    @AfterAll
    void stop() {
        if (app != null) app.stop();
    }

    @Test
    @DisplayName("no cookie -> a new session is created and its cookie is set")
    void noCookieCreatesNewSessionAndSetsCookie() throws Exception {
        var res = get("/whoami", null);

        String setCookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertThat(setCookie).contains("cafeai.sid=");
        assertThat(res.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("a valid cookie loads the existing session -- attributes survive across requests")
    void validCookieLoadsExistingSessionAndAttributesSurvive() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String sid = sidFrom(login);

        var whoami = get("/whoami", sid);

        assertThat(whoami.body()).contains("\"name\":\"Alex\"");
    }

    @Test
    @DisplayName("invalidate() in a handler clears the cookie on THIS response, synchronously")
    void invalidateInHandlerClearsCookieOnThisResponse() throws Exception {
        var login = post("/login", null, "{\"name\":\"Sam\"}");
        String sid = sidFrom(login);

        var logout = post("/logout", sid, "");

        // Two Set-Cookie headers are sent for cafeai.sid: the middleware's
        // pre-next.run() set (see BuiltInMiddleware.session()'s Javadoc), then
        // invalidate()'s synchronous clear. Per RFC 6265, a client applies
        // Set-Cookie headers in order, so the clearing one wins client-side --
        // assert it's present among all of them, not just the first.
        var setCookies = logout.headers().allValues("Set-Cookie");
        assertThat(setCookies).anySatisfy(c ->
            assertThat(c).contains("cafeai.sid=;").contains("Max-Age=0"));
        assertThat(store.exists(sid)).isFalse();
    }

    @Test
    @DisplayName("an idle-expired session is treated as absent -- a fresh one is issued")
    void idleExpiredSessionIsTreatedAsAbsent() throws Exception {
        Session expired = new Session("expired-id", Map.of("name", "Stale"),
            Instant.now().minus(Duration.ofHours(1)), Instant.now().minus(Duration.ofHours(1)));
        store.save(expired);

        var res = get("/whoami", "expired-id");

        assertThat(res.body()).contains("\"name\":\"null\"");
        String setCookie = res.headers().firstValue("Set-Cookie").orElse("");
        assertThat(setCookie).doesNotContain("cafeai.sid=expired-id");
    }

    @Test
    @DisplayName("req.session() with no session middleware registered -> 500, not a silent null")
    void sessionWithoutMiddlewareThrows() throws Exception {
        int port = freePort();
        String noSessionBase = "http://localhost:" + port;
        CafeAI bare = CafeAI.create();
        bare.get("/no-session", (req, res, next) -> res.json(Map.of("id", req.session().id())));

        var latch = new CountDownLatch(1);
        bare.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
        try {
            var req = HttpRequest.newBuilder(URI.create(noSessionBase + "/no-session")).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(500);
        } finally {
            bare.stop();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String sidFrom(HttpResponse<String> res) {
        String setCookie = res.headers().firstValue("Set-Cookie").orElseThrow();
        String afterName = setCookie.substring(setCookie.indexOf("cafeai.sid=") + "cafeai.sid=".length());
        int end = afterName.indexOf(';');
        return end == -1 ? afterName : afterName.substring(0, end);
    }

    private HttpResponse<String> get(String path, String sidCookie) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (sidCookie != null) req.header("Cookie", "cafeai.sid=" + sidCookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String sidCookie, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sidCookie != null) req.header("Cookie", "cafeai.sid=" + sidCookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
