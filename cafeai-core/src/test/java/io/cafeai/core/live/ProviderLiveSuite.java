package io.cafeai.core.live;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.memory.HistoryPolicy;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every provider must do through CafeAI, run against the real thing: the plain, streamed and
 * structured calls, a system prompt, session memory, the history policies, and the provider settings
 * ({@code withMaxTokens}, {@code withTemperature}, {@code withTimeout}) reaching the wire.
 *
 * <p>A provider's live test extends this, says why it cannot run (no key, no server) and how to build
 * its provider, and adds whatever only it can do. Everything here skips itself when the provider is
 * not available, and every live test is excluded from the normal {@code test} task; run them with
 * {@code ./gradlew :cafeai-core:liveTest}.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ProviderLiveSuite {

    /** A short name for the log. */
    abstract String label();

    /** Why this suite cannot run here (no key, no server, model not installed), or {@code null} if it can. */
    abstract String skipReason();

    /** A provider for the model under test. */
    abstract AiProvider provider();

    /** A provider that can read images, or {@code null} to skip the vision test. */
    AiProvider visionProvider() { return null; }

    @BeforeAll
    void requireProvider() {
        String why = skipReason();
        Assumptions.assumeTrue(why == null, label() + ": " + why);
        System.out.println("[live] " + label() + " — model " + provider().modelId());
    }

    // -- helpers ---------------------------------------------------------------------------------------

    CafeAI appOn(AiProvider provider) {
        var app = CafeAI.create();
        app.ai(provider);
        return app;
    }

    CafeAI app() {
        return appOn(provider());
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    static boolean has(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() && !v.startsWith("test-key");
    }

    static String abbreviate(String s) {
        String flat = s == null ? "null" : s.replaceAll("\\s+", " ").trim();
        return flat.length() > 100 ? flat.substring(0, 100) + "…" : flat;
    }

    static byte[] redPng() throws IOException {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) img.setRGB(x, y, 0xFF0000);
        var png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        return png.toByteArray();
    }

    // -- the basics ------------------------------------------------------------------------------------

    @Test @DisplayName("a plain call returns text, token usage and the model id")
    void plainCall() {
        PromptResponse r = app().prompt("Repeat this word back to me and say nothing else: pong").call();

        System.out.println("[live] plain: " + abbreviate(r.text())
            + " (tokens in/out " + r.promptTokens() + "/" + r.outputTokens() + ", model " + r.modelId() + ")");
        assertThat(r.text()).isNotBlank();
        assertThat(r.text().toLowerCase()).contains("pong");
    }

    @Test @DisplayName("a streamed call delivers several tokens that add up to the answer")
    void streamedCall() {
        List<String> tokens = new CopyOnWriteArrayList<>();
        app().prompt("Count from one to five in words, separated by commas.").stream(tokens::add);

        String joined = String.join("", tokens);
        System.out.println("[live] stream: " + tokens.size() + " tokens -> " + abbreviate(joined));
        assertThat(tokens.size()).as("more than one chunk arrived").isGreaterThan(1);
        assertThat(joined.toLowerCase()).contains("three");
    }

    record Capital(String country, String capital) {}

    @Test @DisplayName("structured output: call(Class) returns a typed object")
    void structuredOutput() {
        Capital c = app()
            .prompt("What is the capital of France? Answer for country France.")
            .call(Capital.class);

        System.out.println("[live] structured: " + c);
        assertThat(c).isNotNull();
        assertThat(c.capital()).containsIgnoringCase("Paris");
    }

    @Test @DisplayName("a system prompt shapes the reply")
    void systemPrompt() {
        var app = app();
        app.system("You are a pirate. Always end your reply with the word ARRR.");

        String text = app.prompt("Say hello.").call().text();

        System.out.println("[live] system: " + abbreviate(text));
        assertThat(text.toUpperCase()).contains("ARRR");
    }

    @Test @DisplayName("session memory carries a fact from one turn to the next")
    void sessionMemory() {
        var app = app();
        app.memory(MemoryStrategy.inMemory());

        app.prompt("My name is Zephyrine. Please remember it.").session("live-1").call();
        String text = app.prompt("What is my name?").session("live-1").call().text();

        System.out.println("[live] memory: " + abbreviate(text));
        assertThat(text).containsIgnoringCase("Zephyrine");
    }

    // -- history policies -------------------------------------------------------------------------------

    private static final List<String> FILLER = List.of(
        "What is 2 + 2?", "Name one primary colour.", "What is the capital of Italy?");

    @Test @DisplayName("summarise: a fact that has been folded into a summary is still known")
    void summariseKeepsAFact() {
        var app = app();
        var memory = MemoryStrategy.inMemory();
        app.memory(memory);
        app.history(HistoryPolicy.summarise().keepRecent(2).after(6));

        app.prompt("My name is Zephyrine and I keep bees. Please remember that.").session("live-h1").call();
        for (String question : FILLER) {
            app.prompt(question).session("live-h1").call();     // the 4th exchange stores message 8 (> 6) and summarises
        }

        var stored = memory.retrieve("live-h1");
        System.out.println("[live] summary: " + abbreviate(stored.summary()));
        assertThat(stored.summary()).as("the older turns were folded into a summary").isNotBlank();
        assertThat(stored.messages()).as("only the newest messages are kept whole").hasSize(2);
        assertThat(stored.summary()).as("the model kept the name in the summary").containsIgnoringCase("Zephyrine");

        String text = app.prompt("What is my name, and what do I keep?").session("live-h1").call().text();
        System.out.println("[live] after summary: " + abbreviate(text));
        assertThat(text).containsIgnoringCase("Zephyrine");
    }

    @Test @DisplayName("lastMessages: a fact that has fallen out of the window is not known")
    void windowForgets() {
        var app = app();
        app.memory(MemoryStrategy.inMemory());
        app.history(HistoryPolicy.lastMessages(2));

        app.prompt("My name is Zephyrine. Please remember it.").session("live-h2").call();
        app.prompt("What is 2 + 2?").session("live-h2").call();
        app.prompt("Name one primary colour.").session("live-h2").call();
        String text = app.prompt("What is my name?").session("live-h2").call().text();

        System.out.println("[live] forgotten: " + abbreviate(text));
        assertThat(text).doesNotContainIgnoringCase("Zephyrine");
    }

    // -- the provider settings reach the wire ---------------------------------------------------------

    @Test @DisplayName("withMaxTokens truncates a long answer")
    void maxTokensIsHonoured() {
        PromptResponse r = appOn(provider().withMaxTokens(16))
            .prompt("Count from 1 to 300, separated by spaces.").call();

        System.out.println("[live] maxTokens=16: " + r.text().length() + " chars, output tokens " + r.outputTokens());
        assertThat(r.text().length()).as("a 300-number count cannot fit in 16 tokens").isLessThan(200);
        if (r.outputTokens() > 0) assertThat(r.outputTokens()).isLessThanOrEqualTo(24);
    }

    @Test @DisplayName("withTemperature(0) is accepted by the endpoint")
    void temperatureIsAccepted() {
        String text = appOn(provider().withTemperature(0.0)).prompt("Reply with one word: ok").call().text();

        assertThat(text).isNotBlank();
    }

    @Test @DisplayName("withTimeout applies: an impossible timeout fails fast")
    void timeoutIsHonoured() {
        long start = System.nanoTime();

        assertThatThrownBy(() ->
            appOn(provider().withTimeout(Duration.ofMillis(1))).prompt("Write a long story.").call())
            .isInstanceOf(RuntimeException.class);

        long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        System.out.println("[live] timeout=1ms failed after ~" + seconds + "s");
        assertThat(seconds).as("failed quickly rather than waiting for a reply").isLessThan(15);
    }

    // -- vision, where the provider has it -----------------------------------------------------------

    @Test @DisplayName("a vision model describes an image")
    void vision() throws Exception {
        AiProvider vision = visionProvider();
        Assumptions.assumeTrue(vision != null, label() + ": no vision model configured — skipping");

        String text = appOn(vision.withMaxTokens(200))
            .vision("What single colour fills this image?", redPng(), "image/png").call().text();

        System.out.println("[live] vision: " + abbreviate(text));
        assertThat(text.toLowerCase()).contains("red");
    }
}
