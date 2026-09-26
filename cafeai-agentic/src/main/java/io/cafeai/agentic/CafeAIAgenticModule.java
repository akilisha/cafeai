package io.cafeai.agentic;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-agentic} at startup.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when the {@code cafeai-agentic} JAR is on
 * the classpath -- also registers {@link io.cafeai.agentic.internal.AgenticSupportHolder} as the
 * {@code AgenticBridge} SPI implementation, so {@link CafeAgentic#agentBuilder} can read the
 * app's registered model, guardrails and observability.
 */
public final class CafeAIAgenticModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-agentic"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
