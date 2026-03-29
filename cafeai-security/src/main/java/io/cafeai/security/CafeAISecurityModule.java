package io.cafeai.security;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-security}.
 */
public final class CafeAISecurityModule implements CafeAIModule {

    @Override public String name()    { return "cafeai-security"; }
    @Override public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        registry.registerMiddleware("prompt-injection-detector",
            () -> AiSecurity.promptInjectionDetector());
        registry.registerMiddleware("rag-data-leakage-prevention",
            () -> AiSecurity.ragDataLeakagePrevention());
        registry.registerMiddleware("cache-poisoning-detector",
            () -> AiSecurity.semanticCachePoisoningDetector());
    }
}
