package io.cafeai.core.internal;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Gemini;
import io.cafeai.core.ai.Nvidia;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.ai.OpenAI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each provider's {@code withTemperature} / {@code withMaxTokens} / {@code withTimeout}
 * reaches the right LangChain4j builder call. {@link ProviderOptionsTest} covers the
 * API surface; this covers what the bridge and the providers do with it.
 *
 * <p>Models are built offline with placeholder keys (see {@code cafeai-core/build.gradle}) —
 * building a model makes no network call — and read back through
 * {@code defaultRequestParameters()}. {@code withTimeout} is not readable off a model, so
 * it is proven on the wire against a local server standing in for Ollama, the one
 * provider whose base URL is configurable.
 *
 * <p><strong>Not covered: Jlama.</strong> Building a Jlama model downloads the model, so
 * its mapping (including the {@code Double}→{@code Float} temperature conversion) has
 * no offline test.
 */
class ProviderMappingTest {

    private static final LangchainBridge BRIDGE = LangchainBridge.INSTANCE;

    // ── OpenAI ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OpenAI: maxTokens goes to max_completion_tokens, not max_tokens")
    void openai_mapsToMaxCompletionTokens() {
        var p = OpenAI.of("gpt-4o").withTemperature(0.3).withMaxTokens(700);

        for (ChatRequestParameters params : List.of(
                BRIDGE.modelFor(p).defaultRequestParameters(),
                BRIDGE.streamingModelFor(p).defaultRequestParameters())) {
            assertThat(params.temperature()).isEqualTo(0.3);
            assertThat(((OpenAiChatRequestParameters) params).maxCompletionTokens()).isEqualTo(700);
            // Newer OpenAI models reject max_tokens, so it must stay unset.
            assertThat(params.maxOutputTokens()).isNull();
        }
    }

    @Test
    @DisplayName("OpenAI: unset settings stay unset")
    void openai_unsetStaysUnset() {
        var p = OpenAI.of("gpt-4o");

        for (ChatRequestParameters params : List.of(
                BRIDGE.modelFor(p).defaultRequestParameters(),
                BRIDGE.streamingModelFor(p).defaultRequestParameters())) {
            assertThat(params.temperature()).isNull();
            assertThat(((OpenAiChatRequestParameters) params).maxCompletionTokens()).isNull();
            assertThat(params.maxOutputTokens()).isNull();
        }
    }

    // ── Anthropic ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Anthropic: temperature and maxTokens reach the model, blocking and streaming")
    void anthropic_mapsKnobs() {
        var p = Anthropic.of("claude-sonnet-4-5").withTemperature(0.0).withMaxTokens(2048);

        for (ChatRequestParameters params : List.of(
                BRIDGE.modelFor(p).defaultRequestParameters(),
                BRIDGE.streamingModelFor(p).defaultRequestParameters())) {
            assertThat(params.temperature()).isEqualTo(0.0);
            assertThat(params.maxOutputTokens()).isEqualTo(2048);
        }
    }

    @Test
    @DisplayName("Anthropic: an unset temperature stays unset")
    void anthropic_unsetTemperature() {
        var p = Anthropic.of("claude-sonnet-4-5");

        assertThat(BRIDGE.modelFor(p).defaultRequestParameters().temperature()).isNull();
        assertThat(BRIDGE.streamingModelFor(p).defaultRequestParameters().temperature()).isNull();
    }

