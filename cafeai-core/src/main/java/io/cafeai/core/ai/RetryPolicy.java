package io.cafeai.core.ai;

import java.time.Duration;

/**
 * Configures retry behaviour for LLM calls that fail due to rate limits
 * or transient errors.
 *
 * <p>Register via {@code app.retry(RetryPolicy.onRateLimit())} to have
 * the framework catch rate limit exceptions, wait, and retry automatically.
 * Application code never sees a rate limit exception unless all attempts
 * are exhausted.
 *
 * <pre>{@code
 *   app.retry(RetryPolicy.onRateLimit()
 *       .maxAttempts(3)
 *       .backoff(Duration.ofSeconds(5)));
 * }</pre>
 *
 * <h2>Backoff strategy</h2>
 * <p>Each retry waits {@code backoff * attemptNumber} seconds
 * (linear backoff). With {@code backoff=5s} and {@code maxAttempts=3}:
 * <pre>
 *   Attempt 1: fails → wait 5s
 *   Attempt 2: fails → wait 10s
 *   Attempt 3: fails → throw RateLimitExceededException
 * </pre>
 */
public final class RetryPolicy {

    private final int      maxAttempts;
    private final Duration backoff;
    private final boolean  retriesOnRateLimit;

    private RetryPolicy(int maxAttempts, Duration backoff, boolean onRateLimit) {
        this.maxAttempts        = maxAttempts;
        this.backoff            = backoff;
        this.retriesOnRateLimit = onRateLimit;
    }

    /**
     * Creates a retry policy that activates on rate limit exceptions.
     *
     * <p>Defaults: 3 attempts, 5 second linear backoff.
     */
    public static RetryPolicy onRateLimit() {
        return new RetryPolicy(3, Duration.ofSeconds(5), true);
    }

    /**
     * Sets the maximum number of attempts (including the first attempt).
     *
     * @param maxAttempts total attempts before giving up; minimum 1
     */
    public RetryPolicy maxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
        }
        return new RetryPolicy(maxAttempts, this.backoff, this.retriesOnRateLimit);
    }

    /**
     * Sets the base backoff duration between retries.
     *
     * <p>Actual wait time is {@code backoff * attemptNumber} (linear backoff).
     *
     * @param backoff base wait duration; must be positive
     */
    public RetryPolicy backoff(Duration backoff) {
        if (backoff == null || backoff.isNegative() || backoff.isZero()) {
            throw new IllegalArgumentException("backoff must be a positive Duration");
        }
        return new RetryPolicy(this.maxAttempts, backoff, this.retriesOnRateLimit);
    }

    public int      maxAttempts() { return maxAttempts; }
    public Duration backoff()     { return backoff; }
    public boolean  retriesOnRateLimit() { return retriesOnRateLimit; }

    @Override
    public String toString() {
        return "RetryPolicy(maxAttempts=" + maxAttempts +
               ", backoff=" + backoff.toSeconds() + "s, retriesOnRateLimit=" + retriesOnRateLimit + ")";
    }

    /**
     * Thrown when all retry attempts are exhausted.
     */
    public static final class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
