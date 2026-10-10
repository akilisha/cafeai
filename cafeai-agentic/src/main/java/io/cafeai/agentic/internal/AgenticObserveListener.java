package io.cafeai.agentic.internal;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.audit.AuditSink;
import io.cafeai.core.spi.ObserveBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Whole-invocation observability for an agent, via {@code langchain4j-agentic}'s own
 * {@link AgentListener} -- a different type from plain LangChain4j's {@code AiServiceListener},
 * so this is a separate adapter from {@code cafeai-aiservices}'s {@code AgentObserveListener},
 * not a shared one. Always logs at INFO; when an {@link ObserveBridge} is present it also
 * brackets the invocation with {@link ObserveBridge#beforeAgent(String)} / {@code #afterAgent}.
 * Each tool an agent runs is an audit record ({@link AuditEvent.ToolCall}).
 */
public final class AgenticObserveListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger("io.cafeai.agentic");

    private final ObserveBridge observeBridge;
    private final AuditSink audit;
    private final ThreadLocal<Object> ctx = new ThreadLocal<>();

    public AgenticObserveListener(ObserveBridge observeBridge, AuditSink audit) {
        this.observeBridge = observeBridge;
        this.audit = audit;
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution execution) {
        var t = execution.toolExecution();
        audit.record(AuditEvent.ToolCall.now(t.request().name(), AuditEvent.ToolCall.Via.AGENT, t.hasFailed(),
                t.duration() == null ? Duration.ZERO : t.duration()));
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
