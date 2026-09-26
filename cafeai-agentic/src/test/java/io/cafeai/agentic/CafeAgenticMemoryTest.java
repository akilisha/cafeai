package io.cafeai.agentic;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CafeAgenticMemoryTest {

    @Test
    void of_roundTripsThroughTheMemoryStrategy() {
        MemoryStrategy strategy = MemoryStrategy.inMemory();
        ChatMemoryProvider provider = CafeAgenticMemory.of(strategy);

        ChatMemory memory = provider.get("session-1");
        memory.add(UserMessage.from("hello"));

        var ctx = strategy.retrieve("session-1");
        assertThat(ctx).isNotNull();
        assertThat(ctx.messages()).extracting(m -> m.content()).contains("hello");
    }

    @Test
    void of_keepsMemoryIdsSeparate() {
        MemoryStrategy strategy = MemoryStrategy.inMemory();
        ChatMemoryProvider provider = CafeAgenticMemory.of(strategy);

        provider.get("a").add(UserMessage.from("from-a"));
        provider.get("b").add(UserMessage.from("from-b"));

        assertThat(strategy.retrieve("a").messages()).extracting(m -> m.content()).contains("from-a");
        assertThat(strategy.retrieve("a").messages()).extracting(m -> m.content()).doesNotContain("from-b");
        assertThat(strategy.retrieve("b").messages()).extracting(m -> m.content()).contains("from-b");
    }
}
