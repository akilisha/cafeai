package io.cafeai.tools;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.cafeai.core.spi.ToolBridge;

import java.util.List;

/**
 * ServiceLoader implementation of {@link ToolBridge}.
 *
 * <p>Bridges {@code cafeai-core}'s tool registration calls to
 * {@link ToolRegistry}, which owns the actual tool invocation logic.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ToolBridge}
 */
public final class CafeAIToolBridge implements ToolBridge {

    private final ToolRegistry registry = new ToolRegistry();

    @Override
    public void register(Object toolInstance) {
        registry.register(toolInstance);
    }

    @Override
    public void registerMcp(Object mcpServer) {
        if (!(mcpServer instanceof McpServer mcp)) {
            throw new IllegalArgumentException(
                "Expected an io.cafeai.tools.McpServer instance, got: " +
                mcpServer.getClass().getName() + ". " +
                "Use: app.mcp(McpServer.connect(\"http://mcp-server:3000\"))");
        }
        // Discover tools from the MCP server and register them as external tools
        mcp.discoverTools().forEach(tool ->
            registry.registerExternal(tool.name(), tool.description(), tool.parameters()));
    }

    @Override
    public boolean hasTools() {
        return registry.hasTools();
    }

    @Override
    public String executeWithTools(ChatLanguageModel model, List<ChatMessage> messages) {
        return registry.executeWithTools(model, messages);
    }
}
