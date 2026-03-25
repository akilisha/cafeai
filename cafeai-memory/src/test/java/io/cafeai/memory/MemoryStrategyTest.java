package io.cafeai.memory;

import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-07 Phase 3: tiered memory strategies.
 *
 * <p>Covers:
 * <ul>
 *   <li>Rung 1: {@code inMemory()} — fully functional</li>
 *   <li>Rung 2: {@code mapped()} — SSD-backed FFM</li>
 *   <li>Rung 5: {@code hybrid()} — warm+cold tiering with demotion</li>
 *   <li>Context window trimming in {@link ConversationContext}</li>
 * </ul>
 *
 * <p>Redis integration tests ({@code redis()}) require a live Redis instance
 * and are in {@code RedisMemoryStrategyIntegrationTest} using Testcontainers.
 */
class MemoryStrategyTest {

    // ── Rung 1: inMemory ─────────────────────────────────────────────────────

    @Test
    @DisplayName("inMemory: store and retrieve roundtrip")
    void inMemory_storeAndRetrieve() {
        var strategy = MemoryStrategy.inMemory();
        var ctx = ctx("sess-1");
        ctx.addMessage("user", "Hello");
        ctx.addMessage("assistant", "Hi there!");

        strategy.store("sess-1", ctx);
        var retrieved = strategy.retrieve("sess-1");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.sessionId()).isEqualTo("sess-1");
        assertThat(retrieved.messages()).hasSize(2);
        assertThat(retrieved.messages().get(0).role()).isEqualTo("user");
        assertThat(retrieved.messages().get(0).content()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("inMemory: retrieve returns null for unknown session")
    void inMemory_retrieveUnknown_returnsNull() {
        assertThat(MemoryStrategy.inMemory().retrieve("ghost")).isNull();
    }

    @Test
    @DisplayName("inMemory: exists returns true/false correctly")
    void inMemory_exists() {
        var strategy = MemoryStrategy.inMemory();
        strategy.store("sess-a", ctx("sess-a"));
        assertThat(strategy.exists("sess-a")).isTrue();
        assertThat(strategy.exists("sess-b")).isFalse();
    }

    @Test
    @DisplayName("inMemory: evict removes session")
    void inMemory_evict() {
        var strategy = MemoryStrategy.inMemory();
        strategy.store("sess-x", ctx("sess-x"));
        strategy.evict("sess-x");
        assertThat(strategy.exists("sess-x")).isFalse();
        assertThat(strategy.retrieve("sess-x")).isNull();
    }

    @Test
    @DisplayName("inMemory: evict on non-existent session is a no-op")
    void inMemory_evictNonExistent_noOp() {
        assertThatCode(() -> MemoryStrategy.inMemory().evict("nobody"))
            .doesNotThrowAnyException();
    }

    // ── Rung 2: mapped (FFM) ─────────────────────────────────────────────────

    @Test
    @DisplayName("mapped: store and retrieve roundtrip")
    void mapped_storeAndRetrieve(@TempDir Path tempDir) {
        var strategy = new MappedMemoryStrategy(tempDir);
        var ctx = ctx("sess-mapped");
        ctx.addMessage("user", "Test message");

        strategy.store("sess-mapped", ctx);
        var retrieved = strategy.retrieve("sess-mapped");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.sessionId()).isEqualTo("sess-mapped");
        assertThat(retrieved.messages()).hasSize(1);
        assertThat(retrieved.messages().get(0).content()).isEqualTo("Test message");

        strategy.close();
    }

    @Test
    @DisplayName("mapped: exists and evict work correctly")
    void mapped_existsAndEvict(@TempDir Path tempDir) {
        var strategy = new MappedMemoryStrategy(tempDir);
        strategy.store("sess-m", ctx("sess-m"));

        assertThat(strategy.exists("sess-m")).isTrue();
        strategy.evict("sess-m");
        assertThat(strategy.exists("sess-m")).isFalse();
        assertThat(strategy.retrieve("sess-m")).isNull();

        strategy.close();
    }

