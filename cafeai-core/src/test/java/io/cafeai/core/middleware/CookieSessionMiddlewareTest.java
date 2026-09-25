package io.cafeai.core.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.CafeAI;
import io.cafeai.core.session.SessionOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Middleware.cookieSession(...)} over a real server -- round-trip, tamper
 * detection, secret rotation, idle expiry, invalidation, and the payload-size guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CookieSessionMiddlewareTest {

    private static final String SECRET = "a".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CafeAI     app;
    private String     base;
    private HttpClient http;

    @BeforeAll
    void start() throws Exception {
        int port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        app = CafeAI.create();
        app.filter(CafeAI.json());
        app.filter(Middleware.cookieSession(SECRET));

        app.get("/whoami", (req, res, next) ->
            res.json(Map.of("name", String.valueOf(req.session().get("name")))));

        app.post("/login", (req, res, next) -> {
            req.session().set("name", req.body("name"));
            res.json(Map.of("status", "ok"));
        });

        app.post("/logout", (req, res, next) -> {
            req.session().invalidate();
            res.json(Map.of("status", "ok"));
        });

        app.post("/bloat", (req, res, next) -> {
            req.session().set("blob", "x".repeat(6000));
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
    @DisplayName("attributes set in one request round-trip through the signed cookie to the next")
    void roundTrips() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String cookie = cookieFrom(login);

        var whoami = get("/whoami", cookie);

        assertThat(whoami.body()).contains("\"name\":\"Alex\"");
    }

    @Test
    @DisplayName("a tampered cookie is treated as absent -- a fresh session, no error")
    void tamperedCookieIsTreatedAsAbsent() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String cookie = cookieFrom(login);
        String tampered = cookie.substring(0, cookie.length() - 1)
            + (cookie.charAt(cookie.length() - 1) == 'A' ? 'B' : 'A');

        var whoami = get("/whoami", tampered);

        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("a cookie signed with a different secret is treated as absent")
    void wrongSecretIsTreatedAsAbsent() throws Exception {
        String forged = sign(Map.of("name", "Eve"), Instant.now(), Instant.now(), "not-the-real-secret-1234567890");

        var whoami = get("/whoami", forged);

        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("an idle-expired signed cookie is treated as absent")
    void idleExpiredCookieIsTreatedAsAbsent() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofHours(1));
        String cookie = sign(Map.of("name", "Stale"), stale, stale, SECRET);

        var whoami = get("/whoami", cookie);

        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("invalidate() in a handler clears the cookie -- it does not come back")
    void invalidateClearsTheCookie() throws Exception {
        var login = post("/login", null, "{\"name\":\"Sam\"}");
        String cookie = cookieFrom(login);

        var logout = post("/logout", cookie, "");
        String setCookie = logout.headers().firstValue("Set-Cookie").orElse("");

        assertThat(setCookie).contains("cafeai.sid=;").contains("Max-Age=0");
    }

    @Test
    @DisplayName("a session over the byte limit fails the request rather than silently truncating")
    void oversizedSessionIsRejected() throws Exception {
        var res = post("/bloat", null, "");

        assertThat(res.statusCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("secret rotation: a cookie signed with an old secret still verifies once it's a rotation secret")
    void secretRotationAcceptsAnOldSecret() throws Exception {
        String oldSecret = "b".repeat(32);
        int port = freePort();
        String rotatedBase = "http://localhost:" + port;

        CafeAI rotated = CafeAI.create();
        rotated.filter(CafeAI.json());
        rotated.filter(Middleware.cookieSession(java.util.List.of(SECRET, oldSecret), SessionOptions.defaults()));
        rotated.get("/whoami", (req, res, next) ->
            res.json(Map.of("name", String.valueOf(req.session().get("name")))));

        var latch = new CountDownLatch(1);
        rotated.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
        try {
            String oldCookie = sign(Map.of("name", "Legacy"), Instant.now(), Instant.now(), oldSecret);
            var req = HttpRequest.newBuilder(URI.create(rotatedBase + "/whoami"))
                .header("Cookie", "cafeai.sid=" + oldCookie).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.body()).contains("\"name\":\"Legacy\"");
        } finally {
            rotated.stop();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Builds a validly-signed cookie value independently of BuiltInMiddleware -- same wire format. */
    private static String sign(Map<String, Object> attributes, Instant createdAt, Instant lastAccessedAt, String secret)
            throws Exception {
        var payload = Map.of(
            "attributes", attributes,
            "createdAt", createdAt.toEpochMilli(),
            "lastAccessedAt", lastAccessedAt.toEpochMilli());
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MAPPER.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII)));
        return encodedPayload + "." + signature;
    }

    private static String cookieFrom(HttpResponse<String> res) {
        String setCookie = res.headers().firstValue("Set-Cookie").orElseThrow();
        String afterName = setCookie.substring(setCookie.indexOf("cafeai.sid=") + "cafeai.sid=".length());
        int end = afterName.indexOf(';');
        return end == -1 ? afterName : afterName.substring(0, end);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) req.header("Cookie", "cafeai.sid=" + cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String cookie, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) req.header("Cookie", "cafeai.sid=" + cookie);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
