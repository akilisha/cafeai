package io.cafeai.core.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the conversation history for a single session.
 *
 * <p>Stored and retrieved by {@link MemoryStrategy} implementations.
 * Thread-safe: all mutating methods are synchronized.
 *
 * <p>Context window trimming: when {@code maxTokens > 0} and
 * {@code totalTokens} exceeds that limit, the oldest messages are
 * pruned first — always preserving at least the most recent exchange.
 *
 * <p>Jackson-serializable via {@link JsonCreator} constructor — all fields
 * annotated so {@link com.fasterxml.jackson.databind.ObjectMapper} can
 * round-trip instances to/from JSON without a no-arg constructor.
 */
public final class ConversationContext {

    public static final int DEFAULT_MAX_TOKENS = 0;

    private final String        sessionId;
    private final List<Message> messages;
    private final Instant       createdAt;
    private volatile Instant    lastAccessedAt;
    private volatile int        totalTokens;
    private final int           maxTokens;

    public ConversationContext(String sessionId) {
        this(sessionId, DEFAULT_MAX_TOKENS);
    }

    public ConversationContext(String sessionId, int maxTokens) {
        this.sessionId      = sessionId;
        this.messages       = new ArrayList<>();
        this.createdAt      = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.totalTokens    = 0;
        this.maxTokens      = maxTokens;
    }

    /** Jackson deserialization constructor. */
    @JsonCreator
    ConversationContext(
            @JsonProperty("sessionId")      String        sessionId,
            @JsonProperty("messages")       List<Message> messages,
            @JsonProperty("createdAt")      Instant       createdAt,
            @JsonProperty("lastAccessedAt") Instant       lastAccessedAt,
            @JsonProperty("totalTokens")    int           totalTokens,
            @JsonProperty("maxTokens")      int           maxTokens) {
        this.sessionId      = sessionId;
        this.messages       = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.createdAt      = createdAt  != null ? createdAt  : Instant.now();
        this.lastAccessedAt = lastAccessedAt != null ? lastAccessedAt : Instant.now();
        this.totalTokens    = totalTokens;
        this.maxTokens      = maxTokens;
    }

    public synchronized void addMessage(String role, String content) {
        messages.add(new Message(role, content, Instant.now()));
        lastAccessedAt = Instant.now();
    }

    /**
     * Adds to the running token count and trims when over the limit.
     * After trimming, resets totalTokens to 0 — the count is advisory
     * (used only to trigger pruning) not a billing-precise counter.
     */
    public synchronized void addTokens(int count) {
        this.totalTokens += count;
        if (maxTokens > 0 && totalTokens > maxTokens) {
            trimToWindow();
        }
    }

    /**
     * Removes the oldest messages until only the last 2 remain,
     * then resets the token counter so the trim guard resets cleanly.
     */
    private void trimToWindow() {
        while (messages.size() > 2) {
            messages.remove(0);
        }
        // Reset to 0 after trim — next actual token additions will re-accumulate
        totalTokens = 0;
    }

    @JsonProperty public String           sessionId()      { return sessionId; }
    @JsonProperty public synchronized List<Message> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
    @JsonProperty public Instant          createdAt()      { return createdAt; }
    @JsonProperty public Instant          lastAccessedAt() { return lastAccessedAt; }
    @JsonProperty public int              totalTokens()    { return totalTokens; }
    @JsonProperty public int              maxTokens()      { return maxTokens; }

    /** An individual message in the conversation. */
    public record Message(
            @JsonProperty("role")      String  role,
            @JsonProperty("content")   String  content,
            @JsonProperty("timestamp") Instant timestamp) {}
}
