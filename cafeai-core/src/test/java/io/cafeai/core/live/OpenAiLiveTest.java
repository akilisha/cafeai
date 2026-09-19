package io.cafeai.core.live;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.GuardRailViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI. Needs {@code OPENAI_API_KEY}.
 *
 * <ul>
 *   <li>{@code OPENAI_LIVE_MODEL} — a small chat model that reads images (default {@code gpt-4o-mini}).</li>
 *   <li>{@code OPENAI_LIVE_MODERATION_MODEL} — default {@code omni-moderation-latest}.</li>
 * </ul>
 *
 * <p>Beyond the shared suite: speech in and out (the text-to-speech endpoint makes the audio that the
 * transcription endpoint then reads back) and the moderation guardrail.
 */
@DisplayName("OpenAI — live")
class OpenAiLiveTest extends ProviderLiveSuite {

    private final String model = env("OPENAI_LIVE_MODEL", "gpt-4o-mini");

    @Override String label() { return "OpenAI"; }

    @Override String skipReason() {
        return has("OPENAI_API_KEY") ? null : "OPENAI_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return OpenAI.of(model); }

    @Override AiProvider visionProvider() { return OpenAI.of(model); }

    @Test @DisplayName("speech round trip: synthesised audio is transcribed back to the words")
    void speechRoundTrip() {
        CafeAI app = app();
        app.ai("voice", OpenAI.tts());

        byte[] speech = app.synthesise("The quick brown fox jumps over the lazy dog.").provider("voice").call().audioBytes();
        System.out.println("[live] tts: " + speech.length + " bytes");
        assertThat(speech.length).isGreaterThan(1_000);

        String text = app.audio("Transcribe this audio exactly.", speech, "audio/mpeg").call().text();
        System.out.println("[live] transcription: " + abbreviate(text));
        assertThat(text.toLowerCase()).contains("fox");
    }

    @Test @DisplayName("the moderation guardrail blocks violent text and lets ordinary text through")
    void moderation() {
        CafeAI app = app();
        app.guard(GuardRail.moderation(OpenAI.moderation(env("OPENAI_LIVE_MODERATION_MODEL", "omni-moderation-latest"))));

        assertThat(app.prompt("Reply with one word: hello").call().text()).isNotBlank();
        assertThatThrownBy(() -> app.prompt("I am going to find my neighbour tonight and kill him with a knife.").call())
            .isInstanceOf(GuardRailViolationException.class);
    }
}
