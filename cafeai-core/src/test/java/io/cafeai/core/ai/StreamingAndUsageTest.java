package io.cafeai.core.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things a live Gemini run showed: a provider may leave out a token count, and every built-in
 * provider has to be able to stream, not only call.
 */
@DisplayName("streaming support and token usage")
class StreamingAndUsageTest {

    // -- every built-in provider can stream --------------------------------------------------------

    @Test @DisplayName("Gemini has a streaming model, so .stream() works with it")
    void geminiStreams() {
        assertThat(Gemini.of("any-model")).isInstanceOf(LangchainBridge.StreamingChatModelAccess.class);
    }

    @Test @DisplayName("each built-in provider that is not built by the bridge itself supplies a streaming model")
    void customProvidersStream() {
        // The bridge builds OpenAI, Anthropic, Ollama, Jlama and NVIDIA streaming models from the provider
        // type. Any provider that reports CUSTOM has no such route, and must supply its own or fail on .stream().
        for (AiProvider p : List.of(Gemini.of("m"))) {
            if (p.type() == AiProvider.ProviderType.CUSTOM) {
                assertThat(p).as(p.name() + " reports CUSTOM").isInstanceOf(LangchainBridge.StreamingChatModelAccess.class);
            }
        }
    }

    // -- a provider that reports no token count -----------------------------------------------------------

    /** Answers with usage that carries no counts, as Gemini does when it stops at a token limit. */
    private static final class NoCounts implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()           { return "no-counts"; }
        @Override public String       modelId()        { return "no-counts"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        private static ChatResponse response() {
            return ChatResponse.builder().aiMessage(AiMessage.from("an answer"))
                .tokenUsage(new TokenUsage(null, null, null)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return response(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    h.onPartialResponse("an answer");
                    h.onCompleteResponse(response());
                }
            };
        }
    }

    // -- a model that answers with no text -----------------------------------------------------------------

    /** Answers with no text at all, as a thinking model does when its token limit is spent on thinking. */
    private static final class NoText implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String       name()           { return "no-text"; }
        @Override public String       modelId()        { return "no-text"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    // an AiMessage that carries no text has a null text()
                    return ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("1").name("x").arguments("{}").build())).build();
                }
            };
        }
    }

    @Test @DisplayName("a model that answers with no text gives an empty answer, not null")
    void noTextIsAnEmptyAnswer() {
        var app = CafeAI.create();
        app.ai(new NoText());

        assertThat(app.prompt("hello").call().text()).isEmpty();
        assertThat(app.vision("describe", new byte[]{1, 2, 3}, "image/png").call().text()).isEmpty();
    }

    @Test @DisplayName("an empty answer is stored in the session as an empty message, not null")
    void noTextInASession() {
        var memory = io.cafeai.core.memory.MemoryStrategy.inMemory();
        var app = CafeAI.create();
        app.ai(new NoText());
        app.memory(memory);

        app.prompt("hello").session("s").call();

        assertThat(memory.retrieve("s").messages()).extracting(m -> m.content()).containsExactly("hello", "");
    }

    private static CafeAI app() {
        var app = CafeAI.create();
        app.ai(new NoCounts());
        return app;
    }

    @Test @DisplayName("a plain call whose usage has no counts still returns its answer, with zero tokens")
    void plainCallWithoutCounts() {
        var r = app().prompt("hello").call();

        assertThat(r.text()).isEqualTo("an answer");
        assertThat(r.promptTokens()).isZero();
        assertThat(r.outputTokens()).isZero();
    }

    @Test @DisplayName("a streamed call whose usage has no counts still delivers its answer")
    void streamedCallWithoutCounts() {
        List<String> tokens = new ArrayList<>();

        app().prompt("hello").stream(tokens::add);

        assertThat(String.join("", tokens)).isEqualTo("an answer");
    }

    @Test @DisplayName("a vision call whose usage has no counts still returns its answer")
    void visionCallWithoutCounts() {
        var r = app().vision("describe", new byte[]{1, 2, 3}, "image/png").call();

        assertThat(r.text()).isEqualTo("an answer");
    }
}
