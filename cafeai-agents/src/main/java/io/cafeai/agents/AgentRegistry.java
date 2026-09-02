package io.cafeai.agents;

import io.cafeai.core.agents.AgentConfig;
import io.cafeai.core.spi.AgentBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code cafeai-agents} implementation of {@link AgentBridge}.
 *
 * <p>Phase 2 (ROADMAP-12): scaffold only. {@link #register} stores the config
 * and the startup-log accessors work; {@link #resolve} — the {@code AiServices}
 * assembly + adapters — arrives in Phases 3–4.
 */
public final class AgentRegistry implements AgentBridge {

    private final Map<String, AgentConfig<?>> configs = new ConcurrentHashMap<>();
    private AgentSupport support;

    @Override
    public void init(AgentSupport support) {
        this.support = support;
    }

    @Override
    public <T> AgentConfig<T> register(String name, Class<T> agentInterface) {
        if (configs.containsKey(name)) {
            throw new IllegalStateException(
                "An agent named '" + name + "' is already registered.");
        }
        AgentConfig<T> config = new AgentConfig<>(agentInterface);
        configs.put(name, config);
        return config;
    }

    @Override
    public <T> T resolve(String name, Class<T> type, String sessionId) {
        throw new UnsupportedOperationException(
            "AgentRegistry.resolve() lands in ROADMAP-12 Phase 3–4 "
            + "(AiServices assembly + guardrail/observe/memory adapters).");
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
