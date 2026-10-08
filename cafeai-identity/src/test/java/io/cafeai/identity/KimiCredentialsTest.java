package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.OpenAI;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-call credential against a real third-party endpoint: Kimi's OpenAI-compatible API,
 * which accepts its credential only as {@code Authorization: Bearer} (design section 12). Needs a
 * Kimi platform API key, so it runs only when {@code KIMI_API_KEY} and {@code KIMI_MODEL} are set
 * ({@code KIMI_BASE_URL} defaults to {@code https://api.moonshot.ai/v1}); skipped otherwise.
 */
@DisplayName("Kimi: the per-call credential against a real OpenAI-compatible endpoint")
class KimiCredentialsTest {

    @Test @DisplayName("a chat call with the key given per call, no OPENAI_API_KEY involved")
    void chat() {
        String key = System.getenv("KIMI_API_KEY");
        String model = System.getenv("KIMI_MODEL");
        Assumptions.assumeTrue(key != null && !key.isBlank() && model != null && !model.isBlank(),
                "set KIMI_API_KEY and KIMI_MODEL to run against Kimi");
        String baseUrl = System.getenv().getOrDefault("KIMI_BASE_URL", "https://api.moonshot.ai/v1");

        var app = CafeAI.create();
        app.ai(OpenAI.of(model).withBaseUrl(baseUrl).withCredentials(Credentials.staticKey(key)));
        String answer = app.prompt("Reply with the single word: ready").call().text();

        assertThat(answer).isNotBlank();
    }
}
