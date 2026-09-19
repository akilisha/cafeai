package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Jlama;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jlama, the in-process inference provider: a real model, through the LangChain4j Jlama builders. No key
 * is needed, but a model must be on disk (or downloadable), so this only runs when you name one:
 *
 * <pre>
 *   JLAMA_LIVE_MODEL=tjake/Qwen2.5-0.5B-Instruct-JQ4 ./gradlew :cafeai-core:liveTest
 * </pre>
 *
 * <p>The first run downloads the model (about 300 MB for the one above) into Jlama's cache,
 * {@code ~/.jlama/models}. The {@code liveTest} task already passes the Vector API flags Jlama requires.
 *
 * <p>It runs the shared suite with two differences: Jlama's {@code withMaxTokens} limit counts the prompt
 * as well as the answer, so the cap is larger, and {@code withTimeout} is refused (there is no call to time
 * out), so that check is skipped. A 0.5B model is small; if a check that needs it to recall or reason fails,
 * try a larger model before suspecting the framework.
 */
@DisplayName("Jlama — live")
class JlamaLiveTest extends ProviderLiveSuite {

    private final String model = System.getenv("JLAMA_LIVE_MODEL");

    @Override String label() { return "Jlama"; }

    @Override String skipReason() {
        return model != null && !model.isBlank() ? null
            : "JLAMA_LIVE_MODEL is not set (needs a Hugging Face model id, e.g. tjake/Qwen2.5-0.5B-Instruct-JQ4)";
    }

    /**
     * Temperature 0, so a small model gives the same answer every run: at its default sampling a 0.5B model
     * sometimes answers "what is my name" with its own name, and a suite that passes and fails at random
     * proves nothing.
     */
    @Override AiProvider provider() { return Jlama.of(model).withTemperature(0.0); }

    /** Jlama's limit is the prompt plus the answer, and the chat template alone is dozens of tokens. */
    @Override int smallCap() { return 128; }

    /** 128 tokens in total is a short prompt and roughly an 80-token answer. */
    @Override int maxCharsAtCap() { return 700; }

    @Override boolean honoursTimeout() { return false; }

    /**
     * A 0.5B model does not do the summarising task: asked to summarise, it replied "Great, I've got the
     * numbers and the reason for your bees. What's the next step?" (a larger model, or {@code .model(...)}
     * pointing summaries at one, is the remedy; see {@code HistoryPolicy.summarise()}).
     */
    @Override boolean canSummarise() { return false; }

    @Test @DisplayName("a limit smaller than the prompt fails, and says so")
    void limitBelowThePromptFails() {
        assertThatThrownBy(() -> appOn(Jlama.of(model).withMaxTokens(4)).prompt("Say hello.").call())
            .hasStackTraceContaining("Prompt exceeds max tokens");
    }

    @Test @DisplayName("withTemperature(0) makes the same prompt answer the same way twice")
    void zeroTemperatureIsRepeatable() {
        var app = appOn(Jlama.of(model).withTemperature(0.0));

        String first = app.prompt("Name a primary colour.").call().text();
        String second = app.prompt("Name a primary colour.").call().text();

        System.out.println("[live] temperature=0: " + abbreviate(first) + " | " + abbreviate(second));
        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test @DisplayName("cachedIn(dir) finds a model that is already in that directory, without downloading")
    void cachedInFindsTheModel() {
        Path cache = Path.of(System.getProperty("user.home"), ".jlama", "models");
        // Jlama stores owner/name as owner_name
        Assumptions.assumeTrue(Files.isDirectory(cache.resolve(model.replace('/', '_'))),
            "the model is not in " + cache + " yet — run the other tests once to download it");

        String text = appOn(Jlama.cachedIn(cache.toString()).model(model))
            .prompt("Say hello.").call().text();

        System.out.println("[live] cachedIn: " + abbreviate(text));
        assertThat(text).isNotBlank();
    }
}
