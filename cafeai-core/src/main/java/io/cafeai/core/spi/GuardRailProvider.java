package io.cafeai.core.spi;

import io.cafeai.core.guardrails.GuardRail;

/**
 * SPI allowing {@code cafeai-guardrails} to provide real guardrail
 * implementations that replace the pass-through stubs in {@code cafeai-core}.
 *
 * <p>When {@code cafeai-guardrails} is on the classpath, its
 * {@code GuardRailProviderImpl} is discovered via {@link java.util.ServiceLoader}
 * and all {@link GuardRail} factory calls return real implementations.
 *
 * <p>Without {@code cafeai-guardrails}, all guardrails are no-ops that call
 * {@code next.run()} -- the application compiles and runs but guardrails do nothing.
 * A warning is logged once on first use.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.GuardRailProvider}
 */
public interface GuardRailProvider {

    GuardRail pii();
    GuardRail jailbreak();
    GuardRail promptInjection();
    GuardRail bias();
    GuardRail hallucination();
    GuardRail toxicity();
    GuardRail regulatory();
    GuardRail topicBoundary();
}
