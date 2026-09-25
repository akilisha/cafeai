package io.cafeai.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SpaSessionExample -- what a browser-side SPA can and can't read out of
 * CafeAI's session middleware, and where the rest comes from.
 *
 * <h2>The question this answers</h2>
 * {@code Middleware.encryptedCookieSession(secret)} (and every other session
 * middleware CafeAI ships) sets its cookie {@code HttpOnly} by default
 * (see {@code SessionOptions.defaults()} / {@code CookieOptions}) -- {@code document.cookie}
 * in the SPA can't read it at all, encrypted or not. That's a browser-API
 * restriction, not a crypto one: the browser still sends the cookie
 * automatically on every request to your server, JS just can't touch its
 * value. So if the SPA needs a JWT, or a permissions flag, to decide what
 * to render, that has to come through a <em>different</em> channel. This
 * example shows both standard ones, side by side:
 *
 * <ul>
 *   <li>{@code POST /login} -- reads/writes the real session server-side
 *       (via the encrypted cookie), <em>and separately</em> mints a
 *       short-lived JWT in the response body for the SPA to hold</li>
 *   <li>{@code GET /me} -- the cookie-only pattern: no token at all, just
 *       reads {@code req.session()} (the browser sent the cookie
 *       automatically) and returns a fresh view every call</li>
 *   <li>{@code GET /protected} -- the token-only pattern: verifies
 *       {@code Authorization: Bearer <jwt>}, no cookie involved -- proving
 *       the two channels are genuinely independent (this is exactly the
 *       shape a mobile app or a third-party API consumer would use)</li>
 * </ul>
 *
 * <h2>On the JWT itself</h2>
 * {@link MiniJwt} is a ~30-line hand-rolled HS256 JWT (header.payload.signature,
 * RFC 7519 shape), using the same HMAC-SHA256 technique
 * {@code Middleware.cookieSession(...)} already uses internally. CafeAI does
 * not ship a JWT library or API -- minting/verifying tokens is a solved,
 * commodity problem (jjwt, nimbus-jose-jwt, ...) with nothing CafeAI-specific
 * to add. This is illustrative, not something to copy into production
 * without at least reaching for a real library.
 *
 * <p>The token is deliberately short-lived (15 minutes): unlike the HttpOnly
 * session cookie, a JWT held in JS-reachable memory or storage is exposed to
 * XSS in a way the cookie isn't, so keeping it short-lived limits the blast
 * radius. A real app would pair this with a refresh flow -- not built here,
 * it's a natural extension, not part of the point this example makes.
 *
 * <h2>Running</h2>
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.SpaSessionExample
 * </pre>
 *
 * <h2>Proving the two channels are independent</h2>
 * <pre>
 *   curl -i -c cookies.txt -X POST http://localhost:8080/login \
 *        -H "Content-Type: application/json" -d '{"user":"demo"}'
 *   # -&gt; {"token": "&lt;jwt&gt;"}, and Set-Cookie: cafeai.sid=&lt;encrypted, HttpOnly&gt;
 *
 *   curl -b cookies.txt http://localhost:8080/me
 *   # -&gt; a fresh session-derived view -- no token needed, the cookie did the work
 *
 *   curl -H "Authorization: Bearer &lt;jwt from /login&gt;" http://localhost:8080/protected
 *   # -&gt; claims from the token -- no cookie sent, the cookie played no role here
 * </pre>
 */
public class SpaSessionExample {

    private static final String SESSION_SECRET = "spa-session-example-secret-32-bytes!";
    private static final String JWT_SECRET      = "spa-session-example-jwt-secret-32b!";
    private static final Duration TOKEN_TTL     = Duration.ofMinutes(15);

    public static void main(String[] args) {
        var app = CafeAI.create();

        app.filter(CafeAI.json());
        app.filter(Middleware.encryptedCookieSession(SESSION_SECRET));

        app.post("/login", (req, res, next) -> {
            String user = req.body("user");
            if (user == null || user.isBlank()) {
                res.status(400).json(Map.of("error", "user field required"));
                return;
            }

            // "Authenticate" (hardcoded for the demo) and record real session state.
            req.session().set("userId", user);
            req.session().set("roles", List.of("member"));

            // A separate, short-lived value for the SPA -- not a view into the cookie.
            String token = MiniJwt.issue(Map.of("sub", user, "roles", List.of("member")), TOKEN_TTL, JWT_SECRET);

            res.json(Map.of("token", token));
        });

        app.get("/me", (req, res, next) -> {
            Object userId = req.session().get("userId");
            if (userId == null) {
                res.status(401).json(Map.of("error", "no session -- POST /login first"));
                return;
            }
            res.json(Map.of("userId", userId, "roles", req.session().get("roles")));
        });

        app.get("/protected", (req, res, next) -> {
            String auth = req.header("Authorization");
            String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;

            Optional<Map<String, Object>> claims = token != null
                ? MiniJwt.verify(token, JWT_SECRET) : Optional.empty();

            if (claims.isEmpty()) {
                res.status(401).json(Map.of("error", "missing or invalid bearer token"));
                return;
            }
            res.json(claims.get());
        });

        app.listen(8080, () -> System.out.println("""
            ☕ SpaSessionExample running on http://localhost:8080

               POST /login      → {"user": "..."} sets the session AND returns a JWT
               GET  /me         → cookie-only: reads the session, no token needed
               GET  /protected  → token-only: verifies a Bearer JWT, no cookie needed

            Try:
              curl -i -c cookies.txt -X POST http://localhost:8080/login \\
                   -H "Content-Type: application/json" -d '{"user":"demo"}'

              curl -b cookies.txt http://localhost:8080/me

              curl -H "Authorization: Bearer <token from /login>" \\
                   http://localhost:8080/protected

            Press Ctrl+C to stop.
            """));
    }

    /**
     * A minimal, correctly-shaped HS256 JWT -- header.payload.signature per RFC 7519.
     * See the class Javadoc above for why this is hand-rolled rather than a real library.
     */
    static final class MiniJwt {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

        static String issue(Map<String, Object> claims, Duration ttl, String secret) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>(claims);
                long now = Instant.now().getEpochSecond();
                payload.put("iat", now);
                payload.put("exp", now + ttl.toSeconds());

                String header = encode(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
                String body   = encode(MAPPER.writeValueAsBytes(payload));
                String signingInput = header + "." + body;
                String signature = encode(hmac(signingInput, secret));
                return signingInput + "." + signature;
            } catch (Exception e) {
                throw new RuntimeException("Cannot issue JWT", e);
            }
        }

        @SuppressWarnings("unchecked")
        static Optional<Map<String, Object>> verify(String token, String secret) {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            String signingInput = parts[0] + "." + parts[1];

            try {
                byte[] expected = hmac(signingInput, secret);
                byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
                if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();

                Map<String, Object> claims = MAPPER.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
                long exp = ((Number) claims.get("exp")).longValue();
                if (Instant.now().getEpochSecond() > exp) return Optional.empty();

                return Optional.of(claims);
            } catch (Exception e) {
                return Optional.empty(); // malformed, tampered, or unparseable -- fail closed to "no claims"
            }
        }

        private static byte[] hmac(String data, String secret) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        }

        private static String encode(byte[] bytes) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
