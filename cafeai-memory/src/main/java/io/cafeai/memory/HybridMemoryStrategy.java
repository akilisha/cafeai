package io.cafeai.memory;

import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rung 5: Hybrid tiered memory — warm (SSD) + cold (Redis).
 *
 * <p>Hot sessions stay in the warm tier (fast, local, off-heap).
 * Idle sessions are demoted to the cold tier (distributed, persistent) after
 * a configurable idle TTL. Reads promote sessions back to warm automatically.
 *
 * <p>This is the production default for single-instance deployments that need
 * both performance and persistence, without the operational overhead of
 * managing distributed state for every session.
 *
 * <pre>{@code
 *   app.memory(MemoryStrategy.hybrid()
 *       .warm(MemoryStrategy.mapped())
 *       .cold(MemoryStrategy.redis(redisConfig))
 *       .demoteAfter(Duration.ofMinutes(30)));
 * }</pre>
 *
 * <p>Access patterns:
 * <ul>
 *   <li>{@code store()} — writes to warm tier; cold tier updated async (fire-and-forget)</li>
 *   <li>{@code retrieve()} — checks warm first; on miss, reads from cold and promotes to warm</li>
 *   <li>{@code evict()} — removes from both tiers</li>
 * </ul>
 */
public final class HybridMemoryStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryStrategy.class);

    /** Default: demote to cold after 30 minutes of inactivity. */
    public static final Duration DEFAULT_DEMOTE_AFTER = Duration.ofMinutes(30);

    private MemoryStrategy warm;
    private MemoryStrategy cold;
    private Duration       demoteAfter;

    // Tracks last-access time per session for demotion decisions
    private final ConcurrentHashMap<String, Instant> lastAccess = new ConcurrentHashMap<>();

    public HybridMemoryStrategy() {
        this.demoteAfter = DEFAULT_DEMOTE_AFTER;
    }

    /**
     * Sets the warm tier. Typically {@code MemoryStrategy.mapped()} for SSD-backed storage.
     * Required.
     */
    public HybridMemoryStrategy warm(MemoryStrategy strategy) {
        this.warm = strategy;
        return this;
    }

    /**
     * Sets the cold tier. Typically {@code MemoryStrategy.redis(config)} for distributed storage.
     * Required.
     */
    public HybridMemoryStrategy cold(MemoryStrategy strategy) {
        this.cold = strategy;
        return this;
    }

    /**
     * How long a session can be idle in the warm tier before being demoted to cold.
     * Default: 30 minutes.
     */
    public HybridMemoryStrategy demoteAfter(Duration duration) {
        this.demoteAfter = duration;
        return this;
    }

    @Override
    public void store(String sessionId, ConversationContext context) {
        requireConfigured();
        lastAccess.put(sessionId, Instant.now());
        // Write to warm tier — the fast path
        warm.store(sessionId, context);
        // Also write to cold tier for durability — ensures crash recovery and
        // multi-instance visibility. This is synchronous intentionally:
        // on virtual threads, the cost is a network round-trip that parks the thread.
        try {
            cold.store(sessionId, context);
        } catch (Exception e) {
            // Cold-tier failure is non-fatal — warm tier still has the session
            log.warn("Cold-tier write failed for session {} (warm tier intact): {}",
                sessionId, e.getMessage());
        }
    }

    @Override
    public ConversationContext retrieve(String sessionId) {
        requireConfigured();
        // Try warm tier first (fast path)
        if (warm.exists(sessionId)) {
            ConversationContext ctx = warm.retrieve(sessionId);
            if (ctx != null) {
                lastAccess.put(sessionId, Instant.now());
                return ctx;
            }
        }
        // Cold-tier miss promotion: read from cold, write back to warm
        ConversationContext ctx = cold.retrieve(sessionId);
        if (ctx != null) {
            log.debug("Promoting session {} from cold to warm tier", sessionId);
            warm.store(sessionId, ctx);
            lastAccess.put(sessionId, Instant.now());
        }
        return ctx;
    }

    @Override
    public void evict(String sessionId) {
        requireConfigured();
        lastAccess.remove(sessionId);
        warm.evict(sessionId);
        try {
            cold.evict(sessionId);
        } catch (Exception e) {
            log.warn("Cold-tier evict failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean exists(String sessionId) {
        requireConfigured();
        return warm.exists(sessionId) || cold.exists(sessionId);
    }

    /**
     * Scans the warm tier and demotes sessions idle longer than {@code demoteAfter}.
     *
     * <p>This is a maintenance operation — call it periodically from a background task.
     * In a future release this will be triggered automatically on a configurable schedule.
     *
     * @return the number of sessions demoted
     */
    public int demoteIdleSessions() {
        requireConfigured();
        Instant cutoff = Instant.now().minus(demoteAfter);
        int[] demoted = {0};
        lastAccess.forEach((sessionId, lastAccessTime) -> {
            if (lastAccessTime.isBefore(cutoff) && warm.exists(sessionId)) {
                try {
                    ConversationContext ctx = warm.retrieve(sessionId);
                    if (ctx != null) {
                        cold.store(sessionId, ctx);
                        warm.evict(sessionId);
                        demoted[0]++;
                        log.debug("Demoted idle session {} to cold tier", sessionId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to demote session {}: {}", sessionId, e.getMessage());
                }
            }
        });
        if (demoted[0] > 0) {
            log.info("Demoted {} idle sessions to cold tier", demoted[0]);
        }
        return demoted[0];
    }

    private void requireConfigured() {
        if (warm == null || cold == null) {
            throw new IllegalStateException(
                "HybridMemoryStrategy requires both warm and cold tiers. " +
                "Call .warm(strategy) and .cold(strategy) before using.");
        }
    }
}
