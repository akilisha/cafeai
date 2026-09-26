package io.cafeai.agentic;

import dev.langchain4j.agentic.observability.AgentInvocation;
import dev.langchain4j.agentic.observability.AgentMonitor;
import dev.langchain4j.agentic.observability.MonitoredExecution;
import io.cafeai.core.middleware.Middleware;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a {@code MonitoredAgent}'s {@link AgentMonitor} an HTTP identity. The library itself
 * exposes only raw execution/memory-id data -- the "nice topology page" from LangChain4j
 * conference demos is Quarkus Dev UI tooling, not something the core {@code langchain4j-agentic}
 * artifact ships -- so this serves that same data as JSON instead of trying to reproduce an
 * HTML page that doesn't actually exist upstream.
 *
 * <pre>{@code
 *   MonitoredWorkflow workflow = CafeAgentic.agentBuilder(app, MonitoredWorkflow.class)...build();
 *   app.get("/agentic/monitor", CafeAgenticMonitor.route(workflow.agentMonitor()));
 * }</pre>
 */
public final class CafeAgenticMonitor {

    private CafeAgenticMonitor() {}

    /** A GET route handler that reports {@code monitor}'s current state as JSON. */
    public static Middleware route(AgentMonitor monitor) {
        return (req, res, next) -> res.json(Map.of(
            "ongoing",    monitor.ongoingExecutions().values().stream().map(CafeAgenticMonitor::executionOf).toList(),
            "successful", monitor.successfulExecutions().stream().map(CafeAgenticMonitor::executionOf).toList(),
            "failed",     monitor.failedExecutions().stream().map(CafeAgenticMonitor::executionOf).toList(),
            "memoryIds",  monitor.allMemoryIds().stream().map(String::valueOf).toList()
        ));
    }

    private static Map<String, Object> executionOf(MonitoredExecution execution) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memoryId", String.valueOf(execution.memoryId()));
        out.put("done", execution.done());
        out.put("hasError", execution.hasError());
        if (execution.hasError()) {
            out.put("error", String.valueOf(execution.error().error()));
        }
        AgentInvocation top = execution.topLevelInvocations();
        if (top != null) {
            out.put("invocation", invocationOf(top));
        }
        return out;
    }

    private static Map<String, Object> invocationOf(AgentInvocation invocation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", invocation.agent().name());
        out.put("done", invocation.done());
        out.put("startTime", String.valueOf(invocation.startTime()));
        out.put("inputs", invocation.inputs());
        if (invocation.done()) {
            out.put("finishTime", String.valueOf(invocation.finishTime()));
            out.put("duration", invocation.duration().toString());
            out.put("output", invocation.output());
            out.put("totalTokenCount", invocation.totalTokenCount());
        }
        List<AgentInvocation> nested = invocation.nestedInvocations();
        if (!nested.isEmpty()) {
            out.put("nested", nested.stream().map(CafeAgenticMonitor::invocationOf).toList());
        }
        return out;
    }
}
