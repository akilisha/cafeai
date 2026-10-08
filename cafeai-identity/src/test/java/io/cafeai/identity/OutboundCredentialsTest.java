package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.cache.SemanticCache;
import io.cafeai.core.identity.IdentityRequiredException;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.rag.EmbeddingProvider;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Outbound credentials: the model is reached per call, as the app or as the caller")
class OutboundCredentialsTest {

    private static final String AUDIENCE = "orders-api";
    private static final String CLIENT = "orders-api";
    private static final String SECRET = "s3cret";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer fake;
    private FakeModelServer model;
    private CafeAI app;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().client(CLIENT, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void startModel() throws Exception {
        model = new FakeModelServer();
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        model.close();
    }

    private void serve(AiProvider provider) throws Exception {
        app = CafeAI.create();
        app.ai(provider);
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("hi")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String tokenFor(String subject) {
        return fake.token().subject(subject).audience(AUDIENCE).sign();
    }

    /** The claims of a JWT, as raw JSON text: enough to check sub, aud and act. */
    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("a static key replaces the environment key, sent as exactly one Authorization header")
    void staticKey() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(Credentials.staticKey("k-123")));
        assertThat(get("/ask", null).body()).isEqualTo("ok");
        assertThat(model.calls).hasSize(1);
        assertThat(model.calls.get(0).bearer()).isEqualTo("k-123");
    }

    @Test @DisplayName("token exchange: each caller's call carries a token for that caller, issued for the model")
    void tokenExchangePerCaller() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server")));

        assertThat(get("/ask", tokenFor("alice")).body()).isEqualTo("ok");
        assertThat(get("/ask", tokenFor("bob")).body()).isEqualTo("ok");

        String alice = claims(model.calls.get(0).bearer());
        assertThat(alice).contains("\"sub\":\"alice\"").contains("model-server").contains("\"act\":{\"sub\":\"orders-api\"}");
        assertThat(claims(model.calls.get(1).bearer())).contains("\"sub\":\"bob\"");
    }

    @Test @DisplayName("a streamed call's credential belongs to its caller too, though it is sent from the stream's thread")
    void tokenExchangeOnAStream() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server")));

        assertThat(get("/sse", tokenFor("carol")).body()).contains("ok");
        assertThat(model.calls).hasSize(1);
        assertThat(model.calls.get(0).streamed()).isTrue();
        assertThat(claims(model.calls.get(0).bearer())).contains("\"sub\":\"carol\"");
    }

    @Test @DisplayName("an exchanged token is reused for the same caller's token until it nears expiry")
    void exchangeIsCached() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server")));
        String dave = tokenFor("dave");
        int before = fake.tokenRequests();

        get("/ask", dave);
        get("/ask", dave);
        get("/ask", dave);

        assertThat(fake.tokenRequests() - before).isEqualTo(1);
        assertThat(model.calls.get(2).bearer()).isEqualTo(model.calls.get(0).bearer());
    }

    @Test @DisplayName("no caller, no call: an anonymous request is refused with 401 and the model is never reached")
    void failsClosedWithoutACaller() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server")));

        var response = get("/ask", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(model.calls).isEmpty();
    }

    @Test @DisplayName("client credentials: every call is made as the app, with one token fetched and reused")
    void clientCredentials() throws Exception {
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl())
                .withCredentials(OAuthCredentials.clientCredentials(fake.issuer(), CLIENT, SECRET).audience("model-server")));
        int before = fake.tokenRequests();

        get("/ask", tokenFor("erin"));
        get("/ask", null);

        assertThat(fake.tokenRequests() - before).isEqualTo(1);
        assertThat(claims(model.calls.get(0).bearer())).contains("\"sub\":\"orders-api\"").contains("model-server");
        assertThat(model.calls.get(1).bearer()).isEqualTo(model.calls.get(0).bearer());
    }

    @Test @DisplayName("one shared client per provider, whoever is calling")
    void oneSharedClient() {
        AiProvider provider = OpenAI.of("m").withBaseUrl("http://127.0.0.1:1/v1")
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server"));
        assertThat(LangchainBridge.chatModel(provider)).isSameAs(LangchainBridge.chatModel(provider));
    }

    @Test @DisplayName("a rate limit from the model endpoint reaches the caller as a 429")
    void rateLimitPassedThrough() throws Exception {
        model.rateLimited = true;
        serve(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(Credentials.staticKey("k")));
        assertThat(get("/ask", null).statusCode()).isEqualTo(429);
    }

    @Test @DisplayName("an app that serves verified callers refuses conversation memory with no request in scope")
    void memoryFailsClosedOffRequest() {
        Auth.bearer(fake.issuer(), AUDIENCE);   // creating identity middleware turns identity mode on
        var offline = CafeAI.create();
        offline.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(Credentials.staticKey("k")));
        offline.memory(MemoryStrategy.inMemory());

        // This test thread serves no request, as a thread the request was not carried to.
        assertThatThrownBy(() -> offline.prompt("hi").session("s-1").call())
                .isInstanceOf(IdentityRequiredException.class).hasMessageContaining("RequestScope");
        assertThat(offline.prompt("hi").call().text()).isEqualTo("ok");   // no conversation, no caller needed
    }

    /** Same meaning for every text: a repeated question is a certain cache hit. */
    private static final EmbeddingProvider CONSTANT = new EmbeddingProvider() {
        @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
        @Override public int dimensions()           { return 2; }
        @Override public String modelId()           { return "constant"; }
    };

    private void serveCached(Credentials credentials) throws Exception {
        app = CafeAI.create();
        app.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(credentials));
        app.cache(SemanticCache.inMemory(CONSTANT).build());
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE));
        app.get("/ask", (req, res, next) -> res.send(app.prompt("same question").call().text()));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test @DisplayName("with the caller's own credential, every call reaches the endpoint: no answer is served from another caller's")
    void perCallerCredentialsBypassTheCache() throws Exception {
        serveCached(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server"));

        get("/ask", tokenFor("frank"));
        get("/ask", tokenFor("grace"));

        assertThat(model.calls).hasSize(2);
        assertThat(claims(model.calls.get(1).bearer())).contains("\"sub\":\"grace\"");
    }

    @Test @DisplayName("control: with the app's own credential, the repeated question is answered from the cache")
    void appCredentialsStillCache() throws Exception {
        serveCached(OAuthCredentials.clientCredentials(fake.issuer(), CLIENT, SECRET));

        get("/ask", tokenFor("frank"));
        get("/ask", tokenFor("grace"));

        assertThat(model.calls).hasSize(1);
    }

    @Test @DisplayName("token exchange needs an issuer that publishes a token endpoint")
    void needsATokenEndpoint() {
        assertThatThrownBy(() -> OAuthCredentials.tokenExchange(
                Issuer.of(fake.id(), fake.id() + "/jwks"), CLIENT, SECRET, "model-server"))
                .isInstanceOf(IdentityException.class).hasMessageContaining("token_endpoint");
    }
}
