package io.cafeai.core.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.CafeAI;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code .onThinking(...)} — reasoning tokens are a side channel: delivered to
 * the callback, never mixed into the answer or persisted.
 */
class ThinkingStreamTest {

    @Test
    @DisplayName("prompt: thinking goes to onThinking, answer tokens to the stream")
    void prompt_thinkingIsASeparateChannel() {
        var app = CafeAI.create();
        app.ai(new ReasoningMock(List.of("hmm ", "ok"), List.of("42")));

        var thinking = new StringBuilder();
        var answer   = new StringBuilder();
        app.prompt("meaning of life?").onThinking(thinking::append).stream(answer::append);

        assertThat(thinking.toString()).isEqualTo("hmm ok");
        assertThat(answer.toString()).isEqualTo("42");
    }

    @Test
    @DisplayName("prompt: thinking is not persisted to session memory")
    void prompt_thinkingNotPersisted() {
        var app = CafeAI.create();
        app.ai(new ReasoningMock(List.of("private reasoning"), List.of("42")));
        app.memory(MemoryStrategy.inMemory());

        app.prompt("q").session("s1").onThinking(t -> {}).stream(t -> {});

        var stored = app.local(io.cafeai.core.Locals.MEMORY_STRATEGY, MemoryStrategy.class)
                        .retrieve("s1");
        assertThat(stored.messages().get(1).content()).isEqualTo("42");
    }

    @Test
    @DisplayName("prompt: without onThinking, reasoning is dropped and the answer is unaffected")
    void prompt_noConsumer() {
        var app = CafeAI.create();
        app.ai(new ReasoningMock(List.of("hmm"), List.of("42")));

        var answer = new StringBuilder();
        app.prompt("q").stream(answer::append);

        assertThat(answer.toString()).isEqualTo("42");
    }

    @Test
    @DisplayName("vision: thinking goes to onThinking, answer tokens to the stream")
    void vision_thinkingIsASeparateChannel() {
        var app = CafeAI.create();
        app.ai(new ReasoningMock(List.of("looking ", "closely"), List.of("a cat")));

        var thinking = new StringBuilder();
        var answer   = new StringBuilder();
        app.vision("What is this?", new byte[]{1, 2, 3}, "image/png")
           .onThinking(thinking::append)
           .stream(answer::append);

        assertThat(thinking.toString()).isEqualTo("looking closely");
        assertThat(answer.toString()).isEqualTo("a cat");
    }

    @Test
    @DisplayName("Nvidia with... methods return copies and leave the original untouched")
    void nvidia_withMethods() {
        var base = Nvidia.of("moonshotai/kimi-k3");
        var tuned = base.withReasoningEffort("max")
                        .withTemperature(1.0)
                        .withMaxTokens(16384);

        assertThat(base.reasoningEffort()).isNull();
        assertThat(base.temperature()).isNull();
        assertThat(base.maxTokens()).isNull();
        assertThat(tuned.reasoningEffort()).isEqualTo("max");
        assertThat(tuned.temperature()).isEqualTo(1.0);
        assertThat(tuned.maxTokens()).isEqualTo(16384);
        assertThat(tuned.modelId()).isEqualTo("moonshotai/kimi-k3");
        assertThat(tuned.name()).isEqualTo("nvidia");
    }

    /** Vision-capable mock: emits thinking tokens first, then answer tokens. */
    private record ReasoningMock(List<String> thinking, List<String> answer)
            implements AiProvider, LangchainBridge.StreamingChatModelAccess {

        @Override public String       name()           { return "reasoning-mock"; }
        @Override public String       modelId()        { return "mock-reasoning"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override
        public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override
                public void chat(List<ChatMessage> messages, StreamingChatResponseHandler h) {
                    thinking.forEach(t -> h.onPartialThinking(new PartialThinking(t)));
                    answer.forEach(h::onPartialResponse);
                    h.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(String.join("", answer)))
                        .build());
                }
            };
        }
    }
}