    @Test
    @DisplayName("mapped: crash recovery — sessions survive re-initialisation")
    void mapped_crashRecovery(@TempDir Path tempDir) {
        // Simulate a write then re-initialise (simulates JVM restart)
        var strategy1 = new MappedMemoryStrategy(tempDir);
        var ctx = ctx("persistent-session");
        ctx.addMessage("user", "I was here before the restart");
        strategy1.store("persistent-session", ctx);
        strategy1.close();

        // Create a new strategy instance pointing to the same dir
        var strategy2 = new MappedMemoryStrategy(tempDir);
        var recovered = strategy2.retrieve("persistent-session");

        assertThat(recovered).isNotNull();
        assertThat(recovered.messages()).hasSize(1);
        assertThat(recovered.messages().get(0).content())
            .isEqualTo("I was here before the restart");

        strategy2.close();
    }

    @Test
    @DisplayName("mapped: session IDs with special chars are sanitised for filenames")
    void mapped_sessionIdSanitised(@TempDir Path tempDir) {
        var strategy = new MappedMemoryStrategy(tempDir);
        // Slash, colon, space — all should be sanitised
        String sessionId = "user/42:active session";
        strategy.store(sessionId, ctx(sessionId));

        assertThat(strategy.exists(sessionId)).isTrue();
        assertThat(strategy.retrieve(sessionId)).isNotNull();

        strategy.close();
    }

    @Test
    @DisplayName("mapped: can be constructed without error when module is present")
    void mapped_modulePresent_noError() {
        var strategy = new MappedMemoryStrategy();
        assertThat(strategy).isNotNull();
        strategy.close();
    }

    // ── Rung 5: hybrid ───────────────────────────────────────────────────────

    @Test
    @DisplayName("hybrid: store writes to both warm and cold tiers")
    void hybrid_storeWritesToBothTiers() {
        var warm = MemoryStrategy.inMemory();
        var cold = MemoryStrategy.inMemory();
        var hybrid = hybrid(warm, cold);

        var ctx = ctx("sess-h");
        ctx.addMessage("user", "hello");
        hybrid.store("sess-h", ctx);

        assertThat(warm.exists("sess-h")).isTrue();
        assertThat(cold.exists("sess-h")).isTrue();
    }

    @Test
    @DisplayName("hybrid: retrieve hits warm tier first")
    void hybrid_retrieveHitsWarmFirst() {
        var warm = MemoryStrategy.inMemory();
        var cold = MemoryStrategy.inMemory();
        var hybrid = hybrid(warm, cold);

        // Store only in warm — cold doesn't have it
        warm.store("sess-warm-only", ctx("sess-warm-only"));

        var retrieved = hybrid.retrieve("sess-warm-only");
        assertThat(retrieved).isNotNull();
    }

    @Test
    @DisplayName("hybrid: cold-miss promotes session to warm")
    void hybrid_coldMissPromotesToWarm() {
        var warm = MemoryStrategy.inMemory();
        var cold = MemoryStrategy.inMemory();
        var hybrid = hybrid(warm, cold);

        // Store only in cold — warm doesn't have it
        cold.store("sess-cold-only", ctx("sess-cold-only"));

        // hybrid.retrieve should find it in cold and promote to warm
        var retrieved = hybrid.retrieve("sess-cold-only");
        assertThat(retrieved).isNotNull();
        assertThat(warm.exists("sess-cold-only"))
            .as("Session should be promoted to warm tier after cold hit")
            .isTrue();
    }

    @Test
    @DisplayName("hybrid: evict removes from both tiers")
    void hybrid_evictsBothTiers() {
        var warm = MemoryStrategy.inMemory();
        var cold = MemoryStrategy.inMemory();
        var hybrid = hybrid(warm, cold);

        hybrid.store("sess-ev", ctx("sess-ev"));
        hybrid.evict("sess-ev");

        assertThat(warm.exists("sess-ev")).isFalse();
        assertThat(cold.exists("sess-ev")).isFalse();
    }

