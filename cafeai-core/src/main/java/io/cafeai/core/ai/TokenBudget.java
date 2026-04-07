package io.cafeai.core.ai;

/**
 * Configures a token spend limit for LLM calls.
 *
 * <p>Register via {@code app.budget(TokenBudget.perMinute(30_000))} to
 * prevent runaway token usage. When a budget is set, {@code CafeAIApp}
 * tracks tokens consumed in a rolling one-minute window and pauses
 * automatically before calls that would exceed the limit.
 *
 * <p>This eliminates manual {@code Thread.sleep()} calls in application
 * code for rate-limit management:
 *
 * <pre>{@code
 *   // Before — application code doing framework work
 *   Thread.sleep(15_000);  // hope the rate limit has reset
 *
 *   // After — framework handles it
 *   app.budget(TokenBudget.perMinute(30_000));
 * }</pre>
 *
 * <h2>Choosing a budget</h2>
 * <pre>
 *   OpenAI free tier:      TokenBudget.perMinute(30_000)
 *   OpenAI Tier 1:         TokenBudget.perMinute(500_000)
 *   OpenAI Tier 2+:        TokenBudget.perMinute(2_000_000)
 *   No limit (default):    TokenBudget.unlimited()
 * </pre>
 *
 * <p>CafeAI uses token counts reported by the LLM provider after each call.
 * For the first call in a window, no pre-call estimate is possible —
 * the framework tracks actual usage and throttles subsequent calls.
 */
public record TokenBudget(long tokensPerMinute, boolean isUnlimited) {

    /**
     * Creates a budget limiting token consumption to the given amount per minute.
     *
     * @param tokensPerMinute maximum tokens to consume in any 60-second window
     */
    public static TokenBudget perMinute(long tokensPerMinute) {
        if (tokensPerMinute <= 0) {
            throw new IllegalArgumentException(
                "tokensPerMinute must be positive, got: " + tokensPerMinute);
        }
        return new TokenBudget(tokensPerMinute, false);
    }

    /**
     * Creates an unlimited budget — no throttling applied.
     * This is the default when no budget is registered.
     */
    public static TokenBudget unlimited() {
        return new TokenBudget(Long.MAX_VALUE, true);
    }

    @Override
    public String toString() {
        return isUnlimited
            ? "TokenBudget(unlimited)"
            : "TokenBudget(" + tokensPerMinute + " TPM)";
    }
}
