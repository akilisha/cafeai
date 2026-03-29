package io.cafeai.core.spi;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * SPI allowing {@code cafeai-tools} to provide tool registration and execution
 * without creating a circular compile-time dependency.
 *
 * <p>{@code cafeai-core} calls this via {@link java.util.ServiceLoader};
 * {@code cafeai-tools} provides the implementation.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ToolBridge}
 */
public interface ToolBridge {

    /**
     * Registers a tool provider instance, scanning it for {@code @CafeAITool}
     * annotated methods.
     *
     * @param toolInstance an object with one or more {@code @CafeAITool} methods
     */
    void register(Object toolInstance);

    /**
     * Registers tools discovered from an MCP server connection.
     *
     * @param mcpServer an {@code io.cafeai.tools.McpServer} instance
     */
    void registerMcp(Object mcpServer);

    /**
     * Returns {@code true} if any tools have been registered.
     */
    boolean hasTools();

    /**
     * Executes the full tool-use loop: sends tools + messages to the model,
     * handles tool invocations, and returns the final text response.
     *
     * @param model    the Langchain4j {@link ChatLanguageModel} to call
     * @param messages mutable message list -- modified in place with tool exchanges
     * @return the model's final text response
     */
    String executeWithTools(ChatLanguageModel model, List<ChatMessage> messages);
}
