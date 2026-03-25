package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;

import java.util.*;

/**
 * Topic scope enforcement guardrail — real implementation.
 *
 * <p>Ensures the LLM only answers questions within the defined topic scope.
 * Uses keyword scoring to determine whether the user's input is on-topic.
 *
 * <p>The scoring model:
 * <ul>
 *   <li>Input is tokenised (lowercased words)</li>
 *   <li>If any {@code deny} topic keyword appears: score = 1.0 → block</li>
 *   <li>If {@code allow} topics are set and no allowed keyword appears: block</li>
 *   <li>Otherwise: pass</li>
 * </ul>
 *
 * <pre>{@code
 *   app.guard(GuardRail.topicBoundary()
 *       .allow("customer service", "orders", "returns", "shipping")
 *       .deny("politics", "medical advice", "legal advice", "competitor"));
 * }</pre>
 */
public final class TopicBoundaryGuardRailImpl extends AbstractGuardRail {

    private final Set<String> allowedKeywords = new LinkedHashSet<>();
    private final Set<String> deniedKeywords  = new LinkedHashSet<>();

    public TopicBoundaryGuardRailImpl() {
        super(Action.BLOCK);
    }

    TopicBoundaryGuardRailImpl(Action action) {
        super(action);
    }

    /**
     * Adds allowed topic keywords. When set, input must contain at least
     * one allowed keyword or it will be blocked.
     */
    public TopicBoundaryGuardRailImpl allow(String... topics) {
        for (String t : topics) {
            Collections.addAll(allowedKeywords, t.toLowerCase(Locale.ROOT).split("[,\\s]+"));
        }
        return this;
    }

    /**
     * Adds denied topic keywords. Input containing any denied keyword is blocked.
     */
    public TopicBoundaryGuardRailImpl deny(String... topics) {
        for (String t : topics) {
            Collections.addAll(deniedKeywords, t.toLowerCase(Locale.ROOT).split("[,\\s]+"));
        }
        return this;
    }

    @Override public String   name()     { return "topic-boundary"; }
    @Override public GuardRail.Position position() { return GuardRail.Position.PRE_LLM; }

    @Override
    protected CheckResult checkInput(String input) {
        Set<String> words = tokenise(input);

        // Check denied topics first — any match blocks immediately
        for (String denied : deniedKeywords) {
            if (words.contains(denied)) {
                return CheckResult.block(
                    "Input contains denied topic keyword: '" + denied + "'", 1.0);
            }
        }

        // Check allowed topics — if configured, input must match at least one
        if (!allowedKeywords.isEmpty()) {
            boolean hasAllowed = allowedKeywords.stream().anyMatch(words::contains);
            if (!hasAllowed) {
                return CheckResult.block(
                    "Input does not match any allowed topic. " +
                    "This assistant handles: " + String.join(", ", allowedKeywords),
                    0.8);
            }
        }

        return CheckResult.pass();
    }

    private static Set<String> tokenise(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isBlank()) words.add(word);
        }
        return words;
    }
}
