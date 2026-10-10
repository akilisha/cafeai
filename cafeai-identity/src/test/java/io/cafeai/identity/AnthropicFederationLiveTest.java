package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workload Identity Federation against the real Claude API: a Claude Console organisation with a
 * federation issuer, service account and rule set up (Settings, Workload identity), and an
 * identity token the rule accepts. Runs only when these are set, and is skipped otherwise:
 * {@code ANTHROPIC_FEDERATION_RULE_ID}, {@code ANTHROPIC_ORGANIZATION_ID},
 * {@code ANTHROPIC_SERVICE_ACCOUNT_ID}, {@code ANTHROPIC_IDENTITY_TOKEN_FILE} (or
 * {@code ANTHROPIC_IDENTITY_TOKEN}), {@code ANTHROPIC_WORKSPACE_ID} if the rule spans several
 * workspaces, and {@code ANTHROPIC_LIVE_MODEL} (a model id the workspace may use).
 *
 * <p>An issuer that isn't on the public internet (a Keycloak on localhost) works with its keys
 * uploaded to the Console ({@code inline}); its tokens' {@code iss} is then compared as text.
 */
@DisplayName("Anthropic Workload Identity Federation, live")
class AnthropicFederationLiveTest {

    @Test @DisplayName("a call to the real Claude API with no API key, only a federated token")
    void call() {
        String model = System.getenv("ANTHROPIC_LIVE_MODEL");
        Assumptions.assumeTrue(model != null && !model.isBlank()
                        && System.getenv("ANTHROPIC_FEDERATION_RULE_ID") != null,
                "set the ANTHROPIC_* federation variables and ANTHROPIC_LIVE_MODEL to run against Anthropic");

        var app = CafeAI.create();
        app.ai(Anthropic.of(model).withCredentials(AnthropicFederation.fromEnv()));
        assertThat(app.prompt("Reply with the single word: ready").call().text()).isNotBlank();
    }
}