    @Test
    @DisplayName("hybrid: demoteIdleSessions moves idle sessions to cold")
    void hybrid_demoteIdleSessions() throws InterruptedException {
        var warm = MemoryStrategy.inMemory();
        var cold = MemoryStrategy.inMemory();
        var hybrid = new HybridMemoryStrategy()
            .warm(warm)
            .cold(cold)
            .demoteAfter(Duration.ofMillis(100));  // 100ms window

        hybrid.store("idle-sess", ctx("idle-sess"));

        // Wait comfortably past the demotion window
        Thread.sleep(250);

        int demoted = hybrid.demoteIdleSessions();
        assertThat(demoted).isEqualTo(1);
        assertThat(warm.exists("idle-sess"))
            .as("Session should be evicted from warm tier after demotion")
            .isFalse();
        assertThat(cold.exists("idle-sess"))
            .as("Session should still exist in cold tier after demotion")
            .isTrue();
    }

    @Test
    @DisplayName("hybrid: unconfigured strategy throws IllegalStateException")
    void hybrid_unconfigured_throws() {
        var hybrid = new HybridMemoryStrategy();
        assertThatIllegalStateException()
            .isThrownBy(() -> hybrid.store("x", ctx("x")))
            .withMessageContaining("warm")
            .withMessageContaining("cold");
    }

    // ── Context Window Trimming ───────────────────────────────────────────────

    @Test
    @DisplayName("ConversationContext: trimming removes oldest messages when over limit")
    void context_trimming_removesOldest() {
        // maxTokens = 50 — third addTokens(20) pushes to 60, triggering trim
        var ctx = new ConversationContext("trim-test", 50);
        ctx.addMessage("user",      "Message 1 — this is the oldest");
        ctx.addMessage("assistant", "Response 1");
        ctx.addMessage("user",      "Message 2");
        ctx.addMessage("assistant", "Response 2");

        ctx.addTokens(20); // 20 — no trim
        ctx.addTokens(20); // 40 — no trim
        ctx.addTokens(20); // 60 — over limit, trim fires

        // After trim, token counter resets to 0 and messages are pruned to 2
        assertThat(ctx.totalTokens()).isLessThanOrEqualTo(50);
        assertThat(ctx.messages().size()).isEqualTo(2);
        // The last 2 messages are preserved — oldest are gone
        assertThat(ctx.messages().get(1).content()).isEqualTo("Response 2");
    }

    @Test
    @DisplayName("ConversationContext: trimming always preserves last 2 messages")
    void context_trimming_preservesLastTwo() {
        var ctx = new ConversationContext("preserve-test", 1); // tiny limit
        ctx.addMessage("user",      "First");
        ctx.addMessage("assistant", "Second");
        ctx.addMessage("user",      "Third");
        ctx.addMessage("assistant", "Fourth — most recent");

        ctx.addTokens(100_000); // massive — forces trim

        // Must always keep at least 2
        assertThat(ctx.messages().size()).isGreaterThanOrEqualTo(2);
        // Most recent messages should be preserved
        var msgs = ctx.messages();
        assertThat(msgs.get(msgs.size() - 1).content())
            .isEqualTo("Fourth — most recent");
    }

    @Test
    @DisplayName("ConversationContext: no trimming when maxTokens = 0 (unlimited)")
    void context_noTrimming_whenUnlimited() {
        var ctx = new ConversationContext("unlimited", 0);
        for (int i = 0; i < 100; i++) {
            ctx.addMessage("user", "Message " + i);
        }
        ctx.addTokens(1_000_000);
        assertThat(ctx.messages()).hasSize(100);
    }

    @Test
    @DisplayName("ConversationContext: messages() is a defensive copy")
    void context_messages_isDefensiveCopy() {
        var ctx = ctx("copy-test");
        ctx.addMessage("user", "hello");
        var snapshot = ctx.messages();
        ctx.addMessage("user", "added after snapshot");
        // snapshot should not reflect the new message
        assertThat(snapshot).hasSize(1);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ConversationContext ctx(String sessionId) {
        return new ConversationContext(sessionId);
    }

    private static MemoryStrategy hybrid(MemoryStrategy warm, MemoryStrategy cold) {
        return MemoryStrategy.hybrid()
            .warm(warm)
            .cold(cold)
            .build();
    }
}
