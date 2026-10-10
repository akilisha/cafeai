package io.cafeai.identity;

import dev.langchain4j.agent.tool.Tool;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.identity.Identity;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Auth.mcp: the MCP endpoint as an OAuth resource server, with RFC 9728 metadata")
class McpAuthTest {

    public static class Echo {
        @Tool("Repeats the text")
        public String echo(String text) { return text; }
    }

    /** A {@code @Tool} object that needs to know who is calling. */
    public static class Me {
        @Tool("Who the caller is, as a tool object sees it")
        public String me() { return Identity.current().map(Identity::subject).orElse("anonymous"); }
    }

    /** A {@code @Tool} object that calls the model. */
    public static class Ask {
        private final CafeAI app;
        public Ask(CafeAI app) { this.app = app; }

        @Tool("Asks the model")
        public String ask() { return app.prompt("hi").call().text(); }
    }

    private FakeIssuer fake;
    private FakeModelServer model;
    private CafeAI app;
    private String base;
    private String resource;

    @BeforeAll
    void start() throws Exception {
        fake = FakeIssuer.start().client("orders-api", "orders-secret");
        model = new FakeModelServer();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://localhost:" + port;
        resource = base + "/mcp";

        app = CafeAI.create();
        // The app's routes accept tokens for the API, and for the MCP endpoint, whose route tools
        // forward the caller's token to them.
        app.filter(Auth.bearer(fake.issuer(), "orders-api", resource));
        app.get("/whoami", (req, res, next) -> res.send(req.identity().map(Identity::subject).orElse("anonymous")));
        app.ai(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), "orders-api", "orders-secret", "model-server")));
        app.mcp().tool("whoami", "Who the caller is", "GET /whoami").tools(new Echo(), new Me(), new Ask(app));
        Auth.mcp(app, fake.issuer(), resource).scope("mcp:use");

        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterAll
    void stop() {
        app.stop();
        fake.close();
        model.close();
    }

    private String token(String subject, String audience, String... scopes) {
        return fake.token().subject(subject).audience(audience).scope(scopes).sign();
    }

    /** One raw MCP request, to see the status and headers a client gets before any session. */
    private HttpResponse<String> initialize(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(resource))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String challenge(HttpResponse<?> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    @Test @DisplayName("publishes Protected Resource Metadata naming the issuer, with no token needed")
    void metadata() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(base + "/.well-known/oauth-protected-resource/mcp")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).contains("json"));
        assertThat(response.body())
                .contains("\"resource\":\"" + resource + "\"")
                .contains("\"authorization_servers\":[\"" + fake.id() + "\"]")
                .contains("\"scopes_supported\":[\"mcp:use\"]");
    }

    @Test @DisplayName("no token: 401 pointing the client at the metadata, so it can find where to sign in")
    void noToken() throws Exception {
        var response = initialize(null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response))
                .isEqualTo("Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test @DisplayName("a token for another service can't be replayed here: the audience must be the MCP endpoint")
    void audienceBound() throws Exception {
        var response = initialize(token("alice", "orders-api", "mcp:use"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(challenge(response)).contains("error=\"invalid_token\"").contains("resource_metadata=");
    }

    @Test @DisplayName("a token without the required scope: 403 insufficient_scope")
    void scopeRequired() throws Exception {
        var response = initialize(token("alice", resource));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(challenge(response)).contains("error=\"insufficient_scope\"").contains("scope=\"mcp:use\"");
    }

    @Test @DisplayName("an app serving verified callers won't start with its MCP endpoint unprotected")
    void unprotectedEndpointRefused() {
        var open = CafeAI.create();
        open.filter(Auth.bearer(fake.issuer(), "orders-api"));
        open.mcp().tools(new Echo());
        assertThatThrownBy(() -> open.listen(0, () -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Auth.mcp");
    }

    @Test @DisplayName("protecting a different path than the endpoint is caught too")
    void mismatchedPathRefused() {
        var app2 = CafeAI.create();
        app2.mcp().path("/agents").tools(new Echo());
        Auth.mcp(app2, fake.issuer(), "https://orders.example.com/mcp");
        assertThatThrownBy(() -> app2.listen(0, () -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/agents");
    }

    @Test @DisplayName("with a token for the endpoint: tools work, and a route tool's route sees the caller")
    void authorised() throws Exception {
        var client = new McpClient(resource).header("Authorization", "Bearer " + token("alice", resource, "mcp:use"))
                .connect();
        assertThat(client.listTools().toString()).contains("whoami").contains("echo");
        assertThat(McpClient.text(client.call("echo", Map.of("text", "hi")))).isEqualTo("hi");
        assertThat(McpClient.text(client.call("whoami", Map.of()))).isEqualTo("alice");
    }

    @Test @DisplayName("a @Tool object sees the verified caller in Identity.current(), each caller their own")
    void toolObjectSeesTheCaller() throws Exception {
        var alice = new McpClient(resource).header("Authorization", "Bearer " + token("alice", resource, "mcp:use"))
                .connect();
        var bob = new McpClient(resource).header("Authorization", "Bearer " + token("bob", resource, "mcp:use"))
                .connect();
        assertThat(McpClient.text(alice.call("me", Map.of()))).isEqualTo("alice");
        assertThat(McpClient.text(bob.call("me", Map.of()))).isEqualTo("bob");
        assertThat(McpClient.text(alice.call("me", Map.of()))).isEqualTo("alice");
    }

    @Test @DisplayName("a @Tool object's model call is made on the caller's behalf (token exchange)")
    void toolObjectCallsTheModelAsTheCaller() throws Exception {
        var alice = new McpClient(resource).header("Authorization", "Bearer " + token("alice", resource, "mcp:use"))
                .connect();
        int before = model.calls.size();
        assertThat(McpClient.text(alice.call("ask", Map.of()))).isEqualTo("ok");
        assertThat(model.calls).hasSize(before + 1);
        String claims = new String(java.util.Base64.getUrlDecoder().decode(model.calls.get(before).bearer().split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(claims).contains("\"sub\":\"alice\"").contains("model-server").contains("\"act\":{\"sub\":\"orders-api\"}");
    }
}
