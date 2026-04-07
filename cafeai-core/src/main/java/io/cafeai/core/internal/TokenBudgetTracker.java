package io.cafeai.core.internal;

import io.cafeai.core.ai.TokenBudget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Tracks token consumption in a rolling one-minute window and enforces
 * a {@link TokenBudget}.
 *
 * <p>When the budget would be exceeded, {@link #waitIfNeeded()} parks
 * the current thread until the window resets — no spin-waiting, no
 * {@code Thread.sleep()} in application code.
 *
 * <p>Thread-safe. Multiple virtual threads calling concurrently are safe.
 *
 * <p>Package-private — internal to {@code CafeAIApp}.
 */
final class TokenBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetTracker.class);
    private static final long   WINDOW_NS = 60_000_000_000L; // 1 minute in nanoseconds

    private final TokenBudget budget;
    private final AtomicLong  windowTokens  = new AtomicLong(0);
    private volatile long     windowStartNs = System.nanoTime();

    TokenBudgetTracker(TokenBudget budget) {
        this.budget = budget;
    }

    /**
     * Records tokens consumed by a completed LLM call and checks whether
     * the next call should be delayed.
     *
     * <p>Called after each LLM call with the actual token count reported
     * by the provider.
     *
     * @param tokensUsed tokens consumed by the just-completed call
     */
    void recordUsage(long tokensUsed) {
        if (budget.isUnlimited()) return;
        resetWindowIfExpired();
        long total = windowTokens.addAndGet(tokensUsed);
        if (total >= budget.tokensPerMinute()) {
            log.debug("TokenBudget: window at {}/{} TPM — next call will wait for reset",
                total, budget.tokensPerMinute());
        }
    }

    /**
     * Parks the calling thread until the budget window resets if the current
     * window is at or above the limit.
     *
     * <p>Called before each LLM call. If the budget has not been exceeded,
     * returns immediately. Otherwise parks until the window resets.
     */
    void waitIfNeeded() {
        if (budget.isUnlimited()) return;
        resetWindowIfExpired();

        long current = windowTokens.get();
        if (current < budget.tokensPerMinute()) return;

        // Window is full — calculate how long until it resets
        long nowNs      = System.nanoTime();
        long windowEndNs = windowStartNs + WINDOW_NS;
        long waitNs     = windowEndNs - nowNs;

        if (waitNs > 0) {
            log.info("TokenBudget: {}/{} TPM consumed — pausing {}ms for window reset",
                current, budget.tokensPerMinute(), waitNs / 1_000_000);
            LockSupport.parkNanos(waitNs);
        }

        // Reset the window after waiting
        resetWindow();
    }

    /**
     * Returns the number of tokens consumed in the current window.
     * Useful for observability and logging.
     */
    long currentWindowTokens() {
        resetWindowIfExpired();
        return windowTokens.get();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void resetWindowIfExpired() {
        long nowNs = System.nanoTime();
        if (nowNs - windowStartNs >= WINDOW_NS) {
            resetWindow();
        }
    }

    private synchronized void resetWindow() {
        // Double-check inside synchronized to avoid multiple resets
        long nowNs = System.nanoTime();
        if (nowNs - windowStartNs >= WINDOW_NS) {
            windowTokens.set(0);
            windowStartNs = nowNs;
            log.debug("TokenBudget: window reset — 0/{} TPM", budget.tokensPerMinute());
        }
    }
}
