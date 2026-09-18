package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.spi.GuardRailProvider;

/**
 * ServiceLoader implementation of {@link GuardRailProvider}.
 *
 * <p>Returns real guardrail implementations backed by pattern matching
 * and regulatory rule sets. Backs the {@code GuardRail} factories in {@code cafeai-core}
 * when {@code cafeai-guardrails} is on the classpath.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.GuardRailProvider}
 */
public final class GuardRailProviderImpl implements GuardRailProvider {

    @Override public GuardRail pii()              { return new PiiGuardRail(); }
    @Override public GuardRail jailbreak()        { return new JailbreakGuardRail(); }
    @Override public GuardRail promptInjection()  { return new PromptInjectionGuardRail(); }
    @Override public GuardRail toxicity()         { return new ToxicityGuardRail(); }
    @Override public GuardRail secrets()          { return new SecretsGuardRail(); }
    @Override public GuardRail topicBoundary()    { return new TopicBoundaryGuardRailImpl(); }
    @Override public GuardRail regulatory()       { return new RegulatoryGuardRailImpl(); }
}
