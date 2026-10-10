package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth.bearer(...).introspect: the issuer's word on each token (RFC 7662)")
class IntrospectionTest {

    private static final String AUDIENCE = "orders-api";
    private static final String SECRET = "orders-secret";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer fake;
    private CafeAI app;
    private String base;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().client(AUDIENCE, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    private void serve(BearerAuth bearer) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://localhost:" + port;
        app = CafeAI.create();
        app.filter(bearer);
        app.get("/me", (req, res, next) -> res.send(req.identity()
                .map(who -> who.subject() + " " + String.join(",", new java.util.TreeSet<>(who.scopes())))
                .orElse("anonymous")));
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> me(String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/me")).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String challenge(HttpResponse<?> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private static String jwt(String subject) {
        return fake.token().subject(subject).audience(AUDIENCE).scope("orders:read").sign();
    }

    @Test @DisplayName("a JWT the issuer revoked is refused at once, though it hasn't expired")
    void revokedJwt() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET).introspectionCache(Duration.ZERO));
        String token = jwt("alice");
        assertThat(me(token).body()).isEqualTo("alice orders:read");

        fake.revokeAccessToken(token);
        var refused = me(token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(challenge(refused)).contains("invalid_token").contains("no longer active");
    }

    @Test @DisplayName("an opaque token is accepted on the issuer's word, and the caller built from it")
    void opaqueToken() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET).introspectionCache(Duration.ZERO));
        String token = fake.opaqueToken("bob", AUDIENCE, "orders:read", "orders:write");
        assertThat(me(token).body()).isEqualTo("bob orders:read,orders:write");

        fake.revokeAccessToken(token);
        assertThat(me(token).statusCode()).isEqualTo(401);
    }

    @Test @DisplayName("an opaque token for another service, or one the issuer doesn't know, is refused")
    void opaqueRefused() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET).introspectionCache(Duration.ZERO));
        var otherService = me(fake.opaqueToken("bob", "billing-api"));
        assertThat(otherService.statusCode()).isEqualTo(401);
        assertThat(challenge(otherService)).contains("not intended for this service");

        assertThat(me("opaque-never-issued").statusCode()).isEqualTo(401);
    }

    @Test @DisplayName("a token refused locally (forged) is never sent to the issuer")
    void localFailureIsFinal() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET).introspectionCache(Duration.ZERO));
        int before = fake.introspections();
        String forged = fake.token().subject("mallory").audience(AUDIENCE).signedWithUnknownKey().sign();
        assertThat(me(forged).statusCode()).isEqualTo(401);
        assertThat(fake.introspections()).isEqualTo(before);
    }

    @Test @DisplayName("answers are reused for a while: the issuer isn't asked on every request")
    void cached() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET));   // default: 30 seconds
        String token = jwt("carol");
        int before = fake.introspections();
        for (int i = 0; i < 3; i++) assertThat(me(token).statusCode()).isEqualTo(200);
        assertThat(fake.introspections() - before).isEqualTo(1);
    }

    @Test @DisplayName("without introspection, an opaque token is just malformed")
    void opaqueWithoutIntrospection() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE));
        var refused = me(fake.opaqueToken("bob", AUDIENCE));
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(challenge(refused)).contains("malformed");
    }

    @Test @DisplayName("an issuer that can't be asked: 503, since the caller did nothing wrong")
    void issuerDown() throws Exception {
        var gone = FakeIssuer.start().client(AUDIENCE, SECRET);
        var bearer = Auth.bearer(gone.issuer(), AUDIENCE).introspect(AUDIENCE, SECRET).introspectionCache(Duration.ZERO);
        String token = gone.opaqueToken("dave", AUDIENCE);
        serve(bearer);
        gone.close();
        assertThat(me(token).statusCode()).isEqualTo(503);
    }
}
