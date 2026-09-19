package io.cafeai.agents;

import dev.langchain4j.agent.tool.Tool;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Gemini;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent with a real model: the model decides to call a tool, the framework runs it, and the
 * answer is built from the result. Also that the session's memory reaches the agent.
 *
 * <p>Runs against the first provider that is available, in this order: OpenAI ({@code OPENAI_API_KEY}),
 * Anthropic ({@code ANTHROPIC_API_KEY}), Gemini ({@code GEMINI_API_KEY}), then Ollama on this machine
 * ({@code OLLAMA_LIVE_MODEL}, default {@code llama3.2}, which must be pulled and must support tools).
 * Model ids come from {@code OPENAI_LIVE_MODEL}, {@code ANTHROPIC_LIVE_MODEL} and {@code GEMINI_LIVE_MODEL},
 * with the same defaults as the providers' own live tests. Run with {@code ./gradlew :cafeai-agents:liveTest}.
 */
@Tag("live")
@DisplayName("agents — live")
class AgentLiveTest {

    private static AiProvider provider;
    private static String label;

    @BeforeAll
    static void pickProvider() {
        if (has("OPENAI_API_KEY")) {
            provider = OpenAI.of(env("OPENAI_LIVE_MODEL", "gpt-4o-mini"));
            label = "OpenAI";
        } else if (has("ANTHROPIC_API_KEY")) {
            provider = Anthropic.of(env("ANTHROPIC_LIVE_MODEL", "claude-haiku-4-5-20251001"));
            label = "Anthropic";
        } else if (has("GEMINI_API_KEY")) {
            provider = Gemini.of(env("GEMINI_LIVE_MODEL", "gemini-2.5-flash"));
            label = "Gemini";
        } else {
            String model = env("OLLAMA_LIVE_MODEL", "llama3.2");
            String url = env("OLLAMA_LIVE_URL", "http://localhost:11434");
            if (ollamaHas(url, model)) {
                provider = Ollama.at(url).model(model);
                label = "Ollama";
            }
        }
        Assumptions.assumeTrue(provider != null,
            "no provider available: set OPENAI_API_KEY, ANTHROPIC_API_KEY or GEMINI_API_KEY, "
            + "or run Ollama with a tool-capable model");
        System.out.println("[live] agent provider: " + label + " — " + provider.modelId());
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static boolean has(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() && !v.startsWith("test-key");
    }

    private static boolean ollamaHas(String url, String model) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var response = client.send(HttpRequest.newBuilder(URI.create(url + "/api/tags"))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                && (response.body().contains("\"" + model + "\"") || response.body().contains("\"" + model + ":"));
        } catch (Exception e) {
            return false;
        }
    }

    interface Vault {
        String ask(String question);
    }

    /** The code is not something a model can guess, so an answer that contains it came from the tool. */
    public static class VaultTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("Returns the vault access code. Call it whenever you are asked for the access code.")
        public String accessCode() {
            calls.incrementAndGet();
            return "K7-ALPHA-93";
        }
    }

    private static final String SYSTEM =
        "You answer questions about a vault. You do not know the access code: to give it, you must call the accessCode tool.";

    @Test @DisplayName("the model calls a tool and answers from what it returned")
    void toolIsCalled() {
        var app = CafeAI.create();
        app.ai(provider);
        var tools = new VaultTools();
        app.agent("vault", Vault.class).system(SYSTEM).tool(tools);

        String answer = app.agent("vault", Vault.class, null).ask("What is the vault access code?");

        System.out.println("[live] agent answer: " + answer + " (tool calls " + tools.calls.get() + ")");
        assertThat(tools.calls.get()).as("the tool was called").isPositive();
        // A small model may reword the code, but it cannot have known ALPHA and 93 without the tool.
        assertThat(answer.toUpperCase()).contains("ALPHA").contains("93");
    }

    @Test @DisplayName("session memory reaches the agent: a fact from one turn is known in the next")
    void agentRemembers() {
        var app = CafeAI.create();
        app.ai(provider);
        app.memory(MemoryStrategy.inMemory());
        app.agent("vault", Vault.class).system(SYSTEM).tool(new VaultTools());

        app.agent("vault", Vault.class, "live-agent").ask("My name is Zephyrine. Please remember it.");
        String answer = app.agent("vault", Vault.class, "live-agent").ask("What is my name?");

        System.out.println("[live] agent memory: " + answer);
        assertThat(answer).containsIgnoringCase("Zephyrine");
    }
}
