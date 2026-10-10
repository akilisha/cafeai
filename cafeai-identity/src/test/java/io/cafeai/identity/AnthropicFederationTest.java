package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AnthropicFederation: the Claude API with no API key (Workload Identity Federation)")
class AnthropicFederationTest {

    private static final String APP = "orders-api";
    private static final String SECRET = "s3cret";

    private static FakeIssuer company;   // the organisation's own identity provider
    private FakeAnthropicServer anthropic;

    @BeforeAll
    static void startIssuer() {
        company = FakeIssuer.start().client(APP, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        company.close();
    }

    @BeforeEach
    void startAnthropic() throws Exception {
        anthropic = new FakeAnthropicServer();
    }

    @AfterEach
    void stopAnthropic() {
        anthropic.close();
    }

    private AnthropicFederation federation(IdentityToken identity) {
        return AnthropicFederation.rule("fdrl_orders").organization("00000000-0000-0000-0000-000000000001")
                .serviceAccount("svac_orders").workspace("wrkspc_orders")
                .identityToken(identity).baseUrl(anthropic.baseUrl(""));
    }

    private CafeAI app(AnthropicFederation federation) {
        var app = CafeAI.create();
        app.ai(Anthropic.of("claude-opus-5-5").withBaseUrl(anthropic.baseUrl("")).withCredentials(federation));
        return app;
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("the app's token from its issuer is exchanged, and the call carries Anthropic's short-lived token")
    void exchangesThenCalls() {
        var app = app(federation(IdentityToken.clientCredentials(company.issuer(), APP, SECRET).audience("anthropic")));
        assertThat(app.prompt("hi").call().text()).isEqualTo("ok");

        assertThat(anthropic.exchanges).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .containsEntry("federation_rule_id", "fdrl_orders")
                    .containsEntry("organization_id", "00000000-0000-0000-0000-000000000001")
                    .containsEntry("service_account_id", "svac_orders")
                    .containsEntry("workspace_id", "wrkspc_orders");
            assertThat(claims((String) request.get("assertion"))).contains("\"sub\":\"" + APP + "\"")
                    .contains("\"iss\":\"" + company.id() + "\"");
        });
        var call = anthropic.calls.stream().filter(c -> c.path().equals("/v1/messages")).findFirst().orElseThrow();
        assertThat(call.bearer()).isEqualTo(anthropic.minted.getFirst());
    }

    @Test @DisplayName("Anthropic's token is reused until it nears expiry: one exchange for many calls")
    void cached() {
        var app = app(federation(IdentityToken.clientCredentials(company.issuer(), APP, SECRET)));
        for (int i = 0; i < 3; i++) app.prompt("hi").call();
        assertThat(anthropic.exchanges).hasSize(1);
    }

    @Test @DisplayName("each renewal presents a new identity token, so Anthropic's single-use jti check passes")
    void renewsWithAFreshAssertion() {
        anthropic.expiresIn = 100;   // inside the renewal window: every call renews
        var app = app(federation(IdentityToken.clientCredentials(company.issuer(), APP, SECRET)));
        app.prompt("one").call();
        app.prompt("two").call();

        assertThat(anthropic.exchanges).hasSize(2);
        assertThat(anthropic.exchanges.get(0).get("assertion")).isNotEqualTo(anthropic.exchanges.get(1).get("assertion"));
        assertThat(anthropic.minted).hasSize(2);
    }

    @Test @DisplayName("a token file is read again at every exchange, so a rotated token is picked up")
    void tokenFileReread(@TempDir Path dir) throws Exception {
        anthropic.expiresIn = 100;
        Path file = dir.resolve("token");
        Files.writeString(file, company.token().subject("system:serviceaccount:prod:orders").sign());
        var app = app(federation(IdentityToken.file(file)));
        app.prompt("one").call();
        String rotated = company.token().subject("system:serviceaccount:prod:orders").sign();
        Files.writeString(file, rotated + "\n");
        app.prompt("two").call();

        assertThat(anthropic.exchanges.get(1).get("assertion")).isEqualTo(rotated);
    }

    @Test @DisplayName("a refused exchange fails the call before any request reaches the model")
    void refused() {
        anthropic.refuseExchanges = true;
        var app = app(federation(IdentityToken.clientCredentials(company.issuer(), APP, SECRET)));
        assertThatThrownBy(() -> app.prompt("hi").call()).isInstanceOf(IdentityException.class)
                .hasMessageContaining("History in the Claude Console");
        assertThat(anthropic.calls.stream().filter(c -> c.path().equals("/v1/messages"))).isEmpty();
    }

    @Test @DisplayName("ids are checked by their tags, and a federation missing a part says which")
    void validated() {
        assertThatThrownBy(() -> AnthropicFederation.rule("rule-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnthropicFederation.rule("fdrl_x").serviceAccount("sa-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnthropicFederation.rule("fdrl_x").token())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("organization");
    }
}
