package io.cafeai.tools;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-tools}.
 *
 * <p>Signals that tool use and MCP connectivity are available.
 * The actual tool and MCP registrations happen via {@code app.tool()}
 * and {@code app.mcp()} — this module entry confirms the capability
 * is present and logs it at startup.
 */
public final class CafeAIToolsModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-tools"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        // Tools are registered per-instance via app.tool() and app.mcp().
        // This module registration confirms the capability is present —
        // the startup log entry is the observable output.
    }
}
