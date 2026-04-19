package io.cafeai.core.ai;

import io.cafeai.core.CafeAI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ROADMAP-16 Phase 5: StreamingVoicePipeline.
 *
 * <p>Two areas:
 * <ul>
 *   <li>Sentence chunking logic — pure unit tests, no mocks needed</li>
 *   <li>Pipeline configuration — fluent API, validation</li>
 * </ul>
 */
class StreamingVoicePipelineTest {

    // ── Sentence chunking ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sentence chunking")
    class SentenceChunking {

        @Test
        @DisplayName("single short sentence returns as one chunk")
        void singleShortSentence_oneChunk() {
            var chunks = StreamingVoicePipeline.splitIntoChunks("Hello world.", 5);
            assertThat(chunks).containsExactly("Hello world.");
        }

        @Test
        @DisplayName("two long sentences produce two chunks")
        void twoLongSentences_twoChunks() {
            String text = "The standard deviation measures spread in a dataset. " +
                          "A higher value indicates more variability.";
            var chunks = StreamingVoicePipeline.splitIntoChunks(text, 20);
            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0)).contains("standard deviation");
            assertThat(chunks.get(1)).contains("variability");
        }

        @Test
        @DisplayName("short sentences are merged until minChunkSize is reached")
        void shortSentences_mergedUntilMinSize() {
            // Each sentence is ~10 chars, minSize=25 → should merge pairs
            String text = "Hi there. How are you? I am fine. That is good.";
            var chunks = StreamingVoicePipeline.splitIntoChunks(text, 25);
            // All chunks should be at least 25 chars except possibly the last
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertThat(chunks.get(i).length()).isGreaterThanOrEqualTo(25);
            }
        }

        @Test
        @DisplayName("question marks and exclamation marks are sentence boundaries")
        void questionAndExclamationBoundaries() {
            String text = "Are you ready? Yes I am! Let us begin.";
            var chunks = StreamingVoicePipeline.splitIntoChunks(text, 5);
            assertThat(chunks).hasSize(3);
            assertThat(chunks.get(0)).isEqualTo("Are you ready?");
            assertThat(chunks.get(1)).isEqualTo("Yes I am!");
            assertThat(chunks.get(2)).isEqualTo("Let us begin.");
        }

        @Test
        @DisplayName("trailing text without punctuation is captured as final chunk")
        void trailingTextWithoutPunctuation_capturedAsChunk() {
            String text = "First sentence. Trailing text without punctuation";
            var chunks = StreamingVoicePipeline.splitIntoChunks(text, 5);
            assertThat(chunks.get(chunks.size() - 1))
                .isEqualTo("Trailing text without punctuation");
        }

        @Test
        @DisplayName("empty text returns empty list")
        void emptyText_emptyList() {
            assertThat(StreamingVoicePipeline.splitIntoChunks("", 40)).isEmpty();
            assertThat(StreamingVoicePipeline.splitIntoChunks("  ", 40)).isEmpty();
        }

        @Test
        @DisplayName("null text returns empty list")
        void nullText_emptyList() {
            assertThat(StreamingVoicePipeline.splitIntoChunks(null, 40)).isEmpty();
        }

        @Test
        @DisplayName("text shorter than minChunkSize is returned as single chunk")
        void textShorterThanMin_singleChunk() {
            var chunks = StreamingVoicePipeline.splitIntoChunks("Short.", 1000);
            assertThat(chunks).containsExactly("Short.");
        }

        @Test
        @DisplayName("period inside a sentence (e.g. abbreviation) handled gracefully")
        void periodFollowedByNonSpace_notSplit() {
            // Period not followed by whitespace should not be treated as boundary
            String text = "The value is 3.14 and the result is 42.0 units.";
            var chunks = StreamingVoicePipeline.splitIntoChunks(text, 5);
            // Should not split on decimal points
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0)).contains("3.14");
        }
    }

    // ── Pipeline configuration ────────────────────────────────────────────────

    @Nested
    @DisplayName("Pipeline configuration")
    class PipelineConfiguration {

        @Test
        @DisplayName("create(app) returns non-null pipeline")
        void create_returnsNonNull() {
            var app = CafeAI.create();
            assertThat(StreamingVoicePipeline.create(app)).isNotNull();
        }

        @Test
        @DisplayName("create(null) throws IllegalArgumentException")
        void createWithNull_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> StreamingVoicePipeline.create(null))
                .withMessageContaining("app");
        }

        @Test
        @DisplayName("textProvider() and voiceProvider() return pipeline for chaining")
        void providerMethods_chainable() {
            var app = CafeAI.create();
            var pipeline = StreamingVoicePipeline.create(app);
            assertThat(pipeline.textProvider("tutor")).isSameAs(pipeline);
            assertThat(pipeline.voiceProvider("voice")).isSameAs(pipeline);
        }

        @Test
        @DisplayName("minChunkSize() returns pipeline for chaining")
        void minChunkSize_chainable() {
            var app = CafeAI.create();
            var pipeline = StreamingVoicePipeline.create(app);
            assertThat(pipeline.minChunkSize(60)).isSameAs(pipeline);
        }

        @Test
        @DisplayName("minChunkSize(0) throws IllegalArgumentException")
        void minChunkSizeZero_throws() {
            var app = CafeAI.create();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> StreamingVoicePipeline.create(app).minChunkSize(0));
        }

        @Test
        @DisplayName("stream() with null prompt throws IllegalArgumentException")
        void streamNullPrompt_throws() {
            var app = CafeAI.create();
            app.ai("tutor", mockProvider("ok"));
            app.ai("voice", ttsMock());
            var pipeline = StreamingVoicePipeline.create(app)
                .textProvider("tutor").voiceProvider("voice");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> pipeline.stream(null, chunk -> {}))
                .withMessageContaining("prompt");
        }

        @Test
        @DisplayName("stream() with null consumer throws IllegalArgumentException")
        void streamNullConsumer_throws() {
            var app = CafeAI.create();
            app.ai("tutor", mockProvider("ok"));
            app.ai("voice", ttsMock());
            var pipeline = StreamingVoicePipeline.create(app)
                .textProvider("tutor").voiceProvider("voice");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> pipeline.stream("hello", null))
                .withMessageContaining("consumer");
        }

        @Test
        @DisplayName("VoiceChunk hasAudio() false when bytes empty")
        void voiceChunk_hasAudioFalseWhenEmpty() {
            var chunk = new StreamingVoicePipeline.VoiceChunk("text", new byte[0], "mp3");
            assertThat(chunk.hasAudio()).isFalse();
        }

        @Test
        @DisplayName("VoiceChunk hasAudio() true when bytes present")
        void voiceChunk_hasAudioTrueWhenPresent() {
            var chunk = new StreamingVoicePipeline.VoiceChunk("text", new byte[]{1, 2, 3}, "mp3");
            assertThat(chunk.hasAudio()).isTrue();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider mockProvider(String response) {
        return new MockTextProvider(response);
    }

    private static AiProvider ttsMock() {
        return new MockTtsProvider();
    }

    private static final class MockTextProvider
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        private final String response;
        MockTextProvider(String response) { this.response = response; }
        @Override public String name()    { return "mock-text"; }
        @Override public String modelId() { return "mock-model"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }
        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            String r = response;
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest req) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(r))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(5, 2))
                        .build();
                }
            };
        }
    }

    private static final class MockTtsProvider implements AiProvider {
        @Override public String       name()        { return "mock-tts"; }
        @Override public String       modelId()     { return "tts-mock"; }
        @Override public ProviderType type()        { return ProviderType.CUSTOM; }
        @Override public boolean      supportsTts() { return true; }
    }
}
