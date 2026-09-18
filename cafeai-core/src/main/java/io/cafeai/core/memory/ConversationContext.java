package io.cafeai.core.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * <p>The history is stored whole. What is sent to the model, and whether old messages are folded
 * into a {@link #summary()}, is decided by the {@link HistoryPolicy} in force.
 *
 * <p>Jackson-serializable via {@link JsonCreator} constructor -- all fields
 * annotated so {@link com.fasterxml.jackson.databind.ObjectMapper} can
 * round-trip instances to/from JSON without a no-arg constructor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)   // sessions stored by an earlier version carry fields since removed
public final class ConversationContext {

    private final String        sessionId;
    private final List<Message> messages;
    private final Instant       createdAt;
    private volatile Instant    lastAccessedAt;
    private volatile int        totalTokens;
    private volatile String     summary;

    public ConversationContext(String sessionId) {
        this.sessionId      = sessionId;
        this.messages       = new ArrayList<>();
        this.createdAt      = Instant.now();
        this.lastAccessedAt = Instant.now();
        this.totalTokens    = 0;
    }

    /** Jackson deserialization constructor. */
    @JsonCreator
    ConversationContext(
            @JsonProperty("sessionId")      String        sessionId,
            @JsonProperty("messages")       List<Message> messages,
            @JsonProperty("createdAt")      Instant       createdAt,
            @JsonProperty("lastAccessedAt") Instant       lastAccessedAt,
            @JsonProperty("totalTokens")    int           totalTokens,
            @JsonProperty("summary")        String        summary) {
        this.sessionId      = sessionId;
        this.messages       = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.createdAt      = createdAt  != null ? createdAt  : Instant.now();
        this.lastAccessedAt = lastAccessedAt != null ? lastAccessedAt : Instant.now();
        this.totalTokens    = totalTokens;
        this.summary        = summary;
    }

    public synchronized void addMessage(String role, String content) {
        messages.add(new Message(role, content, Instant.now()));
        lastAccessedAt = Instant.now();
    }

    /** Adds to the running total of tokens the session has used, as reported by the provider. */
    public synchronized void addTokens(int count) {
        this.totalTokens += count;
    }

    /**
     * Replaces the oldest {@code olderCount} messages with {@code summary}, which takes the place
     * of any earlier summary. Used by {@link HistoryPolicy#summarise()}.
     */
    public synchronized void compact(int olderCount, String summary) {
        if (olderCount < 0 || olderCount > messages.size()) {
            throw new IllegalArgumentException(
                "Cannot fold " + olderCount + " messages of " + messages.size());
        }
        messages.subList(0, olderCount).clear();
        this.summary = summary;
    }

    @JsonProperty public String           sessionId()      { return sessionId; }
    @JsonProperty public synchronized List<Message> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
    @JsonProperty public Instant          createdAt()      { return createdAt; }
    @JsonProperty public Instant          lastAccessedAt() { return lastAccessedAt; }
    @JsonProperty public int              totalTokens()    { return totalTokens; }
    /** A summary of the messages folded away by {@link HistoryPolicy#summarise()}, or {@code null}. */
    @JsonProperty public String           summary()        { return summary; }

    /** An individual message in the conversation. */
    public record Message(
            @JsonProperty("role")      String  role,
            @JsonProperty("content")   String  content,
            @JsonProperty("timestamp") Instant timestamp) {}
}
