package io.cafeai.observability;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-observability}.
 *
 * <p>Signals that observability is available via {@code app.observe()}
 * and {@code app.eval()}. The startup log entry confirms the module
 * is active.
 */
public final class CafeAIObservabilityModule implements CafeAIModule {

    @Override public String name()    { return "cafeai-observability"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }

    @Override
    public void register(CafeAIRegistry registry) {
        registry.registerMiddleware("console-observe",
            () -> ObserveStrategy.console());
        registry.registerMiddleware("otel-observe",
            () -> ObserveStrategy.otel());
    }
}
