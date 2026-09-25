package io.cafeai.core.session;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A single HTTP session's server-side data -- the opaque cookie ID plus an
 * arbitrary key/value attribute bag (auth state, cart contents, flash
 * messages, ...).
 *
 * <p><strong>Disambiguation:</strong> this is the classic Express-style HTTP
 * session (cookie + server-side store), set by {@code Middleware.session(...)}
 * and read via {@code req.session()}. It is unrelated to
 * {@link io.cafeai.core.memory.MemoryStrategy}'s AI-conversation "session"
 * (LLM chat history keyed by a {@code sessionId}, typically from an
 * {@code X-Session-Id} header) and unrelated to
 * {@link io.cafeai.core.routing.WsSession} (a WebSocket connection handle).
 * See {@code MemoryStrategy} for LLM chat history.
 *
 * <pre>{@code
 *   app.filter(Middleware.session(SessionStore.sqlite()));
 *
 *   app.post("/login", (req, res, next) -> {
 *       req.session().set("userId", user.id());
 *       res.json(Map.of("status", "ok"));
 *   });
 *
 *   app.post("/logout", (req, res, next) -> {
 *       req.session().invalidate();
 *       res.json(Map.of("status", "ok"));
 *   });
 * }</pre>
 */
public final class Session {

    private final String id;
    private final Map<String, Object> attributes;
    private final Instant createdAt;
    private volatile Instant lastAccessedAt;
    private volatile boolean invalidated = false;
    private Runnable invalidationHook;

    /** A new, unsaved session with the given opaque ID and no attributes. */
    public Session(String id) {
        this(id, new HashMap<>(), Instant.now(), Instant.now());
    }

    /** A session rehydrated from a {@link SessionStore}. */
    public Session(String id, Map<String, Object> attributes, Instant createdAt, Instant lastAccessedAt) {
        this.id = id;
        this.attributes = new HashMap<>(attributes);
        this.createdAt = createdAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    /** The opaque session ID carried in the session cookie. */
    public String id() { return id; }

    /** {@code true} if this session has never been persisted by its store. */
    public boolean isNew() { return createdAt.equals(lastAccessedAt) && attributes.isEmpty(); }

    public Instant createdAt() { return createdAt; }

    public Instant lastAccessedAt() { return lastAccessedAt; }

    /**
     * Refreshes {@link #lastAccessedAt()} to now.
     *
     * <p>Public only because {@code Middleware.session(...)}'s implementation lives in
     * {@code io.cafeai.core.internal}, a different package -- Java package-private
     * cannot cross package boundaries without JPMS (same reason as
     * {@code BuiltInMiddleware}). Not intended for application code.
     */
    public void touch() { this.lastAccessedAt = Instant.now(); }

    /**
     * Binds the hook {@link #invalidate()} runs. Wired internally by
     * {@code Middleware.session(...)} so invalidation can clear the response
     * cookie synchronously. See {@link #touch()} for why this is public.
     */
    public void bindInvalidationHook(Runnable hook) { this.invalidationHook = hook; }

    /** Returns an attribute, or {@code null} if unset. Mirrors Express {@code req.session[key]}. */
    public Object get(String key) { return attributes.get(key); }

    /** Typed retrieval of an attribute. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = attributes.get(key);
        return value == null ? null : type.cast(value);
    }

    /** Sets an attribute. Returns {@code this} for chaining. */
    public Session set(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    /** Removes an attribute. No-op if absent. Returns {@code this} for chaining. */
    public Session remove(String key) {
        attributes.remove(key);
        return this;
    }

    /** An unmodifiable snapshot of this session's attributes, for {@link SessionStore#save(Session)}. */
    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Marks this session for destruction. {@code Middleware.session(...)} destroys it in
     * the backing store and clears the session cookie on the current response
     * <strong>immediately</strong> -- not after the handler returns, since a response
     * already sent cannot have its headers changed. Mirrors Express
     * {@code req.session.destroy()}.
     */
    public void invalidate() {
        invalidated = true;
        if (invalidationHook != null) invalidationHook.run();
    }

    public boolean isInvalidated() { return invalidated; }
}
