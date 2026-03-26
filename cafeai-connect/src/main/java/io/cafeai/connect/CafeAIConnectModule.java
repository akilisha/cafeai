package io.cafeai.connect;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-connect}.
 *
 * <p>Signals that out-of-process service connectivity is available
 * via {@code app.connect()}. The startup log entry confirms the
 * module is active.
 */
public final class CafeAIConnectModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-connect"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        // Connections are registered per-instance via app.connect().
        // No factory registration needed — each Connection implementation
        // is self-contained and carries its own probe + register logic.
    }
}
