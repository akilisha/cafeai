package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Auth.bearer(...).resourceMetadata: an API tells refused clients where to sign in (RFC 9728)")
class ResourceMetadataTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private FakeIssuer fake;
    private CafeAI root;      // resource = the site itself; bearer optional, so require/signedIn answer
    private CafeAI api;       // resource = a path under the site; bearer required
    private String rootBase;
    private String apiBase;

    @BeforeAll
    void start() throws Exception {
        fake = FakeIssuer.start();

        int rootPort = freePort();
        rootBase = "http://localhost:" + rootPort;
        root = serve(rootPort, app -> {
            app.filter(Auth.bearer(fake.issuer(), "orders-api").optional()
                    .resourceMetadata(app, rootBase, "orders:read"));
            app.get("/orders", Auth.signedIn(), (req, res, next) -> res.send("orders"));
            app.get("/ledger", Auth.require(Auth.scope("ledger:read")), (req, res, next) -> res.send("ledger"));
        });

        int apiPort = freePort();
        apiBase = "http://localhost:" + apiPort;
        api = serve(apiPort, app -> {
            app.filter(Auth.bearer(fake.issuer(), "orders-api").resourceMetadata(app, apiBase + "/api"));
            app.get("/api/orders", (req, res, next) -> res.send("orders"));
        });
    }

    @AfterAll
    void stop() {
        root.stop();
        api.stop();
        fake.close();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static CafeAI serve(int port, Consumer<CafeAI> configure) throws Exception {
        var app = CafeAI.create();
        configure.accept(app);
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        return app;
    }

    private static HttpResponse<String> get(String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String challenge(HttpResponse<?> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    @Test @DisplayName("the metadata is served with no token: the resource, its issuer, its scopes")
    void published() throws Exception {
        var response = get(rootBase + "/.well-known/oauth-protected-resource", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        assertThat(response.body())
                .contains("\"resource\":\"" + rootBase + "\"")
                .contains("\"authorization_servers\":[\"" + fake.id() + "\"]")
                .contains("\"scopes_supported\":[\"orders:read\"]");
    }

    @Test @DisplayName("a resource with a path has its metadata at the well-known path plus that path")
    void pathResource() throws Exception {
        var response = get(apiBase + "/.well-known/oauth-protected-resource/api", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"resource\":\"" + apiBase + "/api\"").doesNotContain("scopes_supported");
    }

    @Test @DisplayName("no token: the 401 names the metadata")
    void noToken() throws Exception {
        var response = get(apiBase + "/api/orders", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response))
                .isEqualTo("Bearer resource_metadata=\"" + apiBase + "/.well-known/oauth-protected-resource/api\"");
    }

    @Test @DisplayName("an invalid token: the 401 says why, and names the metadata")
    void invalidToken() throws Exception {
        String forged = fake.token().audience("orders-api").signedWithUnknownKey().sign();
        var response = get(rootBase + "/orders", forged);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response)).startsWith("Bearer error=\"invalid_token\"")
                .endsWith("resource_metadata=\"" + rootBase + "/.well-known/oauth-protected-resource\"");
    }

    @Test @DisplayName("Auth.signedIn's 401 and Auth.require's 403 name it too")
    void furtherDown() throws Exception {
        var anonymous = get(rootBase + "/orders", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(challenge(anonymous))
                .isEqualTo("Bearer resource_metadata=\"" + rootBase + "/.well-known/oauth-protected-resource\"");

        String noLedger = fake.token().subject("bob").audience("orders-api").scope("orders:read").sign();
        var refused = get(rootBase + "/ledger", noLedger);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(challenge(refused)).isEqualTo("Bearer error=\"insufficient_scope\", scope=\"ledger:read\", "
                + "resource_metadata=\"" + rootBase + "/.well-known/oauth-protected-resource\"");

        String withLedger = fake.token().subject("alice").audience("orders-api").scope("ledger:read").sign();
        assertThat(get(rootBase + "/ledger", withLedger).body()).isEqualTo("ledger");
    }

    @Test @DisplayName("the resource must be an absolute http(s) URL with no query or fragment")
    void resourceValidated() {
        var app = CafeAI.create();
        var bearer = Auth.bearer(fake.issuer(), "orders-api");
        assertThatThrownBy(() -> bearer.resourceMetadata(app, "/orders")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bearer.resourceMetadata(app, "https://orders.example.com?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bearer.resourceMetadata(app, "ftp://orders.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
