package io.cafeai.core.memory;

import io.cafeai.core.spi.MemoryStrategyProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered context memory strategy for CafeAI.
 *
 * <p>The memory hierarchy mirrors hardware — start cheap and escalate only
 * when the problem demands it (ADR-003):
 *
 * <pre>
 *   Rung 1 → inMemory()    JVM HashMap — zero deps, prototype/dev
 *   Rung 2 → mapped()      SSD-backed via Java FFM MemorySegment   (cafeai-memory)
 *   Rung 3 → chronicle()   Chronicle Map off-heap                  (cafeai-memory, stub)
 *   Rung 4 → redis(cfg)    Distributed via Lettuce                 (cafeai-memory)
 *   Rung 5 → hybrid()      Warm SSD + cold Redis                   (cafeai-memory)
 * </pre>
 *
 * <p>Rungs 2–5 require {@code io.cafeai:cafeai-memory} on the classpath.
 * Adding the JAR is the only configuration needed — no code changes required.
 *
 * <p>Rung 1 ({@code inMemory()}) is fully functional with zero dependencies.
 * It is appropriate for development, testing, and single-instance production
 * when crash recovery is not required.
 */
public interface MemoryStrategy {

    // ── Core Contract ─────────────────────────────────────────────────────────

    /** Stores or updates a session. Thread-safe. */
    void store(String sessionId, ConversationContext context);

    /**
     * Retrieves a session. Returns {@code null} if the session does not exist.
     * Thread-safe.
     */
    ConversationContext retrieve(String sessionId);

    /** Removes a session. No-op if the session does not exist. */
    void evict(String sessionId);

    /** Returns {@code true} if a session exists. */
    boolean exists(String sessionId);

    // ── Rung 1: In-JVM HashMap (fully functional, zero deps) ─────────────────

    /**
     * Rung 1: In-JVM HashMap. Zero dependencies. Zero configuration.
     * Sessions do not survive restarts. Single-node only.
     *
     * <p>Appropriate for: development, testing, single-request prototypes.
     */
    static MemoryStrategy inMemory() {
        return new InMemoryStrategy();
    }

    // ── Rungs 2–5: Require cafeai-memory module ───────────────────────────────

    /**
     * Rung 2: SSD-backed off-heap memory via Java FFM {@code MemorySegment}.
     *
     * <p>Sessions are stored as JSON files in the default directory
     * ({@code ${java.io.tmpdir}/cafeai/sessions/}) and memory-mapped for
     * fast access. The OS page cache handles hot sessions automatically.
     * Sessions survive JVM restarts — crash recovery out of the box.
     *
     * <p>Requires {@code io.cafeai:cafeai-memory} on the classpath.
     *
     * @throws MemoryModuleNotFoundException if {@code cafeai-memory} is absent
     */
    static MemoryStrategy mapped() {
        return loadProvider().mapped();
    }

    /**
     * Rung 2: SSD-backed off-heap memory with a custom storage directory.
     *
     * @throws MemoryModuleNotFoundException if {@code cafeai-memory} is absent
     */
    static MemoryStrategy mapped(Path storageDir) {
        return loadProvider().mapped(storageDir);
    }

    /**
     * Rung 3: Chronicle Map off-heap key-value store.
     * Stub — full implementation in a future release.
     */
    static MemoryStrategy chronicle() {
        throw new UnsupportedOperationException(
            "Chronicle Map memory strategy not yet implemented. " +
            "Use MemoryStrategy.mapped() for SSD-backed off-heap storage, " +
            "or MemoryStrategy.redis(config) for distributed storage.");
    }

    /**
     * Rung 4: Redis-backed distributed memory via Lettuce.
     *
     * <p>The distributed escape valve. Sessions are shared across all application
     * instances and survive deployments. TTL is enforced at the Redis level.
     *
     * <p>Requires {@code io.cafeai:cafeai-memory} on the classpath.
     *
     * @throws MemoryModuleNotFoundException if {@code cafeai-memory} is absent
     */
    static MemoryStrategy redis(RedisConfig config) {
        return loadProvider().redis(config);
    }

    /**
     * Rung 5: Hybrid tiered memory — warm (SSD) + cold (Redis).
     *
     * <p>Hot sessions stay local and fast. Idle sessions are demoted to Redis.
     * Reads promote sessions back to warm automatically.
     *
     * <pre>{@code
     *   app.memory(MemoryStrategy.hybrid()
     *       .warm(MemoryStrategy.mapped())
     *       .cold(MemoryStrategy.redis(redisConfig))
     *       .demoteAfter(Duration.ofMinutes(30))
     *       .build());
     * }</pre>
     *
     * <p>Requires {@code io.cafeai:cafeai-memory} on the classpath.
     *
     * @throws MemoryModuleNotFoundException if {@code cafeai-memory} is absent
     */
    static HybridBuilder hybrid() {
        return loadProvider().hybrid();
    }

    // ── ServiceLoader discovery ───────────────────────────────────────────────

    private static MemoryStrategyProvider loadProvider() {
        return ServiceLoader.load(MemoryStrategyProvider.class)
            .findFirst()
            .orElseThrow(() -> new MemoryModuleNotFoundException(
                "Memory rungs 2–5 require the cafeai-memory module. " +
                "Add the following dependency:\n\n" +
                "  Gradle: implementation 'io.cafeai:cafeai-memory'\n" +
                "  Maven:  <artifactId>cafeai-memory</artifactId>\n\n" +
                "For development, use MemoryStrategy.inMemory() (zero dependencies)."));
    }

    // ── Nested Types ──────────────────────────────────────────────────────────

    /** Fluent builder for hybrid warm+cold strategies. */
    interface HybridBuilder {
        HybridBuilder warm(MemoryStrategy strategy);
        HybridBuilder cold(MemoryStrategy strategy);
        HybridBuilder demoteAfter(Duration duration);
        MemoryStrategy build();
    }

    /**
     * Thrown when a memory rung requiring {@code cafeai-memory} is requested
     * but that module is not on the classpath.
     */
    class MemoryModuleNotFoundException extends RuntimeException {
        public MemoryModuleNotFoundException(String message) {
            super(message);
        }
    }

    // ── Rung 1 Implementation (fully functional, zero deps) ───────────────────

    final class InMemoryStrategy implements MemoryStrategy {
        // Instance-scoped — each InMemoryStrategy has its own isolated store.
        // Previously static, which caused test cross-contamination.
        private final ConcurrentHashMap<String, ConversationContext>
            store = new ConcurrentHashMap<>();

        @Override public void store(String id, ConversationContext ctx) { store.put(id, ctx); }
        @Override public ConversationContext retrieve(String id)         { return store.get(id); }
        @Override public void evict(String id)                           { store.remove(id); }
        @Override public boolean exists(String id)                       { return store.containsKey(id); }
    }
}
