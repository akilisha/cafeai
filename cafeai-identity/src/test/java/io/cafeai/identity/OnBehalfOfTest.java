package io.cafeai.identity;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuthCredentials.onBehalfOf: model calls as the signed-in person, at Microsoft Entra ID")
class OnBehalfOfTest {

    private static final String APP = "orders-api";   // the app's client id, and its tokens' audience
    private static final String SECRET = "s3cret";
    private static final String FOUNDRY = "https://ai.azure.com/.default";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer entra;   // behaves as Entra does for on-behalf-of
    private FakeAnthropicServer foundry;
    private CafeAI app;

    @BeforeAll
    static void startIssuer() {
        entra = FakeIssuer.start().client(APP, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        entra.close();
    }

    @BeforeEach
    void startModel() throws Exception {
        foundry = new FakeAnthropicServer();
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        foundry.close();
    }

    private void serve(AiProvider provider, BearerAuth bearer) throws Exception {
        app = CafeAI.create();
        app.ai(provider);
        app.filter(bearer);
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> ask(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ask"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private AiProvider claudeInFoundry() {
        return Anthropic.of("claude-opus-5-5").withBaseUrl(foundry.baseUrl("/anthropic"))
                .withCredentials(OAuthCredentials.onBehalfOf(entra.issuer(), APP, SECRET, FOUNDRY));
    }

    @Test @DisplayName("each caller's call reaches the model with a token for them, issued for the model's resource")
    void perPerson() throws Exception {
        serve(claudeInFoundry(), Auth.bearer(entra.issuer(), APP));
        String alice = entra.token().subject("alice").audience(APP).sign();
        assertThat(ask(alice).body()).isEqualTo("ok");
        assertThat(ask(entra.token().subject("bob").audience(APP).sign()).body()).isEqualTo("ok");

        String first = claims(foundry.calls.get(0).bearer());
        assertThat(first).contains("\"sub\":\"alice\"").contains("\"aud\":[\"https://ai.azure.com\"]")
                .contains("\"azp\":\"" + APP + "\"");
        assertThat(claims(foundry.calls.get(1).bearer())).contains("\"sub\":\"bob\"");

        int before = entra.tokenRequests();
        assertThat(ask(alice).body()).isEqualTo("ok");
        assertThat(entra.tokenRequests()).as("alice's token is reused until near its expiry").isEqualTo(before);
    }

    @Test @DisplayName("a caller's token issued for another app can't be redeemed: the model is never reached")
    void onlyTokensForThisApp() throws Exception {
        serve(claudeInFoundry(), Auth.bearer(entra.issuer(), APP, "other-api"));
        var refused = ask(entra.token().subject("carol").audience("other-api").sign());
        assertThat(refused.statusCode()).isEqualTo(500);
        assertThat(foundry.calls).isEmpty();
    }

    @Test @DisplayName("when Entra needs the person to sign in again, the call is a 401, not a 500")
    void interactionRequired() throws Exception {
        serve(claudeInFoundry(), Auth.bearer(entra.issuer(), APP));
        entra.requireInteraction("dave");
        assertThat(ask(entra.token().subject("dave").audience(APP).sign()).statusCode()).isEqualTo(401);
        assertThat(foundry.calls).isEmpty();
    }

    @Test @DisplayName("no caller, no call: refused with 401, never made with some other credential")
    void noCaller() throws Exception {
        serve(claudeInFoundry(), Auth.bearer(entra.issuer(), APP).optional());
        assertThat(ask(null).statusCode()).isEqualTo(401);
        assertThat(foundry.calls).isEmpty();
    }

    @Test @DisplayName("byIssuer: Entra's callers on-behalf-of, another issuer's by token exchange")
    void besideTokenExchange() throws Exception {
        try (var okta = FakeIssuer.start().client("partner-api", "partner-secret")) {
            serve(Anthropic.of("m").withBaseUrl(foundry.baseUrl("")).withCredentials(OAuthCredentials.byIssuer(
                            OAuthCredentials.onBehalfOf(entra.issuer(), APP, SECRET, FOUNDRY),
                            OAuthCredentials.tokenExchange(okta.issuer(), "partner-api", "partner-secret", "model-gateway"))),
                    Auth.bearer(entra.issuer(), APP).or(okta.issuer(), "partner-api"));
            assertThat(ask(entra.token().subject("erin").audience(APP).sign()).body()).isEqualTo("ok");
            assertThat(ask(okta.token().subject("pat").audience("partner-api").sign()).body()).isEqualTo("ok");

            assertThat(claims(foundry.calls.get(0).bearer())).contains("\"sub\":\"erin\"").contains("https://ai.azure.com");
            assertThat(claims(foundry.calls.get(1).bearer())).contains("\"sub\":\"pat\"").contains("model-gateway");
        }
    }
}
