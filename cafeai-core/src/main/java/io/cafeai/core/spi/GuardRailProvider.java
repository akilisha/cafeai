package io.cafeai.core.spi;

import io.cafeai.core.guardrails.GuardRail;

/**
 * SPI allowing {@code cafeai-guardrails} to provide real guardrail
 * implementations that the {@code GuardRail} factories in {@code cafeai-core} delegate to.
 *
 * <p>When {@code cafeai-guardrails} is on the classpath, its
 * {@code GuardRailProviderImpl} is discovered via {@link java.util.ServiceLoader}
 * and all {@link GuardRail} factory calls return real implementations.
 *
 * <p>Without {@code cafeai-guardrails}, those factories throw
 * {@link io.cafeai.core.guardrails.GuardRailModuleNotFoundException}: a guardrail that
 * silently passed everything through would look like protection and be none.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.GuardRailProvider}
 */
public interface GuardRailProvider {

    GuardRail pii();
    GuardRail jailbreak();
    GuardRail promptInjection();
    GuardRail toxicity();
    GuardRail secrets();
    GuardRail regulatory();
    GuardRail topicBoundary();
}
