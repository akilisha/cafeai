package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Auth.bearer: an OAuth 2.0 resource server over any OpenID Connect issuer")
class BearerAuthTest {

    private static final String AUDIENCE = "orders-api";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer fake;
    private CafeAI app;
    private String base;
    private final AtomicReference<Identity> seen = new AtomicReference<>();
    private final AtomicReference<Identity> seenCurrent = new AtomicReference<>();

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    /** Starts an app with {@code auth} in front of one route that records what it saw. */
    private void serve(Middleware auth) throws Exception {
        app = CafeAI.create();
        app.filter(auth);
        app.get("/me", (req, res, next) -> {
            seen.set(req.identity().orElse(null));
            seenCurrent.set(Identity.current().orElse(null));
            res.json(Map.of("subject", req.identity().map(Identity::subject).orElse("anonymous")));
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        base = "http://localhost:" + app.port();
    }

    private HttpResponse<String> get(String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/me")).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithToken(String token) throws Exception {
        return get("Bearer " + token);
    }

    private static String challenge(HttpResponse<?> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    // -- accepted ----------------------------------------------------------------------------

    @Test @DisplayName("a valid token reaches the handler as an Identity, with its RFC 9068 claims")
    void validToken() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        String token = fake.token().subject("alice").audience(AUDIENCE).name("Alice")
                .scope("orders:read", "orders:write").groups("sales").roles("approver")
                .entitlements("refunds").claim("department", "emea").sign();

        var response = getWithToken(token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"subject\":\"alice\"");
        Identity who = seen.get();
        assertThat(who.issuer()).isEqualTo(fake.id());
        assertThat(who.subject()).isEqualTo("alice");
        assertThat(who.name()).contains("Alice");
        assertThat(who.scopes()).containsExactlyInAnyOrder("orders:read", "orders:write");
        assertThat(who.hasScope("orders:write")).isTrue();
        assertThat(who.inGroup("sales")).isTrue();
        assertThat(who.hasRole("approver")).isTrue();
        assertThat(who.hasEntitlement("refunds")).isTrue();
        assertThat(who.claim("department")).contains("emea");
        assertThat(who.key()).isEqualTo(new Identity.Key(fake.id(), "alice"));
        assertThat(who.toString()).doesNotContain("emea");
    }

    @Test @DisplayName("Identity.current() finds the request's identity from code below the handler")
    void currentIdentity() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        getWithToken(fake.token().subject("bob").audience(AUDIENCE).sign());
        assertThat(seenCurrent.get()).isNotNull();
        assertThat(seenCurrent.get().subject()).isEqualTo("bob");
        assertThat(Identity.current()).isEmpty();   // this test thread serves no request
    }

    @Test @DisplayName("any one of several audiences is enough, and a token may name several")
    void audiences() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE, "orders-api-v2"));
        assertThat(getWithToken(fake.token().audience("orders-api-v2").sign()).statusCode()).isEqualTo(200);
        assertThat(getWithToken(fake.token().audience("billing", AUDIENCE).sign()).statusCode()).isEqualTo(200);
    }

    @Test @DisplayName("the scheme name is case-insensitive")
    void schemeCase() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertThat(get("bearer " + fake.token().audience(AUDIENCE).sign()).statusCode()).isEqualTo(200);
    }

    // -- refused: no or unusable credentials --------------------------------------------------

    @Test @DisplayName("no token: 401 with a bare Bearer challenge")
    void noToken() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        var response = get(null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response)).isEqualTo("Bearer");
        assertThat(seen.get()).isNull();
    }

    @Test @DisplayName("an Authorization header that isn't one Bearer token: 400 invalid_request")
    void notBearer() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        for (String header : new String[]{"Basic dXNlcjpwYXNz", "Bearer", "Bearer a b"}) {
            var response = get(header);
            assertThat(response.statusCode()).as(header).isEqualTo(400);
            assertThat(challenge(response)).as(header).contains("error=\"invalid_request\"");
        }
    }

    @Test @DisplayName("optional(): anonymous without a token, but a bad token is still refused")
    void optional() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        var anonymous = get(null);
        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.body()).contains("anonymous");
        assertThat(getWithToken(fake.token().audience("billing").sign()).statusCode()).isEqualTo(401);
        assertThat(getWithToken(fake.token().subject("carol").audience(AUDIENCE).sign()).body()).contains("carol");
    }

    // -- refused: the token itself --------------------------------------------------------------

    private void assertRefused(String token, String reason) throws Exception {
        var response = getWithToken(token);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response)).contains("error=\"invalid_token\"").contains(reason);
        assertThat(seen.get()).isNull();
    }

    @Test @DisplayName("a token that isn't a JWT")
    void malformed() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertRefused("not-a-jwt", "malformed");
        assertRefused("a.b.c", "malformed");
    }

    @Test @DisplayName("an expired token, outside the clock-skew allowance")
    void expired() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        String expired = fake.token().audience(AUDIENCE)
                .issuedAt(Instant.now().minus(Duration.ofHours(2))).lifetime(Duration.ofHours(1)).sign();
        assertRefused(expired, "expired");
    }

    @Test @DisplayName("a token that expired moments ago is still accepted within the skew")
    void expiredWithinSkew() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        String justExpired = fake.token().audience(AUDIENCE)
                .issuedAt(Instant.now().minus(Duration.ofMinutes(10)))
                .lifetime(Duration.ofMinutes(10).minusSeconds(20)).sign();
        assertThat(getWithToken(justExpired).statusCode()).isEqualTo(200);
    }

    @Test @DisplayName("a token whose nbf is in the future")
    void notYetValid() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertRefused(fake.token().audience(AUDIENCE).notBefore(Instant.now().plus(Duration.ofMinutes(5))).sign(),
                "not valid yet");
    }

    @Test @DisplayName("a token claiming another issuer, though signed with this issuer's key")
    void wrongIssuer() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertRefused(fake.token().audience(AUDIENCE).issuer("https://elsewhere.example.com").sign(),
                "different issuer");
    }

    @Test @DisplayName("a token for another service, or for no service")
    void wrongAudience() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertRefused(fake.token().audience("billing-api").sign(), "not intended for this service");
        assertRefused(fake.token().sign(), "not intended for this service");
    }

    @Test @DisplayName("a token signed with a key the issuer never published")
    void unknownKey() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        assertRefused(fake.token().audience(AUDIENCE).signedWithUnknownKey().sign(), "unknown key");
    }

    @Test @DisplayName("a token whose payload was changed after signing")
    void tampered() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        String[] parts = fake.token().subject("alice").audience(AUDIENCE).sign().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"alice\"", "\"admin\"");
        String forged = parts[0] + "." + b64(payload) + "." + parts[2];
        assertRefused(forged, "signature is invalid");
    }

    @Test @DisplayName("alg none, and a shared-secret HS256 header naming the issuer's key id")
    void algorithmAttacks() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        String[] parts = fake.token().subject("alice").audience(AUDIENCE).sign().split("\\.");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);

        String none = b64(header.replace("\"RS256\"", "\"none\"")) + "." + parts[1] + ".";
        assertRefused(none, "invalid_token");
        String noneSigned = b64(header.replace("\"RS256\"", "\"none\"")) + "." + parts[1] + ".c2ln";
        assertRefused(noneSigned, "algorithm is not accepted");
        String hs256 = b64(header.replace("\"RS256\"", "\"HS256\"")) + "." + parts[1] + "." + parts[2];
        assertRefused(hs256, "algorithm is not accepted");
    }

    @Test @DisplayName("requireAccessTokenType(): only typ at+jwt is accepted")
    void accessTokenType() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).requireAccessTokenType());
        assertRefused(fake.token().audience(AUDIENCE).sign(), "not an access token");
        assertThat(getWithToken(fake.token().audience(AUDIENCE).type("at+jwt").sign()).statusCode()).isEqualTo(200);
    }

    @Test @DisplayName("the issuer's keys can't be read at all: 503, not the caller's fault")
    void issuerUnreachable() throws Exception {
        serve(Auth.bearer(Issuer.of(fake.id(), "http://127.0.0.1:1/jwks"), AUDIENCE));
        var response = getWithToken(fake.token().audience(AUDIENCE).sign());
        assertThat(response.statusCode()).isEqualTo(503);
    }

    // -- configuration ----------------------------------------------------------------------

    @Test @DisplayName("an audience is required, and only asymmetric algorithms can be chosen")
    void configuration() {
        assertThatThrownBy(() -> Auth.bearer(fake.issuer())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> Auth.bearer(fake.issuer(), AUDIENCE).algorithms("HS256"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Auth.bearer(fake.issuer(), AUDIENCE).algorithms("none"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("narrowed algorithms refuse the rest")
    void narrowedAlgorithms() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).algorithms("ES256"));
        assertRefused(fake.token().audience(AUDIENCE).sign(), "algorithm is not accepted");
    }

    // -- the issuer -----------------------------------------------------------------------------

    @Nested @DisplayName("Issuer")
    class IssuerTests {

        @Test @DisplayName("discovery must name exactly the requested issuer")
        void issuerMismatch() {
            assertThatThrownBy(() -> Issuer.discover(fake.id() + "/"))
                    .isInstanceOf(IdentityException.class).hasMessageContaining("names issuer");
        }

        @Test @DisplayName("plain http is refused except on loopback")
        void httpsOnly() {
            assertThatThrownBy(() -> Issuer.of("http://issuer.example.com", "http://issuer.example.com/jwks"))
                    .isInstanceOf(IdentityException.class).hasMessageContaining("https");
            assertThatThrownBy(() -> Issuer.of("https://issuer.example.com", "http://issuer.example.com/jwks"))
                    .isInstanceOf(IdentityException.class).hasMessageContaining("jwks_uri");
        }

        @Test @DisplayName("a rotated key is picked up on first sight, with one refresh")
        void keyRotation() throws Exception {
            try (var rotating = FakeIssuer.start()) {
                var issuer = Issuer.discover(rotating.id());
                serve(Auth.bearer(issuer, AUDIENCE));

                assertThat(getWithToken(rotating.token().audience(AUDIENCE).sign()).statusCode()).isEqualTo(200);
                assertThat(rotating.keySetRequests()).isEqualTo(1);

                rotating.rotateKey();
                assertThat(getWithToken(rotating.token().audience(AUDIENCE).sign()).statusCode()).isEqualTo(200);
                assertThat(getWithToken(rotating.token().audience(AUDIENCE).sign()).statusCode()).isEqualTo(200);
                assertThat(rotating.keySetRequests()).isEqualTo(2);
            }
        }

        @Test @DisplayName("forged key ids can't make CafeAI hammer the issuer")
        void unknownKeyRefreshIsRateLimited() throws Exception {
            try (var target = FakeIssuer.start()) {
                serve(Auth.bearer(Issuer.discover(target.id()), AUDIENCE));
                assertThat(getWithToken(target.token().audience(AUDIENCE).sign()).statusCode()).isEqualTo(200);
                for (int i = 0; i < 5; i++) {
                    assertThat(getWithToken(target.token().audience(AUDIENCE).signedWithUnknownKey().sign())
                            .statusCode()).isEqualTo(401);
                }
                assertThat(target.keySetRequests()).isEqualTo(2);   // first fetch + one refresh
            }
        }

        @Test @DisplayName("a withdrawn key stops being trusted once the cache is older than maxKeyAge")
        void withdrawnKey() throws Exception {
            try (var target = FakeIssuer.start()) {
                var clock = new MutableClock(Instant.now());
                var issuer = Issuer.discover(target.id(), clock).maxKeyAge(Duration.ofMinutes(10));
                serve(Auth.bearer(issuer, AUDIENCE));

                String oldKeyToken = target.token().audience(AUDIENCE).sign();
                assertThat(getWithToken(oldKeyToken).statusCode()).isEqualTo(200);

                target.rotateKey();
                target.retireOldKeys();
                assertThat(getWithToken(oldKeyToken).statusCode()).isEqualTo(200);   // still cached

                clock.advance(Duration.ofMinutes(11));
                assertThat(getWithToken(oldKeyToken).statusCode()).isEqualTo(401);
            }
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone()             { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant()                { return now; }
    }
}
