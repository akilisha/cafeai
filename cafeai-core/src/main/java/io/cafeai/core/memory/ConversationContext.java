package io.cafeai.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the conversation history for a single session.
 * Stored and retrieved by {@link MemoryStrategy} implementations.
 *
 * <p>Full FFM/off-heap serialization format defined in ROADMAP-07 Phase 3.
 */
public final class ConversationContext {

    private final String sessionId;
    private final List<Message> messages;
    private final Instant createdAt;
    private Instant lastAccessedAt;
    private int totalTokens;

    public ConversationContext(String sessionId) {
        this.sessionId      = sessionId;
        this.messages       = new ArrayList<>();
        this.createdAt      = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.totalTokens    = 0;
    }

    public void addMessage(String role, String content) {
        messages.add(new Message(role, content, Instant.now()));
        lastAccessedAt = Instant.now();
    }

    public String sessionId()          { return sessionId; }
    public List<Message> messages()    { return Collections.unmodifiableList(messages); }
    public Instant createdAt()         { return createdAt; }
    public Instant lastAccessedAt()    { return lastAccessedAt; }
    public int totalTokens()           { return totalTokens; }
    public void addTokens(int count)   { this.totalTokens += count; }

    /** An individual message in the conversation. */
    public record Message(String role, String content, Instant timestamp) {}
}
