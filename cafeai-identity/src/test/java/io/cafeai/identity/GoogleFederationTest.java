package io.cafeai.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GoogleFederation + Anthropic.onVertex: Claude on Vertex AI with no service-account key")
class GoogleFederationTest {

    private static final String AUDIENCE = "orders-api";
    private static final String SECRET = "s3cret";
    private static final String WORKLOAD = "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/apps/providers/acme";
    private static final String WORKFORCE = "//iam.googleapis.com/locations/global/workforcePools/staff/providers/acme";
    private static final String SA = "claude-caller@my-project.iam.gserviceaccount.com";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static FakeIssuer company;
    private FakeAnthropicServer vertex;
    private FakeGoogle google;
    private CafeAI app;

    /** Google's STS and IAM Credentials APIs, as they answer. */
    static final class FakeGoogle implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> exchanges = new CopyOnWriteArrayList<>();
        final List<String> impersonations = new CopyOnWriteArrayList<>();   // the bearer each came with
        volatile boolean refuse;

        FakeGoogle() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/token", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                exchanges.add(request);
                if (refuse) {
                    send(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"The audience in the token does not match\"}");
                    return;
                }
                send(exchange, 200, "{\"access_token\":\"federated-" + exchanges.size() + "\",\"issued_token_type\":"
                        + "\"urn:ietf:params:oauth:token-type:access_token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
            });
            server.createContext("/v1/projects/-/serviceAccounts/", exchange -> {
                impersonations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                send(exchange, 200, "{\"accessToken\":\"sa-token-" + impersonations.size() + "\",\"expireTime\":\""
                        + Instant.now().plusSeconds(3600) + "\"}");
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override public void close() { server.stop(0); }
    }

    @BeforeAll
    static void startIssuer() {
        company = FakeIssuer.start().client(AUDIENCE, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        company.close();
    }

    @BeforeEach
    void start() throws Exception {
        vertex = new FakeAnthropicServer();
        google = new FakeGoogle();
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        vertex.close();
        google.close();
    }

    private AiProvider claudeOnVertex(GoogleFederation federation) {
        return Anthropic.onVertex("claude-opus-5-5", "my-project", "global").withBaseUrl(vertex.baseUrl(""))
                .withCredentials(federation.endpoints(google.base() + "/v1/token", google.base()));
    }

    private void serve(AiProvider provider) throws Exception {
        app = CafeAI.create();
        app.ai(provider);
        app.filter(Auth.bearer(company.issuer(), AUDIENCE).optional());
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("hi")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> get(String path, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (subject != null) request.header("Authorization", "Bearer " + company.token().subject(subject).audience(AUDIENCE).sign());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String subjectOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)
                .replaceAll("(?s).*\"sub\":\"([^\"]+)\".*", "$1");
    }

    @Test @DisplayName("as the app: STS, then the service account; Vertex gets its form of the request and the account's token")
    void asTheApp() throws Exception {
        serve(claudeOnVertex(GoogleFederation.workload(WORKLOAD,
                IdentityToken.clientCredentials(company.issuer(), AUDIENCE, SECRET)).serviceAccount(SA)));
        assertThat(get("/ask", null).body()).isEqualTo("ok");
        assertThat(get("/ask", null).body()).isEqualTo("ok");

        assertThat(google.exchanges).as("one exchange for both calls").singleElement().satisfies(x -> {
            assertThat(x.get("grantType").asText()).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
            assertThat(x.get("audience").asText()).isEqualTo(WORKLOAD);
            assertThat(x.get("scope").asText()).isEqualTo("https://www.googleapis.com/auth/cloud-platform");
            assertThat(x.get("subjectTokenType").asText()).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
            assertThat(subjectOf(x.get("subjectToken").asText())).isEqualTo(AUDIENCE);
        });
        assertThat(google.impersonations).containsExactly("Bearer federated-1");

        var call = vertex.calls.getFirst();
        assertThat(call.path()).isEqualTo("/v1/projects/my-project/locations/global/publishers/anthropic/models/claude-opus-5-5:rawPredict");
        assertThat(call.bearer()).isEqualTo("sa-token-1");
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.get("anthropic_version").asText()).isEqualTo("vertex-2023-10-16");
        assertThat(body.has("model")).as("the model goes in the URL").isFalse();
        assertThat(body.get("messages")).isNotNull();
    }

    @Test @DisplayName("a streamed call goes to :streamRawPredict")
    void streamed() throws Exception {
        serve(claudeOnVertex(GoogleFederation.workload(WORKLOAD, IdentityToken.clientCredentials(company.issuer(), AUDIENCE, SECRET))));
        assertThat(get("/sse", null).body()).contains("ok");
        var call = vertex.calls.getFirst();
        assertThat(call.path()).endsWith("/models/claude-opus-5-5:streamRawPredict");
        assertThat(call.bearer()).isEqualTo("federated-1");
    }

    @Test @DisplayName("as the caller (workforce): each person's own token is exchanged, and their call carries the result")
    void perPerson() throws Exception {
        serve(claudeOnVertex(GoogleFederation.workforce(WORKFORCE)));
        assertThat(get("/ask", "alice").body()).isEqualTo("ok");
        assertThat(get("/ask", "bob").body()).isEqualTo("ok");

        assertThat(google.exchanges).extracting(x -> subjectOf(x.get("subjectToken").asText())).containsExactly("alice", "bob");
        assertThat(google.exchanges).allSatisfy(x -> assertThat(x.get("audience").asText()).isEqualTo(WORKFORCE));
        assertThat(vertex.calls).extracting(FakeAnthropicServer.Call::bearer).containsExactly("federated-1", "federated-2");
    }

    @Test @DisplayName("as the caller, with no caller: 401, and neither Google nor Vertex is reached")
    void noCaller() throws Exception {
        serve(claudeOnVertex(GoogleFederation.workforce(WORKFORCE)));
        assertThat(get("/ask", null).statusCode()).isEqualTo(401);
        assertThat(google.exchanges).isEmpty();
        assertThat(vertex.calls).isEmpty();
    }

    @Test @DisplayName("Google refusing the exchange fails the call before Vertex is reached")
    void refused() throws Exception {
        google.refuse = true;
        serve(claudeOnVertex(GoogleFederation.workload(WORKLOAD, IdentityToken.clientCredentials(company.issuer(), AUDIENCE, SECRET))));
        assertThat(get("/ask", null).statusCode()).isEqualTo(500);
        assertThat(vertex.calls).isEmpty();
    }

    @Test @DisplayName("the provider must be named in full")
    void providerName() {
        assertThatThrownBy(() -> GoogleFederation.workforce("acme")).isInstanceOf(IllegalArgumentException.class);
    }
}
