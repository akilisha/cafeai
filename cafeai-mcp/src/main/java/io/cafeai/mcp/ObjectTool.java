package io.cafeai.mcp;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.audit.AuditSink;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.extensions.mcp.server.McpToolRequest;
import io.helidon.extensions.mcp.server.McpToolResult;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** A LangChain4j {@code @Tool} method served as an MCP tool; each call is an audit record. */
final class ObjectTool implements McpTool {

    private final ToolSpecification spec;
    private final DefaultToolExecutor executor;
    private final String schema;
    private final AuditSink audit;

    private ObjectTool(Object target, Method method, AuditSink audit) {
        this.audit = audit;
        this.spec = ToolSpecifications.toolSpecificationFrom(method);
        // Exceptions propagate (instead of coming back as an ordinary answer), so a failing
        // tool is reported to the agent as an error result.
        this.executor = DefaultToolExecutor.builder()
                .object(target).originalMethod(method).methodToInvoke(method)
                .propagateToolExecutionExceptions(true)
                .wrapToolArgumentsExceptions(true)
                .build();
        this.schema = Json.write(Json.schemaOf(spec.parameters()));
    }

    /** One tool per {@code @Tool} method of {@code target}. */
    static List<ObjectTool> from(Object target, AuditSink audit) {
        List<ObjectTool> tools = new ArrayList<>();
        for (Method m : target.getClass().getMethods()) {
            if (m.isAnnotationPresent(Tool.class)) tools.add(new ObjectTool(target, m, audit));
        }
        if (tools.isEmpty()) {
            throw new IllegalArgumentException(target.getClass().getName() + " has no public @Tool methods");
        }
        return tools;
    }

    @Override public String name() { return spec.name(); }
    @Override public String description() { return spec.description() == null ? spec.name() : spec.description(); }
    @Override public String schema() { return schema; }

    @Override
    public McpToolResult tool(McpToolRequest request) {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("mcp").name(spec.name())
                .arguments(Json.write(Json.arguments(request.arguments())))
                .build();
        long start = System.nanoTime();
        boolean failed = true;
        try {
            String result = executor.execute(call, "mcp");
            failed = false;
            return McpToolResult.builder().addTextContent(result == null ? "" : result).build();
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            return McpToolResult.builder()
                    .addTextContent(cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()))
                    .error(true).build();
        } finally {
            audit.record(AuditEvent.ToolCall.now(spec.name(), AuditEvent.ToolCall.Via.MCP, failed,
                    Duration.ofNanos(System.nanoTime() - start)));
        }
    }
}
