package io.cafeai.agents;

import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServices;
import io.cafeai.agents.adapter.AgentObserveListener;
import io.cafeai.agents.adapter.CafeAiChatMemoryStore;
import io.cafeai.agents.adapter.CafeAiContentRetriever;
import io.cafeai.agents.adapter.GuardrailAdapters;
import io.cafeai.core.agents.AgentConfig;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.spi.AgentBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The {@code cafeai-agents} implementation of {@link AgentBridge}.
 *
 * <p>Registers agent configs, and on {@link #resolve} assembles a LangChain4j
 * {@code AiServices} builder from the config + the {@link AgentSupport}
 * capabilities lent by {@code cafeai-core}, then returns the built
 * {@code AiService} proxy. No wrapper: CafeAI's contributions are all
 * {@code AiServices.builder()} calls.
 */
public final class AgentRegistry implements AgentBridge {

    private static final int DEFAULT_MEMORY_WINDOW = 20;

    private final Map<String, AgentConfig<?>> configs = new ConcurrentHashMap<>();
    private final Map<String, Object>         proxies = new ConcurrentHashMap<>();
    private AgentSupport support;

    @Override
    public void init(AgentSupport support) {
        this.support = support;
    }

    @Override
    public <T> AgentConfig<T> register(String name, Class<T> agentInterface) {
        if (configs.containsKey(name)) {
            throw new IllegalStateException("An agent named '" + name + "' is already registered.");
        }
        AgentConfig<T> config = new AgentConfig<>(agentInterface);
        configs.put(name, config);
        return config;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T resolve(String name, Class<T> type, String sessionId) {
        AgentConfig<T> config = (AgentConfig<T>) configs.get(name);
        if (config == null) {
            throw new IllegalStateException(
                "No agent registered under '" + name + "'. Register it at startup: "
                + "app.agent(\"" + name + "\", " + type.getSimpleName() + ".class)");
        }
        if (support == null) {
            throw new IllegalStateException("cafeai-agents is not initialised — build the app via CafeAI.create().");
        }

        MemoryStrategy memory = config.memoryStrategy() != null
            ? config.memoryStrategy() : support.defaultMemory();
        boolean stateful = memory != null;
        String cacheKey = stateful ? name + "::" + sessionId : name;

        Object cached = proxies.get(cacheKey);
        if (cached != null) {
            return (T) cached;
        }
        T built = build(name, type, config, sessionId, memory);
        proxies.put(cacheKey, built);
        return built;
    }

    private <T> T build(String name, Class<T> type, AgentConfig<T> config,
                        String sessionId, MemoryStrategy memory) {

        AiProvider provider = config.provider() != null ? config.provider() : support.defaultProvider();
        if (provider == null) {
            throw new IllegalStateException(
                "Agent '" + name + "' has no model. Register a default with app.ai(...) "
                + "or set .model(...) on the agent.");
        }
        ChatModel model = support.chatModel(provider);

        AiServices<T> builder = AiServices.builder(type).chatModel(model);

        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            builder.systemMessage(config.systemPrompt());
        }

        // -- tools -----------------------------------------------------------
        List<Object> tools = config.tools();
        if (!tools.isEmpty()) {
            builder.tools(new ArrayList<>(tools));
        }

        // -- guardrails ----------------------------------------------------
        List<InputGuardrail>  inputRails  = new ArrayList<>();
        List<OutputGuardrail> outputRails = new ArrayList<>();
        for (GuardRail rail : config.guardRails()) {
            GuardRail.Position pos = rail.position();
            if (pos == GuardRail.Position.PRE_LLM || pos == GuardRail.Position.BOTH) {
                inputRails.add(GuardrailAdapters.asInput(rail));
            }
            if (pos == GuardRail.Position.POST_LLM || pos == GuardRail.Position.BOTH) {
                outputRails.add(GuardrailAdapters.asOutput(rail));
            }
        }
        if (!inputRails.isEmpty())  builder.inputGuardrails(inputRails);
        if (!outputRails.isEmpty()) builder.outputGuardrails(outputRails);

        // -- RAG --------------------------------------------------------
        Object ragRetriever = config.ragRetriever() != null
            ? config.ragRetriever() : support.ragRetriever();
        Object vectorStore    = support.vectorStore();
        Object embeddingModel = support.embeddingModel();
        if (ragRetriever != null && vectorStore != null && embeddingModel != null) {
            builder.contentRetriever(
                new CafeAiContentRetriever(ragRetriever, vectorStore, embeddingModel));
        }

        // -- memory ------------------------------------------------------
        if (memory != null) {
            builder.chatMemory(MessageWindowChatMemory.builder()
                .id(sessionId == null ? "default" : sessionId)
                .maxMessages(DEFAULT_MEMORY_WINDOW)
                .chatMemoryStore(new CafeAiChatMemoryStore(memory))
                .build());
        }

        // -- observability ---------------------------------------------
        for (AiServiceListener<?> l :
                AgentObserveListener.forAgent(name, support.observeBridge())) {
            builder.registerListeners(l);
        }

        // -- escape hatch (runs last, over the assembled builder) ------
        Consumer<AiServices<T>> tweak = config.builderConsumer();
        if (tweak != null) {
            tweak.accept(builder);
        }

        return builder.build();
    }

    @Override
    public boolean isRegistered(String name) {
        return configs.containsKey(name);
    }

    @Override
    public int count() {
        return configs.size();
    }
}
