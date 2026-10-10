package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Credentials;
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

@DisplayName("Anthropic-compatible endpoints: withBaseUrl, and a credential per call in the right header")
class AnthropicCredentialsTest {

    private static final String AUDIENCE = "orders-api";
    private static final String SECRET = "s3cret";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer fake;
    private FakeAnthropicServer model;
    private CafeAI app;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().client(AUDIENCE, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void startModel() throws Exception {
        model = new FakeAnthropicServer();
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

    private HttpResponse<String> get(String path, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (subject != null) request.header("Authorization", "Bearer " + fake.token().subject(subject).audience(AUDIENCE).sign());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("an API key goes as x-api-key, and nothing else: as DeepSeek's and Kimi's endpoints take it")
    void apiKeyInItsHeader() throws Exception {
        serve(Anthropic.of("m").withBaseUrl(model.baseUrl("/anthropic")).withCredentials(Credentials.staticKey("sk-test")));
        assertThat(get("/ask", null).body()).isEqualTo("ok");

        var call = model.calls.getFirst();
        assertThat(call.path()).isEqualTo("/anthropic/v1/messages");
        assertThat(call.xApiKey()).containsExactly("sk-test");
        assertThat(call.authorization()).isEmpty();
        assertThat(call.apiKey()).isEmpty();
    }

    @Test @DisplayName("an OAuth token goes as Authorization: Bearer, per caller, and the placeholder key is never sent")
    void tokenPerCaller() throws Exception {
        serve(Anthropic.of("m").withBaseUrl(model.baseUrl("/anthropic/v1"))
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), AUDIENCE, SECRET, "model-gateway")));
        assertThat(get("/ask", "alice").body()).isEqualTo("ok");
        assertThat(get("/ask", "bob").body()).isEqualTo("ok");

        assertThat(model.calls).allSatisfy(c -> assertThat(c.path()).isEqualTo("/anthropic/v1/messages"));
        assertThat(claims(model.calls.get(0).bearer())).contains("\"sub\":\"alice\"").contains("model-gateway");
        assertThat(claims(model.calls.get(1).bearer())).contains("\"sub\":\"bob\"");
    }

    @Test @DisplayName("a streamed call carries its caller's token too, though it runs on the stream's thread")
    void streamedPerCaller() throws Exception {
        serve(Anthropic.of("m").withBaseUrl(model.baseUrl(""))
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), AUDIENCE, SECRET, "model-gateway")));
        var response = get("/sse", "carol");
        assertThat(response.body()).contains("ok");

        var call = model.calls.getFirst();
        assertThat(call.streamed()).isTrue();
        assertThat(claims(call.bearer())).contains("\"sub\":\"carol\"");
    }

    @Test @DisplayName("a call that needs the caller and has none is refused, and never reaches the endpoint")
    void noCallerNoCall() throws Exception {
        serve(Anthropic.of("m").withBaseUrl(model.baseUrl(""))
                .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), AUDIENCE, SECRET, "model-gateway")));
        assertThat(get("/ask", null).statusCode()).isEqualTo(401);
        assertThat(model.calls).isEmpty();
    }
}
