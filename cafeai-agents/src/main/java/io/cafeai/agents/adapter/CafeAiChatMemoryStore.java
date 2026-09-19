package io.cafeai.agents.adapter;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
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
 *
 * <p>A plain system, user or assistant message is stored as text under its own role, as
 * {@code app.prompt(...).session(...)} stores it. Anything that is not just text (an assistant message
 * that asks for a tool to be called, a tool's result) is stored as LangChain4j's own JSON under the
 * role {@value #LANGCHAIN4J}: those messages have to come back exactly as they went in, or the model
 * is sent a tool call with no result and cannot continue. The plain call path ignores that role.
 */
public final class CafeAiChatMemoryStore implements ChatMemoryStore {

    /** The role under which a message that is not plain text is kept, its content being LangChain4j's JSON for it. */
    public static final String LANGCHAIN4J = "langchain4j";

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
            String content = m.content() == null ? "" : m.content();
            switch (m.role() == null ? "" : m.role().toLowerCase()) {
                case LANGCHAIN4J       -> out.add(ChatMessageDeserializer.messageFromJson(content));
                case "system"          -> out.add(SystemMessage.from(content));
                case "assistant", "ai" -> out.add(AiMessage.from(content));
                default                -> out.add(UserMessage.from(content));
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
            } else if (m instanceof AiMessage a && !a.hasToolExecutionRequests() && a.text() != null) {
                ctx.addMessage("assistant", a.text());
            } else if (m instanceof UserMessage u && u.hasSingleText()) {
                ctx.addMessage("user", u.singleText());
            } else {
                ctx.addMessage(LANGCHAIN4J, ChatMessageSerializer.messageToJson(m));
            }
        }
        strategy.store(String.valueOf(memoryId), ctx);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        strategy.evict(String.valueOf(memoryId));
    }
}
