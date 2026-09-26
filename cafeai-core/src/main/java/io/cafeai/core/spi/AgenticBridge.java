package io.cafeai.core.spi;

/**
 * SPI that lets {@code cafeai-agentic} read the app's registered capabilities
 * (model, guardrails, observability, memory) without a circular compile-time
 * dependency on {@code cafeai-core} -- and without depending on
 * {@code cafeai-aiservices} either, since {@code cafeai-agentic} must work
 * standalone.
 *
 * <p>Deliberately reuses {@link AgentBridge.AgentSupport} rather than
 * declaring a new nested capability type: a multi-agent workflow needs
 * exactly the same capabilities (model, guardrails, observability, memory)
 * a single agent does. This is its own SPI slot, not a reuse of
 * {@link AgentBridge} itself, because {@code ServiceLoader} loads one
 * implementation per interface and {@code cafeai-agentic} must be
 * discoverable whether or not {@code cafeai-aiservices} is present.
 *
 * <p>{@code cafeai-core} loads the implementation via {@link java.util.ServiceLoader}
 * and calls {@link #init(AgentBridge.AgentSupport)} once at construction time.
 * Unlike {@link AgentBridge}, there is no {@code register}/{@code resolve}
 * lifecycle here -- {@code cafeai-agentic}'s entry point
 * ({@code io.cafeai.agentic.CafeAgentic}) is a plain class the developer
 * imports directly, not reached through {@code CafeAI} itself.
 *
 * <p>Registered via {@code META-INF/services/io.cafeai.core.spi.AgenticBridge}.
 */
public interface AgenticBridge {

    /**
     * Called once by {@code CafeAIApp}, immediately after this bridge is loaded,
     * to lend it the {@code cafeai-core} capabilities it needs.
     */
    void init(AgentBridge.AgentSupport support);
}