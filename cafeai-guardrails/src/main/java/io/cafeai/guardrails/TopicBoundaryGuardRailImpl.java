package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.TextNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Real topic boundary guardrail implementation.
 *
 * <p>Implements {@link GuardRail.TopicBoundaryGuardRail}, the type
 * {@link GuardRail#topicBoundary()} returns, so the fluent {@code .allow()/.deny()} API works.
 *
 * <p>A topic is a word or a phrase; a comma inside one argument separates topics
 * ({@code allow("orders, shipping")}). Text is {@linkplain TextNormalizer#normalize normalised}
 * first, so case, accents and look-alike characters do not hide a topic.
 * <ul>
 *   <li><strong>Denied topic</strong> — blocks an input containing the topic's words
 *       <em>together and in order</em>. {@code deny("medical advice")} blocks "I need medical
 *       advice" and not "any advice on shipping".</li>
 *   <li><strong>Allowed topics</strong> — when any are set, the input must contain <em>all</em> the
 *       words of at least one of them (in any order). {@code allow("customer service")} needs both
 *       words; {@code allow("orders")} needs that word.</li>
 * </ul>
 * This is matching on words, not on meaning: an input about a denied topic that never uses its
 * words is not caught, and an input that uses an allowed topic's words in passing is let through.
 * For a boundary that judges meaning, add {@code GuardRail.moderation(model)}.
 */
public final class TopicBoundaryGuardRailImpl extends AbstractGuardRail implements GuardRail.TopicBoundaryGuardRail {

    private final List<List<String>> allowed = new ArrayList<>();
    private final List<List<String>> denied  = new ArrayList<>();

    public TopicBoundaryGuardRailImpl() {
        super(Action.BLOCK);
    }

    @Override
    public GuardRail.TopicBoundaryGuardRail allow(String... topics) {
        for (String t : topics) addTopics(allowed, t);
        return this;
    }

    @Override
    public GuardRail.TopicBoundaryGuardRail deny(String... topics) {
        for (String t : topics) addTopics(denied, t);
        return this;
    }

    @Override public String   name()     { return "topic-boundary"; }
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    protected CheckResult screenInput(String input) {
        List<String> words = words(input);

        for (List<String> topic : denied) {
            if (containsPhrase(words, topic)) {
                return CheckResult.block("Input contains a denied topic");
            }
        }

        if (!allowed.isEmpty()) {
            Set<String> present = new HashSet<>(words);
            if (allowed.stream().noneMatch(present::containsAll)) {
                return CheckResult.block("Input is outside the allowed topics");
            }
        }
        return CheckResult.pass();
    }

    private static void addTopics(List<List<String>> into, String topics) {
        if (topics == null) return;
        for (String part : topics.split(",")) {
            List<String> words = words(part);
            if (!words.isEmpty()) into.add(words);
        }
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String w : TextNormalizer.normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) words.add(w);
        }
        return words;
    }

    /** Whether {@code phrase}'s words appear consecutively, in order, in {@code words}. */
    private static boolean containsPhrase(List<String> words, List<String> phrase) {
        for (int i = 0; i + phrase.size() <= words.size(); i++) {
            if (words.subList(i, i + phrase.size()).equals(phrase)) return true;
        }
        return false;
    }
}
