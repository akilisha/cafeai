package io.cafeai.core.internal;

import dev.langchain4j.model.chat.ChatModel;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Gemini;
import io.cafeai.core.ai.Jlama;
import io.cafeai.core.ai.ModelRouter;
import io.cafeai.core.ai.Nvidia;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.ai.OpenAI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code withTemperature} / {@code withMaxTokens} on {@link AiProvider}: every
 * built-in provider carries them, and the bridge applies them. Lives in
 * {@code internal} to reach the package-private {@code modelFor}.
 */
class ProviderOptionsTest {

    private static final List<AiProvider> BUILT_INS = List.of(
        OpenAI.of("gpt-4o"),
        Anthropic.of("claude-sonnet-4-5"),
        Gemini.of("gemini-2.5-flash"),
        Ollama.of("llama3.3"),
        Ollama.vision("llava"),
        Ollama.at("http://gpu:11434").model("mistral"),
        Jlama.of("tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4"),
        Nvidia.of("moonshotai/kimi-k3"));

    @Test
    @DisplayName("every built-in provider supports both knobs, defaults to unset, and copies")
    void builtIns_supportKnobsImmutably() {
        for (AiProvider base : BUILT_INS) {
            AiProvider tuned = base.withTemperature(0.2).withMaxTokens(512);

            assertThat(base.temperature()).as(base.name() + " original temperature").isNull();
            assertThat(base.maxTokens()).as(base.name() + " original maxTokens").isNull();
            assertThat(tuned.temperature()).as(base.name() + " temperature").isEqualTo(0.2);
            assertThat(tuned.maxTokens()).as(base.name() + " maxTokens").isEqualTo(512);
            assertThat(tuned.modelId()).as(base.name() + " modelId").isEqualTo(base.modelId());
            assertThat(tuned.name()).as(base.name() + " name").isEqualTo(base.name());
            assertThat(tuned.supportsVision()).as(base.name() + " vision").isEqualTo(base.supportsVision());
        }
    }

    @Test
    @DisplayName("every network provider supports withTimeout and copies; unset means 'use the config'")
    void networkProviders_supportTimeout() {
        var limit = Duration.ofMinutes(10);
        for (AiProvider base : BUILT_INS) {
            if (base.name().equals("jlama")) continue;   // in-process, covered below

            AiProvider tuned = base.withTimeout(limit);

            assertThat(base.timeout()).as(base.name() + " original timeout").isNull();
            assertThat(tuned.timeout()).as(base.name() + " timeout").isEqualTo(limit);
            assertThat(tuned.modelId()).as(base.name() + " modelId").isEqualTo(base.modelId());
        }
    }

    @Test
    @DisplayName("Jlama refuses withTimeout with a reason — it has no network call to time out")
    void jlama_refusesTimeout() {
        assertThatThrownBy(() -> Jlama.of("tjake/x").withTimeout(Duration.ofSeconds(5)))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("in-process");
    }

    @Test
    @DisplayName("the knobs are independent — setting one keeps the others")
    void knobsAreIndependent() {
        AiProvider p = Anthropic.of("claude-sonnet-4-5")
            .withTemperature(0.0).withTimeout(Duration.ofMinutes(3));
        assertThat(p.withMaxTokens(100).temperature()).isEqualTo(0.0);
        assertThat(p.withMaxTokens(100).timeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(p.withMaxTokens(100).maxTokens()).isEqualTo(100);
        assertThat(p.withTemperature(0.5).timeout()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("Nvidia chains withTimeout with its own withReasoningEffort in any order")
    void nvidia_timeoutChains() {
        var p = Nvidia.of("moonshotai/kimi-k3")
            .withTimeout(Duration.ofMinutes(8)).withTemperature(1.0).withReasoningEffort("max");

        assertThat(p.timeout()).isEqualTo(Duration.ofMinutes(8));
        assertThat(p.temperature()).isEqualTo(1.0);
        assertThat(p.reasoningEffort()).isEqualTo("max");
    }

    @Test
    @DisplayName("a provider that doesn't implement the knobs fails loudly, not silently")
    void unsupportedProvider_throws() {
        AiProvider custom = new AiProvider() {
            public String name()         { return "custom"; }
            public String modelId()      { return "m"; }
            public ProviderType type()   { return ProviderType.CUSTOM; }
        };

        assertThatThrownBy(() -> custom.withTemperature(0.5))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("custom");
        assertThatThrownBy(() -> custom.withMaxTokens(10))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> custom.withTimeout(Duration.ofSeconds(5)))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(custom.temperature()).isNull();
        assertThat(custom.maxTokens()).isNull();
        assertThat(custom.timeout()).isNull();
    }

    @Test
    @DisplayName("ModelRouter does not take the knobs — they belong on the models it routes between")
    void modelRouter_throws() {
        var router = ModelRouter.smart().simple(Ollama.of("a")).complex(Ollama.of("b"));
        assertThatThrownBy(() -> router.withTemperature(0.5))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> router.withTimeout(Duration.ofSeconds(5)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the bridge applies temperature and max tokens to the underlying model")
    void bridge_appliesKnobs() {
        ChatModel model = LangchainBridge.INSTANCE.modelFor(
            Ollama.of("bridge-knobs").withTemperature(0.2).withMaxTokens(256));

        assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
        assertThat(model.defaultRequestParameters().maxOutputTokens()).isEqualTo(256);
    }

    @Test
    @DisplayName("the bridge leaves unset knobs unset")
    void bridge_unsetKnobsStayUnset() {
        ChatModel model = LangchainBridge.INSTANCE.modelFor(Ollama.of("bridge-unset"));

        assertThat(model.defaultRequestParameters().temperature()).isNull();
        assertThat(model.defaultRequestParameters().maxOutputTokens()).isNull();
    }

    @Test
    @DisplayName("providers differing only by a knob get distinct models; equal providers share one")
    void bridge_cacheDistinguishesKnobs() {
        AiProvider cold = Ollama.of("bridge-cache").withTemperature(0.0);
        AiProvider hot  = Ollama.of("bridge-cache").withTemperature(1.0);

        assertThat(LangchainBridge.INSTANCE.modelFor(cold))
            .isNotSameAs(LangchainBridge.INSTANCE.modelFor(hot));
        assertThat(LangchainBridge.INSTANCE.modelFor(cold))
            .isSameAs(LangchainBridge.INSTANCE.modelFor(Ollama.of("bridge-cache").withTemperature(0.0)));
        assertThat(LangchainBridge.INSTANCE.modelFor(hot).defaultRequestParameters().temperature())
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("providers differing only by timeout get distinct models")
    void bridge_cacheDistinguishesTimeout() {
        var quick = LangchainBridge.INSTANCE.modelFor(
            Ollama.of("bridge-timeout").withTimeout(Duration.ofSeconds(5)));
        var slow = LangchainBridge.INSTANCE.modelFor(
            Ollama.of("bridge-timeout").withTimeout(Duration.ofMinutes(10)));

        assertThat(quick).isNotSameAs(slow);
    }

    @Test
    @DisplayName("Ollama instances on different base URLs no longer share a cached model")
    void bridge_cacheDistinguishesBaseUrl() {
        var a = LangchainBridge.INSTANCE.modelFor(Ollama.at("http://host-a:11434").model("same"));
        var b = LangchainBridge.INSTANCE.modelFor(Ollama.at("http://host-b:11434").model("same"));

        assertThat(a).isNotSameAs(b);
    }
}
