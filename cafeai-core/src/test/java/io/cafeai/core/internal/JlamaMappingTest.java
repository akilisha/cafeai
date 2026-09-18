package io.cafeai.core.internal;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Jlama;
import io.cafeai.core.ai.Ollama;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a Jlama provider is mapped onto LangChain4j's Jlama builders. Building a Jlama model loads it (and
 * downloads it on first use), so {@code ProviderMappingTest} cannot cover it the way it covers the network
 * providers; the mapping is checked here, and a real model is exercised by {@code JlamaLiveTest}.
 */
@DisplayName("Jlama mapping")
class JlamaMappingTest {

    private static LangchainBridge.JlamaSettings settings(AiProvider p) {
        return LangchainBridge.JlamaSettings.of(p);
    }

    @Test @DisplayName("a bare provider asks for the model and nothing else")
    void bare() {
        var s = settings(Jlama.of("tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4"));

        assertThat(s.modelName()).isEqualTo("tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4");
        assertThat(s.modelCachePath()).as("Jlama's own default directory").isNull();
        assertThat(s.temperature()).isNull();
        assertThat(s.maxTokens()).isNull();
    }

    @Test @DisplayName("withTemperature narrows the Double to the Float Jlama takes")
    void temperature() {
        assertThat(settings(Jlama.of("m").withTemperature(0.3)).temperature()).isEqualTo(0.3f);
        assertThat(settings(Jlama.of("m").withTemperature(1.0)).temperature()).isEqualTo(1.0f);
    }

    @Test @DisplayName("a temperature of 0 is a setting, not 'unset'")
    void zeroTemperature() {
        assertThat(settings(Jlama.of("m").withTemperature(0.0)).temperature()).isEqualTo(0.0f);
    }

    @Test @DisplayName("withMaxTokens is passed through as given")
    void maxTokens() {
        assertThat(settings(Jlama.of("m").withMaxTokens(64)).maxTokens()).isEqualTo(64);
    }

    @Test @DisplayName("cachedIn(dir) becomes the model cache path; a bare provider has none")
    void cache() {
        var s = settings(Jlama.cachedIn("/opt/models").model("tjake/gemma-2-2b-it-JQ4"));

        assertThat(s.modelCachePath()).isEqualTo(Path.of("/opt/models"));
        assertThat(s.modelName()).isEqualTo("tjake/gemma-2-2b-it-JQ4");
    }

    @Test @DisplayName("settings survive chaining, in any order, together with the cache path")
    void chained() {
        var a = settings(Jlama.cachedIn("/m").model("x/y").withTemperature(0.5).withMaxTokens(16));
        var b = settings(Jlama.cachedIn("/m").model("x/y").withMaxTokens(16).withTemperature(0.5));

        assertThat(a).isEqualTo(b);
        assertThat(a.temperature()).isEqualTo(0.5f);
        assertThat(a.maxTokens()).isEqualTo(16);
        assertThat(a.modelCachePath()).isEqualTo(Path.of("/m"));
    }

    @Test @DisplayName("a provider that is not Jlama has no Jlama cache path")
    void otherProvider() {
        var s = settings(Ollama.of("llama3.3").withTemperature(0.2));

        assertThat(s.modelCachePath()).isNull();
        assertThat(s.temperature()).isEqualTo(0.2f);
    }

    @Test @DisplayName("providers with different settings are different providers, so their models are cached apart")
    void distinctProviders() {
        assertThat(Jlama.of("m").withTemperature(0.1)).isNotEqualTo(Jlama.of("m").withTemperature(0.9));
        assertThat(Jlama.of("m")).isNotEqualTo(Jlama.cachedIn("/x").model("m"));
        assertThat(Jlama.of("m").withMaxTokens(8)).isEqualTo(Jlama.of("m").withMaxTokens(8));
    }
}
