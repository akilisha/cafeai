package io.cafeai.observability;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-observability} at startup.
 *
 * <p>Signals that observability is available via {@code app.observe()}
 * and {@code app.eval()}.
 */
public final class CafeAIObservabilityModule implements CafeAIModule {

    @Override public String name()    { return "cafeai-observability"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
}
