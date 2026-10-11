package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.internal.Cli;
import io.helidon.json.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AzureCliCredentials} against the real Azure CLI and Azure (MILESTONE-20 phase 6). Each
 * part runs only when asked for, and is skipped otherwise:
 *
 * <ul>
 *   <li>{@code AZURE_CLI_LIVE=1}: after {@code cafeai login azure}, tokens for the Foundry and
 *       Azure OpenAI scopes, what {@code az} really answers, and how long it takes.</li>
 *   <li>{@code AZURE_CLI_SIGNED_OUT=1}: after {@code cafeai logout azure}, the sign-in-again message.</li>
 *   <li>{@code AZURE_FOUNDRY_BASE_URL} and {@code AZURE_FOUNDRY_MODEL}: Claude in Microsoft Foundry
 *       ({@code https://<resource>.services.ai.azure.com/anthropic}).</li>
 *   <li>{@code AZURE_OPENAI_BASE_URL} and {@code AZURE_OPENAI_DEPLOYMENT}: Azure OpenAI
 *       ({@code https://<resource>.openai.azure.com/openai/v1}).</li>
 * </ul>
 */
@DisplayName("Azure CLI sign-in, live")
class AzureCliCredentialsLiveTest {

    @Test @DisplayName("tokens for both scopes from the real az, kept for the second call")
    void tokens() {
        Assumptions.assumeTrue("1".equals(System.getenv("AZURE_CLI_LIVE")),
                "set AZURE_CLI_LIVE=1 after `cafeai login azure` to run against Azure");

        for (String scope : List.of(AzureCliCredentials.FOUNDRY, AzureCliCredentials.AZURE_OPENAI)) {
            var az = Cli.find("az", System::getenv).orElseThrow();
            Cli.Result raw = Cli.run(List.of(az.toString(), "account", "get-access-token", "--scope", scope,
                    "--output", "json"), Duration.ofSeconds(60));
            assertThat(raw.ok()).as("az answered: " + raw.err()).isTrue();
            var json = JsonParser.create(raw.out()).readJsonValue().asObject();
            System.out.println("[live] az for " + scope + ": fields " + json.keys()
                    + ", expires_on is a " + json.value("expires_on").map(v -> v.type().toString()).orElse("(absent)"));

            var credentials = AzureCliCredentials.scope(scope);
            long start = System.nanoTime();
            String token = credentials.token();
            long first = (System.nanoTime() - start) / 1_000_000;
            start = System.nanoTime();
            assertThat(credentials.token()).as("the second call reuses the token").isEqualTo(token);
            long second = (System.nanoTime() - start) / 1_000_000;
            System.out.println("[live]   first token() " + first + " ms (runs az), second " + second
                    + " ms; claims " + claims(token));
            assertThat(second).isLessThan(first);
        }
    }

    @Test @DisplayName("signed out: the message says to sign in again")
    void signedOut() {
        Assumptions.assumeTrue("1".equals(System.getenv("AZURE_CLI_SIGNED_OUT")),
                "set AZURE_CLI_SIGNED_OUT=1 after `cafeai logout azure`");
        assertThatThrownBy(() -> AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY).token())
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> System.out.println("[live] signed out: " + e.getMessage()))
                .hasMessageContaining("cafeai login azure");
    }

    @Test @DisplayName("Claude in Microsoft Foundry with the az sign-in")
    void foundry() {
        String base = System.getenv("AZURE_FOUNDRY_BASE_URL");
        String model = System.getenv("AZURE_FOUNDRY_MODEL");
        Assumptions.assumeTrue(base != null && model != null,
                "set AZURE_FOUNDRY_BASE_URL and AZURE_FOUNDRY_MODEL to call Claude in Foundry");
        var app = CafeAI.create();
        app.ai(Anthropic.of(model).withBaseUrl(base)
                .withCredentials(AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY)));
        String answer = app.prompt("Reply with the single word: ready").call().text();
        System.out.println("[live] foundry: " + answer);
        assertThat(answer).isNotBlank();
    }

    @Test @DisplayName("Azure OpenAI with the az sign-in")
    void azureOpenAi() {
        String base = System.getenv("AZURE_OPENAI_BASE_URL");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");
        Assumptions.assumeTrue(base != null && deployment != null,
                "set AZURE_OPENAI_BASE_URL and AZURE_OPENAI_DEPLOYMENT to call Azure OpenAI");
        var app = CafeAI.create();
        app.ai(OpenAI.of(deployment).withBaseUrl(base)
                .withCredentials(AzureCliCredentials.scope(AzureCliCredentials.AZURE_OPENAI)));
        String answer = app.prompt("Reply with the single word: ready").call().text();
        System.out.println("[live] azure openai: " + answer);
        assertThat(answer).isNotBlank();
    }

    /** The token's audience and lifetime, nothing that identifies the person. */
    private static String claims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return "(not a JWT)";
        var payload = JsonParser.create(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                .readJsonValue().asObject();
        long iat = payload.longValue("iat", 0), exp = payload.longValue("exp", 0);
        return "aud " + payload.stringValue("aud", "?") + ", lifetime " + (exp - iat) + "s";
    }
}
