package io.cafeai.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.cache.SemanticCache;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.Retriever;
import io.cafeai.core.rag.VectorStore;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code app.observe(...)} end to end: a real {@link CafeAI} app, a fake model, and the module
 * discovered through {@code ServiceLoader} — so the wiring between the engine and the bridge is
 * exercised, not just the bridge.
 */
@DisplayName("app.observe() wiring")
class ObserveWiringTest {

    /** A model with fixed token usage that can be told to fail. */
    private static final class Model implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        RuntimeException failure;

        @Override public String       name()           { return "fake"; }
        @Override public String       modelId()        { return "gpt-4o-mini"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }
        @Override public boolean      supportsAudio()  { return true; }

        private static ChatResponse reply() {
            return ChatResponse.builder().aiMessage(AiMessage.from("pong")).tokenUsage(new TokenUsage(5, 3)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    if (failure != null) throw failure;
                    return reply();
                }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    if (failure != null) { handler.onError(failure); return; }
                    handler.onPartialResponse("po");
                    handler.onPartialResponse("ng");
                    handler.onCompleteResponse(reply());
                }
            };
        }
    }

    private Model model;

    @BeforeEach
    void setUp() {
        TestOtel.reset();
        model = new Model();
    }

    private CafeAI observed() {
        var app = CafeAI.create();
        app.ai(model);
        app.observe(ObserveStrategy.otel());
        return app;
    }

    @Test @DisplayName("a prompt call produces one span with the model, token usage and session")
    void promptSpan() {
        var app = observed();
        app.memory(MemoryStrategy.inMemory());

        String text = app.prompt("ping").session("s-1").call().text();

        assertThat(text).isEqualTo("pong");
        SpanData span = TestOtel.onlySpan();
        assertThat(span.getName()).isEqualTo("chat");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(TestOtel.attrs(span))
            .containsEntry("gen_ai.response.model", "gpt-4o-mini")
            .containsEntry("gen_ai.system", "openai")
            .containsEntry("gen_ai.usage.input_tokens", 5L)
            .containsEntry("gen_ai.usage.output_tokens", 3L)
            .containsEntry("cafeai.usage.total_tokens", 8L)
            .containsEntry("cafeai.session.id", "s-1")
            .containsEntry("cafeai.cache_hit", false);
    }

    @Test @DisplayName("a failing model call throws and is recorded as an ERROR span")
    void failedCall() {
        model.failure = new IllegalStateException("model down");
        var app = observed();

        assertThatThrownBy(() -> app.prompt("ping").call())
            .isInstanceOf(IllegalStateException.class).hasMessage("model down");

        SpanData span = TestOtel.onlySpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(TestOtel.attrs(span)).containsEntry("error.type", "java.lang.IllegalStateException");
    }

    @Test @DisplayName("a failing vision call is recorded as an ERROR span")
    void failedVisionCall() {
        model.failure = new IllegalStateException("vision model down");
        var app = observed();

        assertThatThrownBy(() -> app.vision("what is this?", new byte[]{1, 2, 3}, "image/png").call())
            .isInstanceOf(IllegalStateException.class).hasMessage("vision model down");

        SpanData span = TestOtel.onlySpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(TestOtel.attrs(span)).containsEntry("error.type", "java.lang.IllegalStateException");
    }

    @Test @DisplayName("a failing audio call is recorded as an ERROR span")
    void failedAudioCall() {
        model.failure = new IllegalStateException("audio model down");
        var app = observed();

        assertThatThrownBy(() -> app.audio("transcribe", new byte[]{1, 2, 3}, "audio/wav").call())
            .isInstanceOf(IllegalStateException.class).hasMessage("audio model down");

        SpanData span = TestOtel.onlySpan();
        assertThat(span.getName()).isEqualTo("transcribe");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test @DisplayName("a streamed call is recorded once, when the stream completes")
    void streamedCall() {
        var app = observed();
        List<String> tokens = new ArrayList<>();

        app.prompt("ping").stream(tokens::add);

        assertThat(String.join("", tokens)).isEqualTo("pong");
        SpanData span = TestOtel.onlySpan();
        assertThat(span.getName()).isEqualTo("chat");
        assertThat(TestOtel.attrs(span)).containsEntry("gen_ai.usage.output_tokens", 3L);
    }

    @Test @DisplayName("a vision call is recorded with its input type and size")
    void visionCall() {
        var app = observed();

        app.vision("what is this?", new byte[]{1, 2, 3}, "image/png").call();

        assertThat(TestOtel.attrs(TestOtel.onlySpan()))
            .containsEntry("cafeai.input.type", "vision")
            .containsEntry("cafeai.input.mime_type", "image/png")
            .containsEntry("cafeai.input.content_bytes", 3L);
    }

    @Test @DisplayName("RAG produces a 'retrieve' span, and the chat span counts the documents")
    void ragCall() {
        var app = observed();
        app.embed(new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1f}; }
            @Override public int dimensions() { return 1; }
            @Override public String modelId() { return "test"; }
        });
        app.vectordb(VectorStore.inMemory());
        app.rag(new Retriever() {
            @Override public List<RagDocument> retrieve(String q, EmbeddingProvider e, VectorStore s) {
                return List.of(new RagDocument("one", "d1", 1.0), new RagDocument("two", "d2", 0.9));
            }
            @Override public int topK() { return 2; }
        });

        app.prompt("what do you know?").call();

        List<SpanData> spans = TestOtel.spans();
        assertThat(spans).extracting(SpanData::getName).containsExactlyInAnyOrder("retrieve", "chat");
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        spans.forEach(s -> byName.put(s.getName(), TestOtel.attrs(s)));
        assertThat(byName.get("retrieve")).containsEntry("cafeai.rag.documents_retrieved", 2L);
        assertThat(byName.get("chat")).containsEntry("cafeai.rag.documents_retrieved", 2L);
    }

    @Test @DisplayName("a semantic-cache hit is recorded as cafeai.cache_hit = true")
    void cacheHit() {
        var app = observed();
        app.cache(SemanticCache.inMemory(new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
            @Override public int dimensions() { return 2; }
            @Override public String modelId() { return "test"; }
        }).build());

        app.prompt("what are your opening hours?").call();   // miss: filled from the model
        TestOtel.reset();
        app.prompt("what are your opening hours?").call();   // hit

        assertThat(TestOtel.attrs(TestOtel.onlySpan())).containsEntry("cafeai.cache_hit", true);
    }

    @Test @DisplayName("without app.observe() nothing is recorded")
    void noObserve() {
        var app = CafeAI.create();
        app.ai(model);

        app.prompt("ping").call();

        assertThat(TestOtel.spans()).isEmpty();
    }

    @Test @DisplayName("app.observe() rejects something that is not an ObserveStrategy")
    void wrongType() {
        var app = CafeAI.create();

        assertThatThrownBy(() -> app.observe("otel")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ObserveStrategy");
    }
}
