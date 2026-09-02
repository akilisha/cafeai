package io.cafeai.core.agents;

/**
 * A source of tools for an agent. CafeAI collects these on {@link AgentConfig}
 * and {@code cafeai-agents} turns each into the matching {@code AiServices}
 * builder call:
 *
 * <ul>
 *   <li>{@link JavaTool} — a {@code @Tool}-annotated object → {@code .tools(instance)}</li>
 *   <li>{@link McpTool} — a named {@code cafeai-connect} MCP connection →
 *       {@code .toolProvider(...)} (planned; requires the {@code McpEndpoint} connector)</li>
 * </ul>
 *
 * <p>Modelled as a sealed hierarchy rather than {@code List<Object>} so the MCP
 * source can be added without touching the collection type.
 */
public sealed interface ToolSource permits ToolSource.JavaTool, ToolSource.McpTool {

    /** A plain {@code @Tool}-annotated Java object. */
    record JavaTool(Object instance) implements ToolSource {}

    /** A named MCP server connection registered via {@code app.connect(McpEndpoint.at(...))}. */
    record McpTool(String connectionName) implements ToolSource {}
}
