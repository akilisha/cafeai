package io.cafeai.agents;

import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.spi.AgentBridge;

/**
 * SPI implementation of {@link AgentBridge} backed by {@link AgentRegistry}.
 *
 * <p>Loaded by {@link java.util.ServiceLoader} from {@code cafeai-core} when
 * {@code cafeai-agents} is on the classpath. Bridges the agent registration
 * and resolution API surface on {@code CafeAI} to the real {@link AgentRegistry}.
 */
public final class AgentBridgeImpl implements AgentBridge {

    private final AgentRegistry registry = new AgentRegistry();

    @Override
    public <T> Object register(String name, Class<T> agentInterface) {
        AgentConfig<T> config = new AgentConfig<>(agentInterface);
        registry.register(name, config);
        return config;
    }

    @Override
    public <T> T resolve(String name, Class<T> type, String sessionId,
                         LangchainBridge.ChatModelAccess provider) {
        registry.checkGuardrails(name, "");  // input checked at call site
        return registry.resolve(name, type, sessionId, provider);
    }

    @Override
    public boolean isRegistered(String name) {
        return registry.isRegistered(name);
    }

    @Override
    public int count() {
        return registry.size();
    }

    /**
     * Returns the underlying registry — used by route handlers to run
     * guardrail checks against actual user input before agent invocation.
     */
    public AgentRegistry registry() {
        return registry;
    }
}
