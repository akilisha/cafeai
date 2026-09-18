package io.cafeai.core.guardrails;

/**
 * Thrown by a {@link GuardRail} factory ({@code GuardRail.pii()}, {@code .jailbreak()}, ...) when
 * {@code cafeai-guardrails} is not on the classpath.
 *
 * <p>This used to return a guardrail that passed everything through and logged a warning. That is
 * the wrong failure for a safety control: the code compiles, the app runs, the guardrail is
 * registered, and it protects nothing — indistinguishable from protection until the day it matters.
 * A missing security dependency now fails loudly, the same way a missing {@code cafeai-rag} or
 * {@code cafeai-memory} already does.
 *
 * <p>Guardrails that need no module still work without it: {@code GuardRail.moderation(model)},
 * {@code GuardRail.promptLeak(systemPrompt)}, and any {@link GuardRail} you implement yourself.
 */
public final class GuardRailModuleNotFoundException extends RuntimeException {
    public GuardRailModuleNotFoundException(String message) {
        super(message);
    }
}
