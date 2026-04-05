package io.cafeai.agents;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent configuration for a CafeAI agent backed by a LangChain4j {@code AiService}.
 *
 * <p>CafeAI pre-wires the common path — model, tools, memory, guardrails. The
 * {@link #configure(Consumer)} escape hatch gives access to the full
 * {@code AiServices.Builder} for capabilities CafeAI does not abstract:
 * per-session memory providers, advanced RAG augmentors, moderation models,
 * dynamic system prompt providers, output parsers, and so on.
 *
 * <pre>{@code
 * app.agent("loan-advisor", LoanAdvisor.class)
 *    .system("You are a conservative mortgage advisor...")
 *    .memory(MemoryStrategy.inMemory())
 *    .guard(GuardRail.regulatory().ecoa().fairHousing())
 *    .configure(builder -> builder
 *        .chatMemoryProvider(id ->
 *            MessageWindowChatMemory.withMaxMessages(20))
 *        .retrievalAugmentor(myAdvancedRag));
 * }</pre>
 *
 * <p><strong>Phase 2 scaffold:</strong> fluent API is defined; builder wiring
 * is implemented in Phase 3.
 *
 * @param <T> the agent interface type
 */
public final class AgentConfig<T> {

    private final Class<T>        agentInterface;
    private       String          systemPrompt;
    private       AiProvider      provider;
    private       MemoryStrategy  memoryStrategy;
    private final List<GuardRail> guardRails  = new ArrayList<>();
    private final List<Object>    tools       = new ArrayList<>();
    private       Consumer<?>     builderConsumer;

    AgentConfig(Class<T> agentInterface) {
        this.agentInterface = agentInterface;
    }

    // ── Fluent API ─────────────────────────────────────────────────────────────

    /**
     * Sets the system prompt for this agent.
     * Overrides any {@code @SystemMessage} annotation on the interface.
     */
    public AgentConfig<T> system(String prompt) {
        this.systemPrompt = prompt;
        return this;
    }

    /**
     * Overrides the application-level AI provider for this agent.
     * Useful for using a cheaper/faster model for a classification agent
     * and an expensive model for the specialist.
     */
    public AgentConfig<T> model(AiProvider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Registers a CafeAI memory strategy for this agent.
     * The strategy governs how conversation history is stored and retrieved.
     */
    public AgentConfig<T> memory(MemoryStrategy strategy) {
        this.memoryStrategy = strategy;
        return this;
    }

    /**
     * Adds a guardrail to this agent. Guardrails are applied before the agent
     * reasoning loop begins — the LLM is never called if a guardrail fires.
     */
    public AgentConfig<T> guard(GuardRail... rails) {
        for (GuardRail r : rails) guardRails.add(r);
        return this;
    }

    /**
     * Adds a tool instance to this agent. Tools registered here are available
     * in addition to any tools registered at the application level.
     */
    public AgentConfig<T> tool(Object toolInstance) {
        tools.add(toolInstance);
        return this;
    }

    /**
     * Escape hatch — provides direct access to the {@code AiServices.Builder}
     * after CafeAI has applied its own configuration. Use for capabilities
     * CafeAI does not abstract: per-session memory providers, advanced RAG
     * augmentors, moderation models, dynamic system prompt providers, etc.
     *
     * <p>The consumer is called during agent instantiation. It may override
     * anything CafeAI has already set.
     *
     * @param consumer receives the builder — must not call {@code .build()}
     */
    @SuppressWarnings("unchecked")
    public AgentConfig<T> configure(
            Consumer<dev.langchain4j.service.AiServices<T>> consumer) {
        this.builderConsumer = consumer;
        return this;
    }

    // ── Accessors (package-private) ────────────────────────────────────────────

    Class<T>         agentInterface()  { return agentInterface; }
    String           systemPrompt()    { return systemPrompt; }
    AiProvider       provider()        { return provider; }
    MemoryStrategy   memoryStrategy()  { return memoryStrategy; }
    List<GuardRail>  guardRails()      { return List.copyOf(guardRails); }
    List<Object>     tools()           { return List.copyOf(tools); }
    Consumer<?>      builderConsumer() { return builderConsumer; }
}
