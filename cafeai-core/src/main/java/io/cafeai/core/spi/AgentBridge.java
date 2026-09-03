package io.cafeai.core.spi;

import dev.langchain4j.model.chat.ChatModel;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.agents.AgentConfig;
import io.cafeai.core.memory.MemoryStrategy;

/**
 * SPI that lets {@code cafeai-agents} provide agent registration and resolution
 * without a circular compile-time dependency on {@code cafeai-core}.
 *
 * <p>{@code cafeai-core} loads the single implementation via
 * {@link java.util.ServiceLoader}, calls {@link #init(AgentSupport)} once right
 * after loading, then delegates {@code app.agent(...)} to {@link #register} and
 * {@link #resolve}.
 *
 * <p><strong>No wrapper.</strong> {@link #resolve} returns LangChain4j's own
 * {@code AiService} proxy. CafeAI's contributions (guardrails, observability,
 * session memory, tools) are applied at {@code AiServices.builder()} time by the
 * implementation, using the capabilities handed over in {@link AgentSupport}.
 *
 * <p>Registered via {@code META-INF/services/io.cafeai.core.spi.AgentBridge}.
 */
public interface AgentBridge {

    /**
     * Called once by {@code CafeAIApp}, immediately after this bridge is loaded,
     * to lend it the {@code cafeai-core} capabilities it needs at build time.
     */
    void init(AgentSupport support);

    /**
     * Registers an agent interface under {@code name} and returns its fluent
     * configuration. Called from {@code CafeAI.agent(String, Class)} at startup.
     */
    <T> AgentConfig<T> register(String name, Class<T> agentInterface);

    /**
     * Builds (or returns a cached) LangChain4j {@code AiService} proxy for the
     * named agent. Called from {@code CafeAI.agent(String, Class, String)} in a
     * route handler.
     *
     * @param sessionId conversation session id, or {@code null} for a stateless agent
     */
    <T> T resolve(String name, Class<T> type, String sessionId);

    /** {@code true} if an agent with the given name is registered. */
    boolean isRegistered(String name);

    /** Number of registered agents. */
    int count();

    /**
     * The {@code cafeai-core} capabilities {@code cafeai-agents} borrows to
     * assemble an {@code AiServices} builder. All accessors may return
     * {@code null} when the corresponding capability is not configured.
     */
    interface AgentSupport {

        /** Resolves an {@link AiProvider} to a LangChain4j {@link ChatModel} (via the internal bridge). */
        ChatModel chatModel(AiProvider provider);

        /** The application-level default provider — used when {@code AgentConfig.model()} is unset. */
        AiProvider defaultProvider();

        /** The application-level observability bridge, or {@code null}. */
        ObserveBridge observeBridge();

        /** The application-level default memory strategy, or {@code null}. */
        MemoryStrategy defaultMemory();

        /**
         * The application-level RAG retriever from {@code app.rag(...)}, or {@code null}.
         * An agent uses this when {@code AgentConfig.rag(...)} is not set.
         * Typed {@code Object} — a {@code io.cafeai.rag.Retriever}.
         */
        default Object ragRetriever() { return null; }

        /** The application-level vector store from {@code app.vectordb(...)}, or {@code null}. */
        default Object vectorStore() { return null; }

        /** The application-level embedding model from {@code app.embed(...)}, or {@code null}. */
        default Object embeddingModel() { return null; }
    }
}
