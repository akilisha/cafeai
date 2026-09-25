package io.cafeai.core.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.CafeAI;
import io.cafeai.core.session.SessionOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code Middleware.encryptedCookieSession(...)} over a real server -- round-trip,
 * confidentiality, tamper detection, key rotation, idle expiry, invalidation, and
 * the payload-size guard. Mirrors {@link CookieSessionMiddlewareTest}, with one
 * additional test that {@code cookieSession} has no equivalent of: proving the
 * cookie is actually unreadable, not just unforgeable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncryptedCookieSessionMiddlewareTest {

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
        app.filter(Middleware.encryptedCookieSession(SECRET));

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
    @DisplayName("attributes set in one request round-trip through the encrypted cookie to the next")
    void roundTrips() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String cookie = cookieFrom(login);

        var whoami = get("/whoami", cookie);

        assertThat(whoami.body()).contains("\"name\":\"Alex\"");
    }

    @Test
    @DisplayName("the cookie is genuinely unreadable, not just unforgeable -- this is what cookieSession lacks")
    void cookieContentsAreConfidential() throws Exception {
        var login = post("/login", null, "{\"name\":\"super-secret-value-xyz\"}");
        String cookie = cookieFrom(login);

        assertThat(cookie).doesNotContain("super-secret-value-xyz");

        // The ciphertext segment must not parse as the JSON it plaintext-encodes as --
        // proving it's genuine ciphertext, not just base64 obfuscation.
        String ciphertextSegment = cookie.substring(cookie.indexOf('.') + 1);
        byte[] decoded = Base64.getUrlDecoder().decode(ciphertextSegment);
        assertThatThrownBy(() -> MAPPER.readTree(decoded))
            .as("ciphertext must not be parseable JSON")
            .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a tampered ciphertext is treated as absent -- a fresh session, no error")
    void tamperedCiphertextIsTreatedAsAbsent() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String cookie = cookieFrom(login);
        String tampered = cookie.substring(0, cookie.length() - 1)
            + (cookie.charAt(cookie.length() - 1) == 'A' ? 'B' : 'A');

        var whoami = get("/whoami", tampered);

        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("a tampered IV is treated as absent -- a fresh session, no error")
    void tamperedIvIsTreatedAsAbsent() throws Exception {
        var login = post("/login", null, "{\"name\":\"Alex\"}");
        String cookie = cookieFrom(login);
        int dot = cookie.indexOf('.');
        String tampered = cookie.substring(0, dot - 1)
            + (cookie.charAt(dot - 1) == 'A' ? 'B' : 'A')
            + cookie.substring(dot);

        var whoami = get("/whoami", tampered);

        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("a cookie encrypted with a different key is treated as absent")
    void wrongKeyIsTreatedAsAbsent() throws Exception {
        String forged = encrypt(Map.of("name", "Eve"), Instant.now(), Instant.now(), "not-the-real-secret-1234567890");

        var whoami = get("/whoami", forged);

        assertThat(whoami.statusCode()).isEqualTo(200);
        assertThat(whoami.body()).contains("\"name\":\"null\"");
    }

    @Test
    @DisplayName("an idle-expired encrypted cookie is treated as absent")
    void idleExpiredCookieIsTreatedAsAbsent() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofHours(1));
        String cookie = encrypt(Map.of("name", "Stale"), stale, stale, SECRET);

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
    @DisplayName("key rotation: a cookie encrypted with an old key still decrypts once it's a rotation key")
    void keyRotationAcceptsAnOldKey() throws Exception {
        String oldSecret = "b".repeat(32);
        int port = freePort();
        String rotatedBase = "http://localhost:" + port;

        CafeAI rotated = CafeAI.create();
        rotated.filter(CafeAI.json());
        rotated.filter(Middleware.encryptedCookieSession(List.of(SECRET, oldSecret), SessionOptions.defaults()));
        rotated.get("/whoami", (req, res, next) ->
            res.json(Map.of("name", String.valueOf(req.session().get("name")))));

        var latch = new CountDownLatch(1);
        rotated.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
        try {
            String oldCookie = encrypt(Map.of("name", "Legacy"), Instant.now(), Instant.now(), oldSecret);
            var req = HttpRequest.newBuilder(URI.create(rotatedBase + "/whoami"))
                .header("Cookie", "cafeai.sid=" + oldCookie).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(res.body()).contains("\"name\":\"Legacy\"");
        } finally {
            rotated.stop();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Builds a validly-encrypted cookie value independently of BuiltInMiddleware -- same wire format. */
    private static String encrypt(Map<String, Object> attributes, Instant createdAt, Instant lastAccessedAt, String secret)
            throws Exception {
        var payload = Map.of(
            "attributes", attributes,
            "createdAt", createdAt.toEpochMilli(),
            "lastAccessedAt", lastAccessedAt.toEpochMilli());
        byte[] plaintext = MAPPER.writeValueAsBytes(payload);

        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        String encodedIv         = Base64.getUrlEncoder().withoutPadding().encodeToString(iv);
        String encodedCiphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        return encodedIv + "." + encodedCiphertext;
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
