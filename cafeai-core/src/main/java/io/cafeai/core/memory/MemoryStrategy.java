package io.cafeai.core.memory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered context memory strategy for CafeAI.
 *
 * <p>The memory hierarchy mirrors hardware — start at the cheapest tier
 * and escalate only when the problem demands it (ADR-003):
 * <pre>
 *   Rung 1 → inMemory()    JVM HashMap — prototype, zero deps
 *   Rung 2 → mapped()      SSD-backed via Java FFM MemorySegment
 *   Rung 3 → chronicle()   Chronicle Map off-heap
 *   Rung 4 → redis(cfg)    Distributed — the escape valve
 *   Rung 5 → memcached()   Distributed alternative
 *   Rung 6 → hybrid()      Warm SSD + cold Redis
 * </pre>
 *
 * <p>Full implementations delivered in ROADMAP-07 Phase 3 (cafeai-memory module).
 */
public interface MemoryStrategy {

    /**
     * Rung 1: In-JVM HashMap. Zero dependencies. Zero configuration.
     * Does not survive restarts. Single node only.
     */
    static MemoryStrategy inMemory() {
        return new InMemoryStrategy();
    }

    /**
     * Rung 2: SSD-backed via Java FFM {@code MemorySegment}.
     * Off-heap, OS page-cache managed, crash-recoverable.
     * The production single-node default. No network overhead.
     */
    static MemoryStrategy mapped() {
        return new MappedMemoryStrategy();
    }

    /**
     * Rung 3: Chronicle Map off-heap key-value store.
     * Designed for this exact pattern — high-throughput, persisted, off-heap.
     */
    static MemoryStrategy chronicle() {
        return new ChronicleMemoryStrategy();
    }

    /**
     * Rung 4: Redis-backed distributed memory.
     * The escape valve. Reach for this when you need state shared across
     * multiple application instances.
     *
     * @param config Redis connection configuration
     */
    static MemoryStrategy redis(RedisConfig config) {
        return new RedisMemoryStrategy(config);
    }

    /**
     * Rung 6: Hybrid tiered memory — warm SSD tier + cold Redis tier.
     * Promotes and demotes entries across tiers automatically.
     */
    static HybridMemoryStrategy hybrid() {
        return new HybridMemoryStrategy();
    }

    // ── Core Contract ─────────────────────────────────────────────────────────

    void store(String sessionId, ConversationContext context);
    ConversationContext retrieve(String sessionId);
    void evict(String sessionId);
    boolean exists(String sessionId);

    // ── Stub Implementations (replaced in cafeai-memory — ROADMAP-07 Phase 3) ─

    record InMemoryStrategy() implements MemoryStrategy {
        private static final ConcurrentHashMap<String, ConversationContext>
            store = new ConcurrentHashMap<>();

        @Override public void store(String id, ConversationContext ctx) { store.put(id, ctx); }
        @Override public ConversationContext retrieve(String id)         { return store.get(id); }
        @Override public void evict(String id)                           { store.remove(id); }
        @Override public boolean exists(String id)                       { return store.containsKey(id); }
    }

    /** Stub — full FFM implementation in cafeai-memory module. */
    record MappedMemoryStrategy() implements MemoryStrategy {
        @Override public void store(String id, ConversationContext ctx) {}
        @Override public ConversationContext retrieve(String id)         { return null; }
        @Override public void evict(String id)                           {}
        @Override public boolean exists(String id)                       { return false; }
    }

    /** Stub — full Chronicle Map implementation in cafeai-memory module. */
    record ChronicleMemoryStrategy() implements MemoryStrategy {
        @Override public void store(String id, ConversationContext ctx) {}
        @Override public ConversationContext retrieve(String id)         { return null; }
        @Override public void evict(String id)                           {}
        @Override public boolean exists(String id)                       { return false; }
    }

    /** Stub — full Lettuce/Redis implementation in cafeai-memory module. */
    record RedisMemoryStrategy(RedisConfig config) implements MemoryStrategy {
        @Override public void store(String id, ConversationContext ctx) {}
        @Override public ConversationContext retrieve(String id)         { return null; }
        @Override public void evict(String id)                           {}
        @Override public boolean exists(String id)                       { return false; }
    }

    /** Hybrid builder — full implementation in cafeai-memory module. */
    final class HybridMemoryStrategy implements MemoryStrategy {
        private MemoryStrategy warm;
        private MemoryStrategy cold;

        public HybridMemoryStrategy warm(MemoryStrategy strategy) { this.warm = strategy; return this; }
        public HybridMemoryStrategy cold(MemoryStrategy strategy) { this.cold = strategy; return this; }

        @Override public void store(String id, ConversationContext ctx) {
            if (warm != null) warm.store(id, ctx);
        }
        @Override public ConversationContext retrieve(String id) {
            if (warm != null && warm.exists(id)) return warm.retrieve(id);
            if (cold != null) return cold.retrieve(id);
            return null;
        }
        @Override public void evict(String id) {
            if (warm != null) warm.evict(id);
            if (cold != null) cold.evict(id);
        }
        @Override public boolean exists(String id) {
            return (warm != null && warm.exists(id)) || (cold != null && cold.exists(id));
        }
    }
}
