package io.cafeai.agentic.internal;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.cafeai.core.spi.ObserveBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whole-invocation observability for an agent, via {@code langchain4j-agentic}'s own
 * {@link AgentListener} -- a different type from plain LangChain4j's {@code AiServiceListener},
 * so this is a separate adapter from {@code cafeai-aiservices}'s {@code AgentObserveListener},
 * not a shared one. Always logs at INFO; when an {@link ObserveBridge} is present it also
 * brackets the invocation with {@link ObserveBridge#beforeAgent(String)} / {@code #afterAgent}.
 */
public final class AgenticObserveListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger("io.cafeai.agentic");

    private final ObserveBridge observeBridge;
    private final ThreadLocal<Object> ctx = new ThreadLocal<>();

    public AgenticObserveListener(ObserveBridge observeBridge) {
        this.observeBridge = observeBridge;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        log.info("agent '{}' invoked", request.agentName());
        if (observeBridge != null) {
            ctx.set(observeBridge.beforeAgent(request.agentName()));
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        log.info("agent '{}' completed", response.agentName());
        if (observeBridge != null) {
            observeBridge.afterAgent(ctx.get(), response.agentName(), null);
            ctx.remove();
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        log.warn("agent '{}' failed: {}", error.agentName(), String.valueOf(error.error()));
        if (observeBridge != null) {
            observeBridge.afterAgent(ctx.get(), error.agentName(), error.error());
            ctx.remove();
        }
    }
}
