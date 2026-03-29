package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.spi.GuardRailProvider;

/**
 * ServiceLoader implementation of {@link GuardRailProvider}.
 *
 * <p>Returns real guardrail implementations backed by pattern matching,
 * NLP, and regulatory rule sets. Replaces the pass-through stubs in
 * {@code cafeai-core} when {@code cafeai-guardrails} is on the classpath.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.GuardRailProvider}
 */
public final class GuardRailProviderImpl implements GuardRailProvider {

    @Override public GuardRail pii()              { return new PiiGuardRail(); }
    @Override public GuardRail jailbreak()        { return new JailbreakGuardRail(); }
    @Override public GuardRail promptInjection()  { return new PromptInjectionGuardRail(); }
    @Override public GuardRail toxicity()         { return new ToxicityGuardRail(); }
    @Override public GuardRail topicBoundary()    { return new TopicBoundaryGuardRailImpl(); }
    @Override public GuardRail regulatory()       { return new RegulatoryGuardRailImpl(); }

    @Override public GuardRail bias() {
        // Bias detection requires a trained model -- stub with clear message until
        // a lightweight classifier is bundled in a future release.
        return GuardRail.StubGuardRail.of("bias", GuardRail.Position.POST_LLM);
    }

    @Override public GuardRail hallucination() {
        // Hallucination scoring requires the RAG corpus for grounding --
        // implemented in Phase 8 when the observability layer integrates
        // with the RAG pipeline. Stub until then.
        return GuardRail.StubGuardRail.of("hallucination", GuardRail.Position.POST_LLM);
    }
}
