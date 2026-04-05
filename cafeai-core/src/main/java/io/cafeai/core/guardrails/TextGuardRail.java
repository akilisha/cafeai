package io.cafeai.core.guardrails;

/**
 * Optional extension for {@link GuardRail} implementations that support
 * direct text checking without an HTTP request context.
 *
 * <p>Implemented by guardrails that can evaluate plain text input —
 * useful for agent guardrail pre-screening before the reasoning loop begins.
 *
 * <p>Guardrails that do <em>not</em> implement this interface are skipped
 * in agent contexts (they remain active in the HTTP middleware pipeline).
 *
 * <pre>{@code
 * // Agent guardrail checking (in AgentRegistry)
 * for (GuardRail rail : config.guardRails()) {
 *     if (rail instanceof TextGuardRail tgr) {
 *         TextGuardRail.Result result = tgr.checkText(input);
 *         if (result.isBlocked()) throw new GuardRailViolationException(...);
 *     }
 * }
 * }</pre>
 */
public interface TextGuardRail {

    /**
     * Checks the given text against this guardrail.
     *
     * @param text the text to evaluate
     * @return a {@link Result} indicating whether the text passes or is blocked
     */
    Result checkText(String text);

    /**
     * Result of a text guardrail check.
     *
     * @param blocked {@code true} if the guardrail blocks this text
     * @param reason  human-readable reason when blocked, {@code null} otherwise
     */
    record Result(boolean blocked, String reason) {

        /** Convenience factory — text passes. */
        public static Result pass() {
            return new Result(false, null);
        }

        /** Convenience factory — text is blocked with the given reason. */
        public static Result block(String reason) {
            return new Result(true, reason);
        }

        /** Returns {@code true} if the text should be blocked. */
        public boolean isBlocked() { return blocked; }
    }
}
