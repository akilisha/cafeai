package io.cafeai.core.guardrails;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Ethical, regulatory, and safety guardrails for CafeAI.
 *
 * <p>Guardrails are middleware -- composable, testable, replaceable.
 * They sit in the pipeline at {@code PRE_LLM}, {@code POST_LLM}, or both.
 * In regulated industries, guardrails are first-class architectural requirements.
 * CafeAI treats them as such (ADR-002).
 *
 * <p>Real implementations are provided by {@code cafeai-guardrails}. Without
 * that module on the classpath, every factory method returns a pass-through
 * stub and logs a one-time warning. Add the dependency to activate enforcement:
 *
 * <pre>{@code
 *   // build.gradle
 *   implementation 'io.cafeai:cafeai-guardrails'
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 *   app.guard(GuardRail.pii());
 *   app.guard(GuardRail.jailbreak());
 *   app.guard(GuardRail.regulatory().gdpr().hipaa());
 *   app.guard(GuardRail.topicBoundary()
 *       .allow("customer service", "orders")
 *       .deny("politics", "medical advice"));
 * }</pre>
 */
public interface GuardRail extends Middleware {

    /** Human-readable name used in observability traces and logs. */
    String name();

    /** Position in the pipeline -- before LLM, after LLM, or both. */
    Position position();

    /** What happens when this guardrail triggers. */
    Action action();

    // -- Factory Methods -------------------------------------------------------
    // Each method delegates to GuardRailProvider (cafeai-guardrails) when present.
    // Without cafeai-guardrails, returns a pass-through stub with a logged warning.

    /** PII detection and scrubbing -- pre and post LLM. */
    static GuardRail pii() {
        return provider().map(p -> p.pii())
            .orElseGet(() -> warnedStub("pii", Position.BOTH));
    }

    /** Adversarial prompt / jailbreak detection. */
    static GuardRail jailbreak() {
        return provider().map(p -> p.jailbreak())
            .orElseGet(() -> warnedStub("jailbreak", Position.PRE_LLM));
    }

    /** Data-sourced prompt injection detection -- checks user input and RAG documents. */
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

    /**
     * Regulatory compliance guardrail builder -- GDPR, HIPAA, FCRA, CCPA.
     *
     * <p>Returns a real implementation from {@code cafeai-guardrails} when
     * present, or a pass-through stub builder with a logged warning when absent.
     */
    static RegulatoryGuardRail regulatory() {
        GuardRail g = provider().map(p -> p.regulatory()).orElse(null);
        if (g instanceof RegulatoryGuardRail r) return r;
        warnOnce("regulatory");
        return new RegulatoryGuardRail();
    }

    /**
     * Topic scope enforcement builder.
     *
     * <p>Returns a real implementation from {@code cafeai-guardrails} when
     * present, or a pass-through stub builder with a logged warning when absent.
     */
    static TopicBoundaryGuardRail topicBoundary() {
        GuardRail g = provider().map(p -> p.topicBoundary()).orElse(null);
        if (g instanceof TopicBoundaryGuardRail t) return t;
        warnOnce("topic-boundary");
        return new TopicBoundaryGuardRail();
    }

    // -- Internal helpers ------------------------------------------------------

    private static java.util.Optional<io.cafeai.core.spi.GuardRailProvider> provider() {
        return java.util.ServiceLoader
            .load(io.cafeai.core.spi.GuardRailProvider.class)
            .findFirst();
    }

    private static GuardRail warnedStub(String name, Position position) {
        warnOnce(name);
        return StubGuardRail.of(name, position);
    }

    private static void warnOnce(String name) {
        org.slf4j.LoggerFactory.getLogger(GuardRail.class).warn(
            "GuardRail.{}() is a no-op -- add 'io.cafeai:cafeai-guardrails' " +
            "to your dependencies to activate real guardrail enforcement.", name);
    }

    // -- Enums -----------------------------------------------------------------

    enum Position { PRE_LLM, POST_LLM, BOTH }

    enum Action   { BLOCK, WARN, LOG }

    // -- Pass-through stub (used when cafeai-guardrails is absent) -------------

    /**
     * Pass-through stub returned when {@code cafeai-guardrails} is not on the
     * classpath. Does nothing -- all requests pass through unchecked.
     * A warning is logged once when the stub is created.
     */
    record StubGuardRail(String name, Position position) implements GuardRail {
        public static StubGuardRail of(String name, Position position) {
            return new StubGuardRail(name, position);
        }

        @Override public Action action() { return Action.BLOCK; }

        @Override
        public void handle(Request req, Response res, Next next) {
            next.run(); // pass-through -- cafeai-guardrails not on classpath
        }
    }

    // -- Stub builder classes (used when cafeai-guardrails is absent) ----------

    /**
     * Pass-through regulatory guardrail builder.
     * Fluent API is preserved so application code compiles regardless of
     * whether {@code cafeai-guardrails} is present. Enforcement is a no-op
     * without the module.
     *
     * <p>Non-final so {@code cafeai-guardrails} can extend this with real enforcement.
     */
    class RegulatoryGuardRail implements GuardRail {
        private String flags = "";

        public RegulatoryGuardRail gdpr()  { flags += "+gdpr";  return this; }
        public RegulatoryGuardRail hipaa() { flags += "+hipaa"; return this; }
        public RegulatoryGuardRail fcra()  { flags += "+fcra";  return this; }
        public RegulatoryGuardRail ccpa()  { flags += "+ccpa";  return this; }

        @Override public String   name()     { return "regulatory" + flags; }
        @Override public Position position() { return Position.BOTH; }
        @Override public Action   action()   { return Action.BLOCK; }

        @Override
        public void handle(Request req, Response res, Next next) {
            next.run(); // pass-through -- cafeai-guardrails not on classpath
        }
    }

    /**
     * Pass-through topic-boundary guardrail builder.
     * Fluent API is preserved so application code compiles regardless of
     * whether {@code cafeai-guardrails} is present. Enforcement is a no-op
     * without the module.
     *
     * <p>Non-final so {@code cafeai-guardrails} can extend this with real enforcement.
     */
    class TopicBoundaryGuardRail implements GuardRail {
        private final List<String> allowed = new ArrayList<>();
        private final List<String> denied  = new ArrayList<>();

        public TopicBoundaryGuardRail allow(String... topics) {
            allowed.addAll(List.of(topics)); return this;
        }

        public TopicBoundaryGuardRail deny(String... topics) {
            denied.addAll(List.of(topics)); return this;
        }

        @Override public String   name()     { return "topic-boundary"; }
        @Override public Position position() { return Position.PRE_LLM; }
        @Override public Action   action()   { return Action.BLOCK; }

        @Override
        public void handle(Request req, Response res, Next next) {
            next.run(); // pass-through -- cafeai-guardrails not on classpath
        }
    }
}
