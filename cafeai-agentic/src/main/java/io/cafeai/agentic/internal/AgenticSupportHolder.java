package io.cafeai.agentic.internal;

import io.cafeai.core.spi.AgentBridge;
import io.cafeai.core.spi.AgenticBridge;

/**
 * The {@code cafeai-agentic} implementation of {@link AgenticBridge}. Stashes the
 * {@link AgentBridge.AgentSupport} lent by {@code cafeai-core} at app-construction time, so
 * {@link io.cafeai.agentic.CafeAgentic#agentBuilder} can read it back.
 */
public final class AgenticSupportHolder implements AgenticBridge {

    private static volatile AgentBridge.AgentSupport support;

    @Override
    public void init(AgentBridge.AgentSupport support) {
        AgenticSupportHolder.support = support;
    }

    public static AgentBridge.AgentSupport support() {
        return support;
    }
}
