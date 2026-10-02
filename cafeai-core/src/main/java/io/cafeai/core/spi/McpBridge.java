package io.cafeai.core.spi;

import io.cafeai.core.CafeAI;
import io.cafeai.core.mcp.McpConfig;

/**
 * SPI that {@code cafeai-mcp} implements to give {@code app.mcp()} its behaviour.
 * Found with {@link java.util.ServiceLoader}; without it, {@code app.mcp()} throws
 * {@link io.cafeai.core.mcp.McpModuleNotFoundException}.
 */
public interface McpBridge {

    /**
     * The MCP configuration for {@code app}, called once per app. The implementation
     * mounts its endpoint through {@code app.helidon()}.
     */
    McpConfig create(CafeAI app);
}
