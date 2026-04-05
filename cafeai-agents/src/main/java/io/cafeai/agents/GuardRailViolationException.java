package io.cafeai.agents;

/**
 * Thrown when a guardrail blocks an agent invocation.
 *
 * <p>The exception is thrown before the agent reasoning loop begins —
 * before the LLM is called. The message contains the guardrail name
 * and the reason for blocking.
 */
public final class GuardRailViolationException extends RuntimeException {

    public GuardRailViolationException(String message) {
        super(message);
    }
}
