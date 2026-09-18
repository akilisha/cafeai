package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.TextNormalizer;

import java.util.*;

/**
 * Real topic boundary guardrail implementation.
 *
 * <p>Implements {@link GuardRail.TopicBoundaryGuardRail}, the type
 * {@link GuardRail#topicBoundary()} returns, so the fluent {@code .allow()/.deny()} API works.
 */
public final class TopicBoundaryGuardRailImpl extends AbstractGuardRail implements GuardRail.TopicBoundaryGuardRail {

    private final Set<String> allowedKeywords = new LinkedHashSet<>();
    private final Set<String> deniedKeywords  = new LinkedHashSet<>();

    public TopicBoundaryGuardRailImpl() {
        super(Action.BLOCK);
    }

    @Override
    public GuardRail.TopicBoundaryGuardRail allow(String... topics) {
        for (String t : topics) {
            Collections.addAll(allowedKeywords, keywords(t));
        }
        return this;
    }

    @Override
    public GuardRail.TopicBoundaryGuardRail deny(String... topics) {
        for (String t : topics) {
            Collections.addAll(deniedKeywords, keywords(t));
        }
        return this;
    }

    private static String[] keywords(String topic) {
        return TextNormalizer.normalize(topic).split("[^\\p{L}\\p{N}]+");
    }

    @Override public String   name()     { return "topic-boundary"; }
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    protected CheckResult screenInput(String input) {
        Set<String> words = tokenise(input);

        // Denied keywords block immediately
        for (String denied : deniedKeywords) {
            if (words.contains(denied)) {
                return CheckResult.block("Input contains a denied topic");
            }
        }

        // If an allow list is set, the input must mention at least one allowed keyword
        if (!allowedKeywords.isEmpty() && allowedKeywords.stream().noneMatch(words::contains)) {
            return CheckResult.block("Input is outside the allowed topics");
        }
        return CheckResult.pass();
    }

    private static Set<String> tokenise(String text) {
        Set<String> words = new HashSet<>();
        for (String w : TextNormalizer.normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank()) words.add(w);
        }
        return words;
    }
}
