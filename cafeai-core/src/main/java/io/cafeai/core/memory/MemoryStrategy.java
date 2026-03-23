package io.cafeai.core.memory;

/**
 * Tiered context memory strategy for CafeAI.
 *
 * <p>The memory hierarchy mirrors how hardware thinks:
 * <pre>
 *   Hot    →  JVM Heap          (active turn, current conversation)
 *   Warm   →  Java FFM/Chronicle (recent sessions — SSD-backed, off-heap)
 *   Cool   →  Redis / Memcached  (distributed — the escape valve)
 *   Cold   →  Vector DB          (semantic long-term memory, RAG corpus)
 * </pre>
 *
 * <p>This is the incremental memory adoption ladder.
 * Start with inMemory(). Graduate to mapped() when you outgrow the heap.
 * Only reach for redis() when you genuinely need distributed state.
 * Don't pay the network tax until the problem demands it.
 *
 * <pre>{@code
 *   // Rung 1: Prototype — zero deps, zero config
 *   app.memory(MemoryStrategy.inMemory());
 *
 *   // Rung 2: SSD-backed via Java FFM MemorySegment
 *   //         Survives restarts. OS manages the page cache brilliantly.
 *   app.memory(MemoryStrategy.mapped());
 *   app.memory(MemoryStrategy.mapped(Path.of("/data/cafeai/sessions")));
 *
 *   // Rung 3: Chronicle Map — off-heap, designed for this pattern
 *   app.memory(MemoryStrategy.chronicle());
 *   app.memory(MemoryStrategy.chronicle(ChronicleConfig.defaults()));
 *
 *   // Rung 4: Redis — distributed, multi-instance
 *   app.memory(MemoryStrategy.redis(RedisConfig.of("localhost", 6379)));
 *
 *   // Rung 5: Memcached — distributed escape valve
 *   app.memory(MemoryStrategy.memcached(MemcachedConfig.of("localhost", 11211)));
 *
 *   // Rung 6: Hybrid — warm SSD tier + cold Redis tier
 *   app.memory(MemoryStrategy.hybrid()
 *       .warm(MemoryStrategy.mapped())
 *       .cold(MemoryStrategy.redis(config)));
 * }</pre>
 */
public interface MemoryStrategy {

    /**
     * Rung 1: In-JVM HashMap.
     * Zero dependencies. Zero configuration. Perfect for prototyping.
     * Does not survive restarts. Single node only.
     */
    static MemoryStrategy inMemory() {
        return new InMemoryStrategy();
    }

    /**
     * Rung 2: SSD-backed via Java FFM MemorySegment.
     * Uses {@code java.lang.foreign.MemorySegment} for off-heap,
     * memory-mapped file storage. The OS page cache does the heavy lifting.
     * Survives restarts. Single node. No network overhead.
     *
     * <p>This is where Java 21's FFM API earns its place in CafeAI.
     * Same API surface used for native ML library bindings — skills transfer.
     */
    static MemoryStrategy mapped() {
        return new MappedMemoryStrategy();
    }

    /**
     * Rung 3: Chronicle Map off-heap storage.
     * Designed specifically for this use case — high-throughput,
     * off-heap, persisted key-value storage without GC pressure.
     */
    static MemoryStrategy chronicle() {
        return new ChronicleMemoryStrategy();
    }

    /**
     * Rung 4: Redis-backed distributed memory.
     * The escape valve. Reach for this when you genuinely need
     * state shared across multiple application instances.
     */
    static MemoryStrategy redis(RedisConfig config) {
        return new RedisMemoryStrategy(config);
    }

    /**
     * Rung 5: Memcached-backed distributed memory.
     * Alternative distributed escape valve — simpler than Redis,
     * excellent for pure session cache use cases.
     */
    static MemoryStrategy memcached(MemcachedConfig config) {
        return new MemcachedMemoryStrategy(config);
    }

    /**
     * Rung 6: Hybrid tiered memory.
     * Warm tier (SSD-backed) for recent sessions.
     * Cold tier (Redis/Memcached) for distributed overflow.
     * Promotes and demotes entries across tiers automatically.
     */
    static HybridMemoryStrategy hybrid() {
        return new HybridMemoryStrategy();
    }

    // ── Core Memory Operations ───────────────────────────────────────────────

    void store(String sessionId, ConversationContext context);

    ConversationContext retrieve(String sessionId);

    void evict(String sessionId);

    boolean exists(String sessionId);
}
