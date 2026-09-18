package io.cafeai.core.live;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Jlama;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live smoke test of Jlama, the in-process inference provider: a real model, through the LangChain4j Jlama
 * builders, after the LangChain4j upgrade. No key is needed, but a model must be on disk (or downloadable),
 * so this only runs when you name one:
 *
 * <pre>
 *   JLAMA_LIVE_MODEL=tjake/Qwen2.5-0.5B-Instruct-JQ4 ./gradlew :cafeai-core:liveTest
 * </pre>
 *
 * <p>The first run downloads the model (about 300 MB for the one above) into Jlama's cache,
 * {@code ~/.jlama/models}. The {@code liveTest} task already passes the Vector API flags Jlama requires.
 */
@Tag("live")
@DisplayName("Jlama — live")
class JlamaLiveTest {

    private static String model;

    @BeforeAll
    static void requireModel() {
        model = System.getenv("JLAMA_LIVE_MODEL");
        Assumptions.assumeTrue(model != null && !model.isBlank(),
            "JLAMA_LIVE_MODEL is not set — skipping (needs a Hugging Face model id, e.g. tjake/Qwen2.5-0.5B-Instruct-JQ4)");
        System.out.println("[live] jlama model: " + model);
    }

    private static CafeAI appOn(AiProvider provider) {
        var app = CafeAI.create();
        app.ai(provider);
        return app;
    }

    private static String abbreviate(String s) {
        String flat = s == null ? "null" : s.replaceAll("\\s+", " ").trim();
        return flat.length() > 100 ? flat.substring(0, 100) + "…" : flat;
    }

    @Test @DisplayName("a plain call returns text from the in-process model")
    void plainCall() {
        var r = appOn(Jlama.of(model)).prompt("Say hello in one short sentence.").call();

        System.out.println("[live] plain: " + abbreviate(r.text()));
        assertThat(r.text()).isNotBlank();
        assertThat(r.modelId()).isEqualTo(model);
    }

    @Test @DisplayName("a streamed call delivers several tokens")
    void streamedCall() {
        List<String> tokens = new CopyOnWriteArrayList<>();

        appOn(Jlama.of(model)).prompt("Count from one to five in words.").stream(tokens::add);

        System.out.println("[live] stream: " + tokens.size() + " tokens -> " + abbreviate(String.join("", tokens)));
        assertThat(tokens.size()).as("more than one chunk arrived").isGreaterThan(1);
        assertThat(String.join("", tokens)).isNotBlank();
    }

    @Test @DisplayName("withMaxTokens cuts a long answer short; for Jlama the limit counts the prompt as well")
    void maxTokensIsHonoured() {
        // Jlama's limit is prompt + answer. The chat template alone is dozens of tokens, so the limit has to
        // leave room for them: 128 in total is a short prompt and roughly an 80-token answer.
        String text = appOn(Jlama.of(model).withMaxTokens(128))
            .prompt("Write a long essay about the history of the printing press.").call().text();

        System.out.println("[live] maxTokens=128: " + text.length() + " chars -> " + abbreviate(text));
        assertThat(text).isNotBlank();
        assertThat(text.length()).as("128 tokens in total cannot be a long essay").isLessThan(700);
    }

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
