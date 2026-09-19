package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Nvidia;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live smoke test against NVIDIA's hosted API catalog: proves the whole path — CafeAI provider,
 * the LangChain4j bridge, the wire — works with a real key, after the LangChain4j upgrade.
 *
 * <p>Run with {@code ./gradlew :cafeai-core:liveTest}. It skips itself when {@code NVIDIA_API_KEY}
 * is absent, and is excluded from the normal {@code test} task.
 *
 * <p>Model ids are provider data and rot, so they come from the environment:
 * <ul>
 *   <li>{@code NVIDIA_LIVE_MODEL} — a fast text model (default {@value #DEFAULT_MODEL}).</li>
 *   <li>{@code NVIDIA_LIVE_REASONING_MODEL} — enables the thinking-stream test; slow, so opt-in
 *       (e.g. {@code moonshotai/kimi-k3}).</li>
 *   <li>{@code NVIDIA_LIVE_VISION_MODEL} — enables the vision test.</li>
 * </ul>
 */

@DisplayName("NVIDIA — live")
class NvidiaLiveTest extends ProviderLiveSuite {

    private static final String DEFAULT_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b";

    private final String model = env("NVIDIA_LIVE_MODEL", DEFAULT_MODEL);

    @Override String label() { return "NVIDIA"; }

    @Override String skipReason() {
        return has("NVIDIA_API_KEY") ? null : "NVIDIA_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return Nvidia.of(model); }

    @Override
    AiProvider visionProvider() {
        String vision = System.getenv("NVIDIA_LIVE_VISION_MODEL");
        return vision == null || vision.isBlank() ? null : Nvidia.of(vision);
    }

    // -- opt-in: slow or model-specific --------------------------------------------------

    @Test @DisplayName("a reasoning model streams its thinking apart from the answer")
    void thinkingStream() {
        String reasoning = System.getenv("NVIDIA_LIVE_REASONING_MODEL");
        Assumptions.assumeTrue(reasoning != null && !reasoning.isBlank(),
            "NVIDIA_LIVE_REASONING_MODEL not set — skipping (slow)");

        List<String> thinking = new ArrayList<>();
        List<String> answer = new ArrayList<>();
        appOn(Nvidia.of(reasoning).withReasoningEffort("low").withMaxTokens(2048))
            .prompt("What is 17 * 23? Answer with just the number.")
            .onThinking(thinking::add)
            .stream(answer::add);

        System.out.println("[live] thinking chunks " + thinking.size() + ", answer: " + abbreviate(String.join("", answer)));
        String answerText = String.join("", answer);
        assertThat(answerText).contains("391");
        assertThat(thinking).as("reasoning tokens arrived").isNotEmpty();
        // Thinking is a side channel: a substantial chunk of it never shows up in the answer text.
        thinking.stream().map(String::strip).filter(t -> t.length() >= 6).findFirst()
            .ifPresent(t -> assertThat(answerText).doesNotContain(t));
    }
}