    // ── Gemini ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Gemini: temperature and maxTokens (maxOutputTokens) reach the model")
    void gemini_mapsKnobs() {
        var p = Gemini.of("gemini-2.5-flash").withTemperature(0.9).withMaxTokens(512);

        ChatRequestParameters params =
            ((LangchainBridge.ChatModelAccess) p).toChatModel().defaultRequestParameters();

        assertThat(params.temperature()).isEqualTo(0.9);
        assertThat(params.maxOutputTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("Gemini: unset settings stay unset")
    void gemini_unsetStaysUnset() {
        ChatRequestParameters params = ((LangchainBridge.ChatModelAccess)
            Gemini.of("gemini-2.5-flash")).toChatModel().defaultRequestParameters();

        assertThat(params.temperature()).isNull();
        assertThat(params.maxOutputTokens()).isNull();
    }

    // ── Nvidia ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Nvidia: all three settings reach both the blocking and streaming model")
    void nvidia_mapsKnobs() {
        var p = Nvidia.of("moonshotai/kimi-k3")
            .withTemperature(1.0).withMaxTokens(16384).withReasoningEffort("max");

        for (ChatRequestParameters params : List.of(
                p.toChatModel().defaultRequestParameters(),
                p.toStreamingChatModel().defaultRequestParameters())) {
            var openAi = (OpenAiChatRequestParameters) params;
            assertThat(openAi.temperature()).isEqualTo(1.0);
            assertThat(openAi.maxCompletionTokens()).isEqualTo(16384);
            assertThat(openAi.reasoningEffort()).isEqualTo("max");
        }
    }

    @Test
    @DisplayName("Nvidia: unset settings stay unset")
    void nvidia_unsetStaysUnset() {
        var p = Nvidia.of("moonshotai/kimi-k3");

        for (ChatRequestParameters params : List.of(
                p.toChatModel().defaultRequestParameters(),
                p.toStreamingChatModel().defaultRequestParameters())) {
            var openAi = (OpenAiChatRequestParameters) params;
            assertThat(openAi.temperature()).isNull();
            assertThat(openAi.maxCompletionTokens()).isNull();
            assertThat(openAi.reasoningEffort()).isNull();
        }
    }

    // ── Ollama (streaming; the blocking path is in ProviderOptionsTest) ───────

    @Test
    @DisplayName("Ollama: the streaming model gets temperature and maxTokens (num_predict)")
    void ollama_streamingMapsKnobs() {
        StreamingChatModel model = BRIDGE.streamingModelFor(
            Ollama.of("stream-knobs").withTemperature(0.2).withMaxTokens(256));

        assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
        assertThat(model.defaultRequestParameters().maxOutputTokens()).isEqualTo(256);
    }

    // ── withTimeout, on the wire ──────────────────────────────────────────────

    private static final String OLLAMA_REPLY = """
        {"model":"m","created_at":"2026-01-01T00:00:00Z",
         "message":{"role":"assistant","content":"pong"},
         "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";

    /** A stand-in Ollama that takes {@code delayMillis} to answer every chat request. */
    private static HttpServer slowOllama(long delayMillis) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(delayMillis);
                byte[] body = OLLAMA_REPLY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
                // the client gave up on us; nothing to answer
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String ask(AiProvider provider) {
        return BRIDGE.modelFor(provider).chat(List.of(UserMessage.from("ping"))).aiMessage().text();
    }

    @Test
    @DisplayName("withTimeout: a provider given less time than the model needs times out")
    void timeout_shortLimitFires() throws Exception {
        HttpServer server = slowOllama(1500);
        try {
            AiProvider p = Ollama.at(url(server)).model("wire-short")
                .withTimeout(Duration.ofMillis(300));

            assertThatThrownBy(() -> ask(p)).hasMessageContaining("timed out");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("withTimeout: a provider given enough time gets its answer from the same slow model")
    void timeout_longLimitWaits() throws Exception {
        HttpServer server = slowOllama(1500);
        try {
            AiProvider p = Ollama.at(url(server)).model("wire-long")
                .withTimeout(Duration.ofSeconds(20));

            assertThat(ask(p)).isEqualTo("pong");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("withTimeout: a provider's own limit beats the 60s cafeai.chat.timeout default")
    void timeout_perProviderBeatsDefault() throws Exception {
        HttpServer server = slowOllama(1500);
        try {
            // Unset falls back to the 60s default, so the slow reply is simply waited for...
            assertThat(ask(Ollama.at(url(server)).model("wire-default"))).isEqualTo("pong");

            // ...whereas the same provider with its own short limit does not wait.
            AiProvider quick = Ollama.at(url(server)).model("wire-default")
                .withTimeout(Duration.ofMillis(300));
            assertThatThrownBy(() -> ask(quick)).hasMessageContaining("timed out");
        } finally {
            server.stop(0);
        }
    }
}
