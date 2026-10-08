package io.cafeai.identity.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.identity.Issuer;
import io.helidon.security.jwt.Jwt;
import io.helidon.security.jwt.SignedJwt;
import io.helidon.json.JsonObject;
import io.helidon.security.jwt.jwk.JwkKeys;
import io.helidon.security.jwt.jwk.JwkRSA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final Map<String, String> clients = new ConcurrentHashMap<>();
    private volatile Duration issuedLifetime = Duration.ofHours(1);
    private volatile SigningKey current;
    private volatile Issuer issuer;

    private FakeIssuer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        id = "http://127.0.0.1:" + server.getAddress().getPort();
        current = SigningKey.generate();
        keys.add(current);
        server.createContext("/.well-known/openid-configuration", this::metadata);
        server.createContext("/jwks", this::jwks);
        server.createContext("/token", this::token);
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

    /**
     * Registers a confidential client that may use the token endpoint (client credentials and
     * token exchange), authenticating with HTTP Basic.
     */
    public FakeIssuer client(String clientId, String clientSecret) {
        clients.put(clientId, clientSecret);
        return this;
    }

    /** How long tokens issued by the token endpoint last (default one hour). */
    public FakeIssuer issuedLifetime(Duration lifetime) {
        this.issuedLifetime = lifetime;
        return this;
    }

    /** How many times the token endpoint has been called, for tests of caching. */
    public int tokenRequests() {
        return tokenRequests.get();
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
                + "\"token_endpoint\":\"" + id + "/token\","
                + "\"grant_types_supported\":[\"client_credentials\","
                + "\"urn:ietf:params:oauth:grant-type:token-exchange\"],"
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

    /**
     * The token endpoint: client credentials (RFC 6749 4.4) and token exchange (RFC 8693). An
     * exchanged token names the same subject, is issued for the requested audience, and carries
     * {@code act} naming the client acting for the subject.
     */
    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"invalid_request\"}");
            return;
        }
        String clientId = authenticatedClient(exchange.getRequestHeaders().getFirst("Authorization"));
        if (clientId == null) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String grant = form.getOrDefault("grant_type", "");
        Jwt.Builder issued;
        if (grant.equals("client_credentials")) {
            issued = issue(clientId, form.get("audience"), form.get("scope")).addPayloadClaim("client_id", clientId);
        } else if (grant.equals("urn:ietf:params:oauth:grant-type:token-exchange")) {
            String subject = verifiedSubject(form.get("subject_token"));
            if (subject == null || form.get("audience") == null) {
                respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            issued = issue(subject, form.get("audience"), form.get("scope"))
                    .addPayloadClaim("client_id", clientId)
                    .addPayloadClaim("act", JsonObject.builder().set("sub", clientId).build());
        } else {
            respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        String token = SignedJwt.sign(issued.build(), current.jwk).tokenContent();
        respond(exchange, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\","
                + "\"expires_in\":" + issuedLifetime.toSeconds() + ","
                + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}");
    }

    private Jwt.Builder issue(String subject, String audience, String scope) {
        Instant now = Instant.now();
        Jwt.Builder jwt = Jwt.builder()
                .algorithm(JwkRSA.ALG_RS256)
                .keyId(current.kid)
                .issuer(id)
                .subject(subject)
                .issueTime(now)
                .expirationTime(now.plus(issuedLifetime))
                .jwtId(UUID.randomUUID().toString());
        if (audience != null) jwt.audience(List.of(audience));
        if (scope != null) jwt.addPayloadClaim("scope", scope);
        return jwt;
    }

    /** The subject of an unexpired token this issuer signed, or {@code null}. */
    private String verifiedSubject(String token) {
        if (token == null) return null;
        try {
            SignedJwt signed = SignedJwt.parseToken(token);
            var keySet = JwkKeys.builder();
            for (SigningKey k : keys) keySet.addKey(k.jwk);
            if (!signed.verifySignature(keySet.build()).isValid()) return null;
            Jwt jwt = signed.getJwt();
            if (!id.equals(jwt.issuer().orElse(null))) return null;
            if (jwt.expirationTime().map(exp -> !Instant.now().isBefore(exp)).orElse(true)) return null;
            return jwt.subject().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String authenticatedClient(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) return null;
        String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        if (colon < 0) return null;
        String clientId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
        String secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
        return secret.equals(clients.get(clientId)) ? clientId : null;
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, json);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
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
