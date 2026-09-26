package io.cafeai.agentic;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.cafeai.agentic.internal.CafeAgenticChatMemoryStore;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.memory.MemoryStrategy;

/**
 * A {@link ChatMemoryProvider} backed by a CafeAI {@link MemoryStrategy}, for use with an
 * agentic agent's own {@code @ChatMemoryProviderSupplier} method -- {@code AgenticScope} has no
 * persistence of its own, so per-agent conversation history still goes through this, the same
 * way {@code cafeai-aiservices} wires it for plain {@code AiServices}.
 */
public final class CafeAgenticMemory {

    /** Same config key as {@code cafeai-aiservices}' agent memory window, so one setting covers both. */
    public static final ConfigKey<Integer> MEMORY_WINDOW = ConfigKey.of(
        "cafeai.agent.memory.window", Integer.class, 20,
        "Number of messages an agentic agent's chat memory retains per memory id.");

    private CafeAgenticMemory() {}

    public static ChatMemoryProvider of(MemoryStrategy strategy) {
        CafeAgenticChatMemoryStore store = new CafeAgenticChatMemoryStore(strategy);
        int window = AppConfig.load().get(MEMORY_WINDOW);
        return memoryId -> MessageWindowChatMemory.builder()
            .id(memoryId)
            .maxMessages(window)
            .chatMemoryStore(store)
            .build();
    }
}
