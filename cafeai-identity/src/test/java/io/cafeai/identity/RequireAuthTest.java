package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Auth.require: checking what the issuer granted, per route")
class RequireAuthTest {

    private static final String AUDIENCE = "orders-api";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer fake;
    private CafeAI app;
    private String base;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void startApp() throws Exception {
        app = CafeAI.create();
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        app.get("/orders", Auth.require(Auth.scope("orders:read")), (req, res, next) -> res.send("orders"));
        app.post("/refunds", Auth.require(Auth.role("approver"), Auth.scope("orders:write")),
                (req, res, next) -> res.send("refunded"));
        app.get("/reports", Auth.require(Auth.anyOf(Auth.group("finance"), Auth.role("auditor"))),
                (req, res, next) -> res.send("reports"));
        app.get("/beta", Auth.require(Auth.entitlement("beta")), (req, res, next) -> res.send("beta"));
        app.get("/either", Auth.require(Auth.anyOf(Auth.scope("a:read"), Auth.scope("b:read"))),
                (req, res, next) -> res.send("either"));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void stopApp() {
        app.stop();
    }

    private HttpResponse<String> call(String method, String path, FakeIssuer.TokenBuilder token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token.audience(AUDIENCE).sign());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String challenge(HttpResponse<?> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    @Test @DisplayName("a granted scope passes")
    void scopeGranted() throws Exception {
        var response = call("GET", "/orders", fake.token().scope("orders:read", "other"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("orders");
    }

    @Test @DisplayName("a missing scope: 403 insufficient_scope naming the scope")
    void scopeMissing() throws Exception {
        var response = call("GET", "/orders", fake.token().scope("orders:write"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(challenge(response)).isEqualTo("Bearer error=\"insufficient_scope\", scope=\"orders:read\"");
    }

    @Test @DisplayName("an anonymous caller: 401, not 403")
    void anonymous() throws Exception {
        var response = call("GET", "/orders", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response)).isEqualTo("Bearer");
    }

    @Test @DisplayName("every requirement must hold; a missing role is a 403 that names nothing")
    void allRequired() throws Exception {
        assertThat(call("POST", "/refunds", fake.token().roles("approver").scope("orders:write")).statusCode())
                .isEqualTo(200);

        var noRole = call("POST", "/refunds", fake.token().scope("orders:write"));
        assertThat(noRole.statusCode()).isEqualTo(403);
        assertThat(challenge(noRole)).isEmpty();

        var noScope = call("POST", "/refunds", fake.token().roles("approver"));
        assertThat(noScope.statusCode()).isEqualTo(403);
        assertThat(challenge(noScope)).contains("scope=\"orders:write\"");
    }

    @Test @DisplayName("anyOf: one option is enough")
    void anyOf() throws Exception {
        assertThat(call("GET", "/reports", fake.token().groups("finance")).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/reports", fake.token().roles("auditor")).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/reports", fake.token().groups("sales").roles("approver")).statusCode())
                .isEqualTo(403);
    }

    @Test @DisplayName("anyOf over scopes names every scope that would do")
    void anyOfScopes() throws Exception {
        assertThat(call("GET", "/either", fake.token().scope("b:read")).statusCode()).isEqualTo(200);
        var response = call("GET", "/either", fake.token().scope("c:read"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(challenge(response)).contains("scope=\"a:read b:read\"");
    }

    @Test @DisplayName("entitlements are checked like the rest")
    void entitlement() throws Exception {
        assertThat(call("GET", "/beta", fake.token().entitlements("beta")).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/beta", fake.token()).statusCode()).isEqualTo(403);
    }

    @Test @DisplayName("signedIn: any verified caller passes, whatever the token grants; anonymous gets 401")
    void signedIn() throws Exception {
        app.stop();
        app = CafeAI.create();
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        app.get("/inbox", Auth.signedIn(), (req, res, next) -> res.send("mail"));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        base = "http://localhost:" + app.port();

        assertThat(call("GET", "/inbox", fake.token()).body()).isEqualTo("mail");
        var anonymous = call("GET", "/inbox", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(challenge(anonymous)).isEqualTo("Bearer");
    }

    @Test @DisplayName("names must be usable: no blank values, scopes are RFC 6749 scope tokens")
    void validation() {
        assertThatThrownBy(() -> Auth.require()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Auth.role(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Auth.scope("two words")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Auth.scope("quote\"d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Auth.anyOf()).isInstanceOf(IllegalArgumentException.class);
    }
}
