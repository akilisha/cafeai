package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Real topic boundary guardrail implementation.
 *
 * <p>Extends {@link GuardRail.TopicBoundaryGuardRail} so the {@code instanceof}
 * check in {@link GuardRail#topicBoundary()} resolves correctly, and the
 * fluent {@code .allow()/.deny()} API continues to work on the returned instance.
 */
public final class TopicBoundaryGuardRailImpl extends GuardRail.TopicBoundaryGuardRail {

    private static final Logger log = LoggerFactory.getLogger(TopicBoundaryGuardRailImpl.class);

    private final Set<String> allowedKeywords = new LinkedHashSet<>();
    private final Set<String> deniedKeywords  = new LinkedHashSet<>();

    public TopicBoundaryGuardRailImpl() {}

    @Override
    public GuardRail.TopicBoundaryGuardRail allow(String... topics) {
        for (String t : topics) {
            Collections.addAll(allowedKeywords,
                t.toLowerCase(Locale.ROOT).split("[,\\s]+"));
        }
        return this;
    }

    @Override
    public GuardRail.TopicBoundaryGuardRail deny(String... topics) {
        for (String t : topics) {
            Collections.addAll(deniedKeywords,
                t.toLowerCase(Locale.ROOT).split("[,\\s]+"));
        }
        return this;
    }

    @Override public String   name()     { return "topic-boundary"; }
    @Override public Position position() { return Position.PRE_LLM; }
    @Override public Action   action()   { return Action.BLOCK; }

    @Override
    public void handle(Request req, Response res, Next next) {
        String input = extractText(req);
        if (input == null || input.isBlank()) { next.run(); return; }

        Set<String> words = tokenise(input);

        // Denied keywords block immediately
        for (String denied : deniedKeywords) {
            if (words.contains(denied)) {
                log.warn("Topic boundary: denied keyword '{}' in input", denied);
                res.status(400).json(java.util.Map.of(
                    "error",     "Request blocked by guardrail",
                    "guardrail", name(),
                    "reason",    "Input contains denied topic: '" + denied + "'"));
                return;
            }
        }

        // If allow list set, input must match at least one keyword
        if (!allowedKeywords.isEmpty()) {
            boolean hasAllowed = allowedKeywords.stream().anyMatch(words::contains);
            if (!hasAllowed) {
                log.warn("Topic boundary: no allowed keywords found in input");
                res.status(400).json(java.util.Map.of(
                    "error",     "Request blocked by guardrail",
                    "guardrail", name(),
                    "reason",    "I can only help with Helios connection pooling questions."));
                return;
            }
        }

        next.run();
    }

    private static String extractText(Request req) {
        String t = req.bodyText();
        if (t != null && !t.isBlank()) return t;
        Object b = req.body("message");
        return b != null ? b.toString() : null;
    }

    private static Set<String> tokenise(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isBlank()) words.add(w);
        }
        return words;
    }
}
