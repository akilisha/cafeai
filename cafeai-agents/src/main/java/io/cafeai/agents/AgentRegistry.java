package io.cafeai.agents;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.internal.LangchainBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores registered agent configurations and builds LangChain4j {@code AiService}
 * proxies on demand.
 *
 * <h2>Session model</h2>
 * Each unique {@code sessionId} gets its own {@link MessageWindowChatMemory}
 * instance, isolating conversation history between sessions. Agents registered
 * without a memory strategy are stateless — no history is maintained.
 *
 * <h2>Guardrail model</h2>
 * Guardrails are applied <em>before</em> the agent proxy is invoked — before
 * the reasoning loop begins. If a guardrail blocks the input, the agent method
 * is never called. Guardrail checking happens in {@link #checkGuardrails}.
 */
public final class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** Default memory window — 20 messages per session. */
    private static final int DEFAULT_MEMORY_WINDOW = 20;

    /** name → config */
    private final ConcurrentHashMap<String, AgentConfig<?>> registrations
        = new ConcurrentHashMap<>();

    /** name → sessionId → agent proxy */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> sessionAgents
        = new ConcurrentHashMap<>();

    /** name → stateless agent proxy (shared, no memory) */
    private final ConcurrentHashMap<String, Object> statelessAgents
        = new ConcurrentHashMap<>();

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Registers an agent configuration under the given name.
     *
     * @param name   unique agent name
     * @param config fluent configuration produced by {@link AgentConfig}
     */
    public <T> void register(String name, AgentConfig<T> config) {
        Objects.requireNonNull(name,   "Agent name must not be null");
        Objects.requireNonNull(config, "AgentConfig must not be null");
        registrations.put(name, config);
        sessionAgents.put(name, new ConcurrentHashMap<>());
        log.info("[cafeai-agents] Agent registered: '{}' ({})",
            name, config.agentInterface().getSimpleName());
    }

    /** Returns {@code true} if an agent with the given name has been registered. */
    public boolean isRegistered(String name) {
        return registrations.containsKey(name);
    }

    /** Returns the number of registered agents. */
    public int size() {
        return registrations.size();
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Resolves the agent proxy for the given name and session.
     *
     * <p>If a session ID is provided and the agent has memory configured, a
     * dedicated memory instance is created (or reused) for that session.
     * If no session ID is provided, the agent runs statelessly.
     *
     * @param name      registered agent name
     * @param type      the agent interface class — used for type safety
     * @param sessionId conversation session ID, or {@code null} for stateless
     * @param provider  the resolved {@link LangchainBridge.ChatModelAccess} — the app-level model
     * @return the LangChain4j AiService proxy implementing {@code type}
     * @throws IllegalArgumentException if no agent with {@code name} is registered
     */
    @SuppressWarnings("unchecked")
    public <T> T resolve(String name, Class<T> type, String sessionId,
                         LangchainBridge.ChatModelAccess provider) {

        AgentConfig<?> raw = registrations.get(name);
        if (raw == null) {
            throw new IllegalArgumentException(
                "No agent registered with name '" + name + "'. " +
                "Register it with app.agent(\"" + name + "\", " +
                type.getSimpleName() + ".class) before listen().");
        }

        AgentConfig<T> config = (AgentConfig<T>) raw;

        if (sessionId != null && config.memoryStrategy() != null) {
            // Per-session agent — create or reuse
            return type.cast(
                sessionAgents.get(name)
                    .computeIfAbsent(sessionId,
                        sid -> buildAgent(config, provider, sid)));
        } else {
            // Stateless agent — build once, reuse
            return type.cast(
                statelessAgents.computeIfAbsent(name,
                    n -> buildAgent(config, provider, null)));
        }
    }

    // ── Guardrail checking ─────────────────────────────────────────────────────

    /**
     * Runs all guardrails registered for the named agent against the given input.
     * Only guardrails implementing {@link io.cafeai.core.guardrails.TextGuardRail}
     * are applied in the agent context — others are silently skipped.
     *
     * @param name  the agent name
     * @param input the user input to screen
     * @throws GuardRailViolationException if any guardrail blocks the input
     */
    public void checkGuardrails(String name, String input) {
        AgentConfig<?> config = registrations.get(name);
        if (config == null) return;

        for (GuardRail rail : config.guardRails()) {
            if (rail instanceof io.cafeai.core.guardrails.TextGuardRail tgr) {
                io.cafeai.core.guardrails.TextGuardRail.Result result = tgr.checkText(input);
                if (result.isBlocked()) {
                    throw new GuardRailViolationException(
                        "Agent '" + name + "' blocked by guardrail '"
                        + rail.name() + "': " + result.reason());
                }
            }
        }
    }

    // ── Internal builder ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> T buildAgent(AgentConfig<T> config,
                             LangchainBridge.ChatModelAccess provider,
                             String sessionId) {

        // Resolve model — agent-level override wins, then app-level
        var chatModelAccess = config.provider() != null
            ? (LangchainBridge.ChatModelAccess) config.provider()
            : provider;

        var model = chatModelAccess.toChatModel();

        AiServices<T> builder = AiServices.builder(config.agentInterface())
            .chatModel(model);

        // System prompt — if set, uses systemMessageProvider so it applies per session
        if (config.systemPrompt() != null) {
            final String prompt = config.systemPrompt();
            builder.systemMessageProvider(memId -> prompt);
        }

        // Memory — per-session or shared single instance
        if (config.memoryStrategy() != null && sessionId != null) {
            builder.chatMemoryProvider(memId ->
                MessageWindowChatMemory.withMaxMessages(DEFAULT_MEMORY_WINDOW));
        } else if (config.memoryStrategy() != null) {
            builder.chatMemory(
                MessageWindowChatMemory.withMaxMessages(DEFAULT_MEMORY_WINDOW));
        }

        // Tools
        if (!config.tools().isEmpty()) {
            builder.tools(config.tools().toArray());
        }

        // Escape hatch — developer-supplied builder consumer applied last
        if (config.builderConsumer() != null) {
            ((java.util.function.Consumer<AiServices<T>>) config.builderConsumer())
                .accept(builder);
        }

        T agent = builder.build();

        log.debug("[cafeai-agents] Built agent '{}' session='{}'",
            config.agentInterface().getSimpleName(), sessionId);

        return agent;
    }
}
