package io.cafeai.core.ai;

import io.cafeai.core.CafeAI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each built-in provider says it can take as input. {@code app.vision()} and {@code app.audio()} check
 * this before any call, so a provider that stops declaring a capability makes the whole modality fail with
 * a message that tells the user to register the very provider they have.
 */
@DisplayName("provider capabilities")
class ProviderCapabilitiesTest {

    private static void expect(AiProvider p, boolean vision, boolean audio) {
        assertThat(p.supportsVision()).as(p.name() + "/" + p.modelId() + " vision").isEqualTo(vision);
        assertThat(p.supportsAudio()).as(p.name() + "/" + p.modelId() + " audio").isEqualTo(audio);
    }

    @Test @DisplayName("every OpenAI chat provider takes images and audio, whichever model it names")
    void openAi() {
        expect(OpenAI.of("gpt-4o"), true, true);
        expect(OpenAI.of("gpt-4o-mini"), true, true);
        expect(OpenAI.of("some-future-model").withTemperature(0.2), true, true);
    }

    @Test @DisplayName("OpenAI.whisper() is audio only")
    void whisper() {
        expect(OpenAI.whisper(), false, true);
    }

    @Test @DisplayName("the other built-in providers take images, and none but OpenAI takes audio")
    void others() {
        expect(Anthropic.of("claude-sonnet-4-5"), true, false);
        expect(Gemini.of("gemini-2.5-flash"), true, false);
        expect(Nvidia.of("moonshotai/kimi-k3"), true, false);
        expect(Ollama.vision("llava"), true, false);
        expect(Ollama.of("llama3.3"), false, false);
        expect(Jlama.of("tjake/x"), false, false);
    }

    @Test @DisplayName("settings copies keep the capabilities")
    void copiesKeepThem() {
        expect(OpenAI.of("gpt-4o").withMaxTokens(100).withTimeout(java.time.Duration.ofSeconds(5)), true, true);
    }

    @Test @DisplayName("app.audio() on a provider without audio fails before any call, naming the way out")
    void audioRefused() {
        var app = CafeAI.create();
        app.ai(Anthropic.of("claude-sonnet-4-5"));

        assertThatThrownBy(() -> app.audio("transcribe", new byte[]{1, 2, 3}, "audio/wav").call())
            .isInstanceOf(AudioRequest.AudioNotSupportedException.class)
            .hasMessageContaining("claude-sonnet-4-5").hasMessageContaining("OpenAI.whisper()");
    }

    @Test @DisplayName("app.vision() on a provider without vision fails before any call")
    void visionRefused() {
        var app = CafeAI.create();
        app.ai(Ollama.of("llama3.3"));

        assertThatThrownBy(() -> app.vision("what is this?", new byte[]{1, 2, 3}, "image/png").call())
            .isInstanceOf(VisionRequest.VisionNotSupportedException.class);
    }
}
