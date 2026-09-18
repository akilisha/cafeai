package io.cafeai.guardrails;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-guardrails} at startup.
 *
 * <p>The guardrail implementations are not registered here. They are
 * discovered through the {@code GuardRailProvider} SPI, which is how
 * {@code GuardRail.pii()} and its siblings find them.
 */
public final class CafeAIGuardrailsModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-guardrails"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
