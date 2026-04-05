package io.cafeai.core.agents;

import dev.langchain4j.service.AiServices;
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
 *            MessageWindowChatMemory.withMaxMessages(20)));
 * }</pre>
 *
 * @param <T> the agent interface type
 */
public final class AgentConfig<T> {

    private final Class<T>        agentInterface;
    private       String          systemPrompt;
    private       AiProvider      provider;
    private       MemoryStrategy  memoryStrategy;
    private final List<GuardRail> guardRails     = new ArrayList<>();
    private final List<Object>    tools          = new ArrayList<>();
    private       Consumer<?>     builderConsumer;

    public AgentConfig(Class<T> agentInterface) {
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
     * Useful for using a cheaper model for classification and an expensive
     * model for the specialist.
     */
    public AgentConfig<T> model(AiProvider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Registers a CafeAI memory strategy for this agent.
     */
    public AgentConfig<T> memory(MemoryStrategy strategy) {
        this.memoryStrategy = strategy;
        return this;
    }

    /**
     * Adds guardrails to this agent. Applied before the reasoning loop begins —
     * the LLM is never called if a guardrail fires.
     */
    public AgentConfig<T> guard(GuardRail... rails) {
        for (GuardRail r : rails) guardRails.add(r);
        return this;
    }

    /**
     * Adds a tool instance to this agent.
     */
    public AgentConfig<T> tool(Object toolInstance) {
        tools.add(toolInstance);
        return this;
    }

    /**
     * Escape hatch — direct access to the {@code AiServices} builder after
     * CafeAI has applied its own configuration. Use for capabilities CafeAI
     * does not abstract: per-session memory providers, advanced RAG augmentors,
     * moderation models, dynamic system prompt providers, output parsers, etc.
     *
     * <p>The consumer receives an {@code AiServices<T>} instance and may call
     * any builder method on it. It must not call {@code .build()}.
     *
     * @param consumer receives the {@code AiServices<T>} builder
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public AgentConfig<T> configure(Consumer<AiServices<T>> consumer) {
        this.builderConsumer = (Consumer) consumer;
        return this;
    }

    // ── Accessors (package-visible to cafeai-agents via SPI) ──────────────────

    public Class<T>        agentInterface()  { return agentInterface; }
    public String          systemPrompt()    { return systemPrompt; }
    public AiProvider      provider()        { return provider; }
    public MemoryStrategy  memoryStrategy()  { return memoryStrategy; }
    public List<GuardRail> guardRails()      { return List.copyOf(guardRails); }
    public List<Object>    tools()           { return List.copyOf(tools); }
    public Consumer<?>     builderConsumer() { return builderConsumer; }
}
