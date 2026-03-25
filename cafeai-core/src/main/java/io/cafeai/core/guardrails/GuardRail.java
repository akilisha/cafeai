package io.cafeai.core.guardrails;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import java.util.ArrayList;
import java.util.List;
import io.cafeai.core.routing.Response;
/**
 * Ethical, regulatory, and safety guardrails for CafeAI.
 *
 * <p>Guardrails are middleware — composable, testable, replaceable.
 * They sit in the pipeline at {@code PRE_LLM}, {@code POST_LLM}, or both.
 * In regulated industries, guardrails are first-class architectural requirements.
 * CafeAI treats them as such (ADR-002).
 *
 * <pre>{@code
 *   app.guard(GuardRail.pii());
 *   app.guard(GuardRail.jailbreak());
 *   app.guard(GuardRail.regulatory().gdpr().hipaa());
 *   app.guard(GuardRail.topicBoundary()
 *       .allow("customer service", "orders")
 *       .deny("politics", "medical advice"));
 * }</pre>
 *
 * <p>Full implementations delivered in ROADMAP-07 Phase 7.
 */
public interface GuardRail extends Middleware {

    /** Human-readable name used in observability traces and logs. */
    String name();

    /** Position in the pipeline — before LLM, after LLM, or both. */
    Position position();

    /** What happens when this guardrail triggers. */
    Action action();

    // ── Factory Methods ───────────────────────────────────────────────────────

    /** PII detection and scrubbing — pre and post LLM. */
    static GuardRail pii() {
        return provider().map(p -> p.pii())
            .orElseGet(() -> warnedStub("pii", Position.BOTH));
    }

    /** Adversarial prompt / jailbreak detection. */
    static GuardRail jailbreak() {
        return provider().map(p -> p.jailbreak())
            .orElseGet(() -> warnedStub("jailbreak", Position.PRE_LLM));
    }

    /** Data-sourced prompt injection detection. */
    static GuardRail promptInjection() {
        return provider().map(p -> p.promptInjection())
            .orElseGet(() -> warnedStub("prompt-injection", Position.PRE_LLM));
    }

    /** Demographic bias detection in model outputs. */
    static GuardRail bias() {
        return provider().map(p -> p.bias())
            .orElseGet(() -> warnedStub("bias", Position.POST_LLM));
    }

    /** Factual grounding / hallucination scoring against RAG corpus. */
    static GuardRail hallucination() {
        return provider().map(p -> p.hallucination())
            .orElseGet(() -> warnedStub("hallucination", Position.POST_LLM));
    }

    /** Toxic and harmful content filtering. */
    static GuardRail toxicity() {
        return provider().map(p -> p.toxicity())
            .orElseGet(() -> warnedStub("toxicity", Position.BOTH));
    }

    /** Regulatory compliance guardrail builder. */
    static RegulatoryGuardRail regulatory() {
        GuardRail g = provider().map(p -> p.regulatory()).orElse(null);
        if (g instanceof RegulatoryGuardRail r) return r;
        return new RegulatoryGuardRail(); // stub fallback
    }

    /** Topic scope enforcement builder. */
    static TopicBoundaryGuardRail topicBoundary() {
        GuardRail g = provider().map(p -> p.topicBoundary()).orElse(null);
        if (g instanceof TopicBoundaryGuardRail t) return t;
        return new TopicBoundaryGuardRail(); // stub fallback
    }

    /** Loads the GuardRailProvider via ServiceLoader — cached per call. */
    private static java.util.Optional<io.cafeai.core.spi.GuardRailProvider> provider() {
        return java.util.ServiceLoader
            .load(io.cafeai.core.spi.GuardRailProvider.class)
            .findFirst();
    }

    /**
     * Creates a stub guardrail that logs a one-time warning that
     * {@code cafeai-guardrails} is not on the classpath.
     */
    private static GuardRail warnedStub(String name, Position position) {
        org.slf4j.LoggerFactory.getLogger(GuardRail.class).warn(
            "GuardRail.{}() is a no-op — add 'io.cafeai:cafeai-guardrails' " +
            "to your dependencies to activate real guardrail enforcement.", name);
        return StubGuardRail.of(name, position);
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    enum Position { PRE_LLM, POST_LLM, BOTH }

    enum Action { BLOCK, WARN, LOG }

    // ── Stub Implementation (replaced in ROADMAP-07 Phase 7) ─────────────────

    record StubGuardRail(String name, Position position) implements GuardRail {
        public static StubGuardRail of(String name, Position position) {
            return new StubGuardRail(name, position);
        }

        @Override public Action action() { return Action.BLOCK; }

        @Override
        public void handle(Request req,
                           Response res,
                           Next next) {
            // Stub: pass-through. Real implementations in ROADMAP-07 Phase 7.
            next.run();
        }
    }

    // ── Builder Classes ───────────────────────────────────────────────────────

    /** Regulatory compliance guardrail builder — GDPR, HIPAA, FCRA, CCPA. */
    final class RegulatoryGuardRail implements GuardRail {
        private String flags = "";

        public RegulatoryGuardRail gdpr()  { flags += "+gdpr";  return this; }
        public RegulatoryGuardRail hipaa() { flags += "+hipaa"; return this; }
        public RegulatoryGuardRail fcra()  { flags += "+fcra";  return this; }
        public RegulatoryGuardRail ccpa()  { flags += "+ccpa";  return this; }

        @Override public String name()     { return "regulatory" + flags; }
        @Override public Position position() { return Position.BOTH; }
        @Override public Action action()   { return Action.BLOCK; }

        @Override
        public void handle(Request req,
                           Response res,
                           Next next) {
            next.run(); // stub — full impl in ROADMAP-07 Phase 7
        }
    }

    /** Topic scope enforcement — keeps the AI on-topic. */
    final class TopicBoundaryGuardRail implements GuardRail {
        private final List<String> allowed = new ArrayList<>();
        private final List<String> denied  = new ArrayList<>();

        public TopicBoundaryGuardRail allow(String... topics) {
            allowed.addAll(List.of(topics));
            return this;
        }

        public TopicBoundaryGuardRail deny(String... topics) {
            denied.addAll(List.of(topics));
            return this;
        }

        @Override public String name()     { return "topic-boundary"; }
        @Override public Position position() { return Position.PRE_LLM; }
        @Override public Action action()   { return Action.BLOCK; }

        @Override
        public void handle(Request req,
                           Response res,
                           Next next) {
            next.run(); // stub — full impl in ROADMAP-07 Phase 7
        }
    }
}
