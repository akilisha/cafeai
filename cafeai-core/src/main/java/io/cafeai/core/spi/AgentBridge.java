package io.cafeai.core.spi;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;

import java.util.function.Consumer;

/**
 * SPI allowing {@code cafeai-agents} to provide agent registration and resolution
 * without creating a circular compile-time dependency on {@code cafeai-core}.
 *
 * <p>{@code cafeai-core} calls this via {@link java.util.ServiceLoader};
 * {@code cafeai-agents} provides the implementation.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.AgentBridge}
 */
public interface AgentBridge {

    /**
     * Registers an agent interface under the given name.
     *
     * @param name           unique agent name
     * @param agentInterface the LangChain4j AiService interface class
     * @return an opaque configuration handle — cast to {@code AgentConfig<T>}
     *         in the {@code cafeai-agents} module
     */
    <T> Object register(String name, Class<T> agentInterface);

    /**
     * Resolves the agent proxy for the given name and session.
     *
     * @param name      registered agent name
     * @param type      the agent interface class
     * @param sessionId conversation session ID, or {@code null} for stateless
     * @param provider  the app-level model provider
     * @return the LangChain4j AiService proxy implementing {@code type}
     */
    <T> T resolve(String name, Class<T> type, String sessionId,
                  io.cafeai.core.internal.LangchainBridge.ChatModelAccess provider);

    /**
     * Returns {@code true} if an agent with the given name is registered.
     */
    boolean isRegistered(String name);

    /**
     * Returns the number of registered agents.
     */
    int count();
}
