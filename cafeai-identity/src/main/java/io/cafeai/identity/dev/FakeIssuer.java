package io.cafeai.identity.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.identity.Issuer;
import io.helidon.security.jwt.Jwt;
import io.helidon.security.jwt.SignedJwt;
import io.helidon.security.jwt.jwk.JwkRSA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in OpenID Connect issuer for tests and local development: no real identity provider
 * needed.
 *
 * <p>It runs an HTTP server on the loopback interface that publishes Discovery metadata and a
 * JWK set, and it signs access tokens with its own RSA key.
 *
 * <pre>{@code
 *   try (var fake = FakeIssuer.start()) {
 *       app.filter(Auth.bearer(fake.issuer(), "orders-api"));
 *       String token = fake.token().subject("alice").audience("orders-api").scope("orders:read").sign();
 *       // call the app with "Authorization: Bearer " + token
 *   }
 * }</pre>
 *
 * <p><b>Never use it in production.</b> Anyone who can reach it can obtain a token for anyone.
 * It listens on the loopback interface only and logs a warning when it starts.
 */
public final class FakeIssuer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FakeIssuer.class);

    private final HttpServer server;
    private final String id;
    private final List<SigningKey> keys = new CopyOnWriteArrayList<>();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private volatile SigningKey current;
    private volatile Issuer issuer;

    private FakeIssuer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        id = "http://127.0.0.1:" + server.getAddress().getPort();
        current = SigningKey.generate();
        keys.add(current);
        server.createContext("/.well-known/openid-configuration", this::metadata);
        server.createContext("/jwks", this::jwks);
        server.start();
        log.warn("FakeIssuer started at {}. It signs tokens for anyone; development and tests only.", id);
    }

    /** Starts a fake issuer on a free loopback port. */
    public static FakeIssuer start() {
        try {
            return new FakeIssuer();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start FakeIssuer", e);
        }
    }

    /** The issuer identifier its tokens carry in {@code iss}. */
    public String id() { return id; }

    /** This issuer, discovered the way a real one would be. Created once, on first call. */
    public Issuer issuer() {
        Issuer i = issuer;
        if (i == null) {
            synchronized (this) {
                if (issuer == null) issuer = Issuer.discover(id);
                i = issuer;
            }
        }
        return i;
    }

    /** Starts a token, signed with the current key. Defaults: one hour, no audience, no scope. */
    public TokenBuilder token() {
        return new TokenBuilder(this);
    }

    /**
     * Rotates the signing key: a new key signs from now on. The old key stays published until
     * {@link #retireOldKeys()}, as a real issuer keeps it while its tokens are still in use.
     *
     * @return the new key's id
     */
    public String rotateKey() {
        SigningKey next = SigningKey.generate();
        keys.add(next);
        current = next;
        return next.kid;
    }

    /** Stops publishing every key except the current one. */
    public void retireOldKeys() {
        keys.removeIf(k -> k != current);
    }

    /** How many times the key set has been fetched, for tests of caching. */
    public int keySetRequests() {
        return jwksRequests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // -- endpoints -------------------------------------------------------------------------

    private void metadata(HttpExchange exchange) throws IOException {
        String json = "{\"issuer\":\"" + id + "\","
                + "\"jwks_uri\":\"" + id + "/jwks\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        respond(exchange, json);
    }

    private void jwks(HttpExchange exchange) throws IOException {
        jwksRequests.incrementAndGet();
        List<String> published = new ArrayList<>();
        for (SigningKey k : keys) published.add(k.publicJwk());
        respond(exchange, "{\"keys\":[" + String.join(",", published) + "]}");
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // -- keys ------------------------------------------------------------------------------

    private record SigningKey(String kid, RSAPublicKey publicKey, JwkRSA jwk) {

        static SigningKey generate() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                String kid = UUID.randomUUID().toString();
                RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
                JwkRSA jwk = JwkRSA.builder()
                        .publicKey(pub)
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyId(kid)
                        .algorithm(JwkRSA.ALG_RS256)
                        .build();
                return new SigningKey(kid, pub, jwk);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        String publicJwk() {
            return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\","
                    + "\"n\":\"" + base64Url(publicKey.getModulus()) + "\","
                    + "\"e\":\"" + base64Url(publicKey.getPublicExponent()) + "\"}";
        }

        private static String base64Url(BigInteger value) {
            byte[] bytes = value.toByteArray();
            // Unsigned big-endian (RFC 7518 6.3.1): drop the sign byte BigInteger adds.
            if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    // -- tokens ----------------------------------------------------------------------------

    /** Builds one access token. */
    public static final class TokenBuilder {
        private final FakeIssuer fake;
        private String issuer;
        private String subject = "user";
        private final List<String> audience = new ArrayList<>();
        private final List<String> scopes = new ArrayList<>();
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofHours(1);
        private Instant notBefore;
        private String type;
        private boolean signWithStrangerKey;

        private TokenBuilder(FakeIssuer fake) {
            this.fake = fake;
            this.issuer = fake.id;
        }

        public TokenBuilder subject(String subject)          { this.subject = subject; return this; }
        public TokenBuilder audience(String... audience)     { this.audience.addAll(List.of(audience)); return this; }
        public TokenBuilder scope(String... scopes)          { this.scopes.addAll(List.of(scopes)); return this; }
        public TokenBuilder groups(String... groups)         { claims.put("groups", List.of(groups)); return this; }
        public TokenBuilder roles(String... roles)           { claims.put("roles", List.of(roles)); return this; }
        public TokenBuilder entitlements(String... values)   { claims.put("entitlements", List.of(values)); return this; }
        public TokenBuilder name(String name)                { claims.put("name", name); return this; }
        public TokenBuilder claim(String name, Object value) { claims.put(name, value); return this; }

        /** How long from issue until it expires (default one hour). */
        public TokenBuilder lifetime(Duration lifetime)      { this.lifetime = lifetime; return this; }

        /** When it was issued (default now); with {@link #lifetime}, decides expiry. */
        public TokenBuilder issuedAt(Instant issuedAt)       { this.issuedAt = issuedAt; return this; }

        public TokenBuilder notBefore(Instant notBefore)     { this.notBefore = notBefore; return this; }

        /** The JOSE {@code typ} header, e.g. {@code "at+jwt"} (RFC 9068). Unset by default. */
        public TokenBuilder type(String type)                { this.type = type; return this; }

        /** Claims a different issuer in {@code iss}, still signed with this issuer's key. */
        public TokenBuilder issuer(String issuer)            { this.issuer = issuer; return this; }

        /** Signs with a key this issuer never published, as a forger would. */
        public TokenBuilder signedWithUnknownKey()           { this.signWithStrangerKey = true; return this; }

        /** The signed token, ready for an {@code Authorization: Bearer} header. */
        public String sign() {
            SigningKey key = signWithStrangerKey ? SigningKey.generate() : fake.current;
            Jwt.Builder jwt = Jwt.builder()
                    .algorithm(JwkRSA.ALG_RS256)
                    .keyId(key.kid)
                    .issuer(Objects.requireNonNull(issuer))
                    .subject(subject)
                    .issueTime(issuedAt)
                    .expirationTime(issuedAt.plus(lifetime))
                    .jwtId(UUID.randomUUID().toString());
            if (type != null) jwt.type(type);
            if (notBefore != null) jwt.notBefore(notBefore);
            if (!audience.isEmpty()) jwt.audience(audience);
            if (!scopes.isEmpty()) jwt.addPayloadClaim("scope", String.join(" ", scopes));
            claims.forEach(jwt::addPayloadClaim);
            return SignedJwt.sign(jwt.build(), key.jwk).tokenContent();
        }
    }
}
