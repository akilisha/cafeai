package io.cafeai.guardrails;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-guardrails}.
 *
 * <p>Registers all guardrail implementations. Guardrails are also
 * discovered independently via {@code GuardRailProvider} SPI — this
 * registration makes them available by name through the registry
 * for programmatic lookup.
 */
public final class CafeAIGuardrailsModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-guardrails"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        registry.registerGuardRail("pii",              PiiGuardRail::new);
        registry.registerGuardRail("jailbreak",        JailbreakGuardRail::new);
        registry.registerGuardRail("prompt-injection", PromptInjectionGuardRail::new);
        registry.registerGuardRail("toxicity",         ToxicityGuardRail::new);
        registry.registerGuardRail("topic-boundary",   TopicBoundaryGuardRailImpl::new);
        registry.registerGuardRail("regulatory",       RegulatoryGuardRailImpl::new);
    }
}
