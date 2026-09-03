package io.cafeai.agents.adapter;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges a CafeAI {@link MemoryStrategy} to LangChain4j's {@link ChatMemoryStore},
 * keyed on the session id. This is how {@code .memory(MemoryStrategy.redis(...))}
 * on an agent gives its conversation history the same persistence as
 * {@code app.prompt(...).session(...)}.
 */
public final class CafeAiChatMemoryStore implements ChatMemoryStore {

    private final MemoryStrategy strategy;

    public CafeAiChatMemoryStore(MemoryStrategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        ConversationContext ctx = strategy.retrieve(String.valueOf(memoryId));
        if (ctx == null) {
            return List.of();
        }
        List<ChatMessage> out = new ArrayList<>();
        for (ConversationContext.Message m : ctx.messages()) {
            switch (m.role() == null ? "" : m.role().toLowerCase()) {
                case "system"          -> out.add(SystemMessage.from(m.content()));
                case "assistant", "ai" -> out.add(AiMessage.from(m.content()));
                default                -> out.add(UserMessage.from(m.content()));
            }
        }
        return out;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        ConversationContext ctx = new ConversationContext(String.valueOf(memoryId));
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage s) {
                ctx.addMessage("system", s.text());
            } else if (m instanceof AiMessage a) {
                ctx.addMessage("assistant", a.text());
            } else if (m instanceof UserMessage u) {
                ctx.addMessage("user", u.singleText());
            }
        }
        strategy.store(String.valueOf(memoryId), ctx);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        strategy.evict(String.valueOf(memoryId));
    }
}
