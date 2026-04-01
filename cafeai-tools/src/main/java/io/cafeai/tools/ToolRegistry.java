package io.cafeai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all registered tools and executes the LLM tool-use loop.
 *
 * <p>One {@code ToolRegistry} lives on the application. When {@code app.tool(instance)}
 * is called, all {@link CafeAITool}-annotated methods on the instance are scanned
 * and registered.
 *
 * <p>The execution loop follows the ReAct pattern:
 * <ol>
 *   <li>Send messages + tool specs to the LLM</li>
 *   <li>LLM responds with tool call requests</li>
 *   <li>CafeAI invokes the requested tools</li>
 *   <li>Results returned to LLM as {@link ToolExecutionResultMessage}</li>
 *   <li>LLM produces its final text answer</li>
 *   <li>Repeat until LLM produces a text response (no more tool calls)</li>
 * </ol>
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** Maximum tool-call iterations per prompt -- prevents infinite loops. */
    private static final int MAX_ITERATIONS = 10;

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    /** Registers all {@link CafeAITool}-annotated methods on the given instance. */
    public void register(Object instance) {
        Objects.requireNonNull(instance, "Tool instance must not be null");
        int count = 0;
        for (Method method : instance.getClass().getMethods()) {
            if (method.isAnnotationPresent(CafeAITool.class)) {
                ToolDefinition def = ToolDefinition.fromMethod(instance, method);
                tools.put(def.name(), def);
                log.debug("Registered tool '{}' ({}): {}",
                    def.name(), def.trustLevel(), def.description());
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                instance.getClass().getSimpleName() +
                " has no @CafeAITool-annotated public methods. " +
                "Annotate at least one method with @CafeAITool(\"description\").");
        }
        log.info("Registered {} tool(s) from {}", count,
            instance.getClass().getSimpleName());
    }

    /** Registers an external MCP tool by name, description, and parameter schema. */
    public void registerExternal(String name, String description,
                                  List<ToolDefinition.ParameterSchema> parameters) {
        ToolDefinition def = ToolDefinition.fromMcp(name, description, parameters);
        tools.put(name, def);
        log.debug("Registered external MCP tool '{}': {}", name, description);
    }

    /** Returns {@code true} if at least one tool is registered. */
    public boolean hasTools() { return !tools.isEmpty(); }

    /** Returns an unmodifiable view of all registered tools. */
    public Collection<ToolDefinition> allTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * Executes the full tool-use loop against the given model and message history.
     *
     * <p>Converts registered tools to Langchain4j {@link ToolSpecification}s,
     * sends them with the messages, and handles tool execution until the LLM
     * produces a final text response.
     *
     * @param model    the Langchain4j chat model to call
     * @param messages mutable message list (modified in place with tool exchanges)
     * @return the LLM's final text response after all tool calls are resolved
     */
    public String executeWithTools(ChatLanguageModel model,
                                   List<ChatMessage> messages) {
        // Build Langchain4j ToolSpecifications from our definitions
        List<ToolSpecification> specs = buildSpecifications();

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            dev.langchain4j.model.output.Response<AiMessage> response =
                model.generate(messages, specs);

            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            // No tool calls -- LLM produced a final text answer
            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() != null ? aiMessage.text() : "";
            }

            // Execute each requested tool and add results to the message list
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                String result = executeTool(toolRequest);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
                log.debug("Tool '{}' returned: {}",
                    toolRequest.name(),
                    result.length() > 100 ? result.substring(0, 100) + "..." : result);
            }
        }

        log.warn("Tool loop reached max iterations ({}) -- returning last response",
            MAX_ITERATIONS);
        // Return whatever the last AI message said
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai && ai.text() != null) {
                return ai.text();
            }
        }
        return "Tool loop exceeded maximum iterations.";
    }

    private String executeTool(ToolExecutionRequest request) {
        ToolDefinition def = tools.get(request.name());
        if (def == null) {
            return "ERROR: Unknown tool '" + request.name() + "'";
        }

        log.info("Invoking tool '{}' [{}] with args: {}",
            def.name(), def.trustLevel(), request.arguments());

        // Parse the JSON arguments from the LLM into positional Object[]
        Object[] args = parseArguments(request.arguments(), def.parameters());
        return def.invoke(args);
    }

    /**
     * Parses tool arguments from the JSON string the LLM produces,
     * converting each value to the correct Java type for the method parameter.
     */
    private static Object[] parseArguments(String json,
            List<ToolDefinition.ParameterSchema> parameters) {
        if (parameters.isEmpty()) return new Object[0];

        Object[] args = new Object[parameters.size()];
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> parsed = mapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});

            for (int i = 0; i < parameters.size(); i++) {
                ToolDefinition.ParameterSchema p = parameters.get(i);
                Object value = parsed.get(p.name());
                if (value == null) {
                    args[i] = defaultForType(p.type());
                    continue;
                }
                args[i] = switch (p.type()) {
                    case "number"  -> ((Number) coerceToNumber(value)).doubleValue();
                    case "integer" -> ((Number) coerceToNumber(value)).intValue();
                    case "boolean" -> Boolean.parseBoolean(value.toString());
                    default        -> value.toString();  // "string" and anything else
                };
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments '{}': {}", json, e.getMessage());
            Arrays.fill(args, "");
        }
        return args;
    }

    private static Object coerceToNumber(Object value) {
        if (value instanceof Number n) return n;
        try { return Double.parseDouble(value.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static Object defaultForType(String type) {
        return switch (type) {
            case "number"  -> 0.0;
            case "integer" -> 0;
            case "boolean" -> false;
            default        -> "";
        };
    }

    private List<ToolSpecification> buildSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (ToolDefinition def : tools.values()) {
            // Build JSON schema for parameters manually -- avoids ToolParameters
            // builder API differences across langchain4j versions.
            var properties = new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            List<String> required = new ArrayList<>();

            for (ToolDefinition.ParameterSchema p : def.parameters()) {
                var prop = properties.putObject(p.name());
                prop.put("type", p.type());
                prop.put("description", p.description());
                required.add(p.name());
            }

            var schema = new com.fasterxml.jackson.databind.node.ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
            schema.put("type", "object");
            schema.set("properties", properties);
            var reqArray = schema.putArray("required");
            required.forEach(reqArray::add);

            specs.add(ToolSpecification.builder()
                .name(def.name())
                .description(def.description())
                .parameters(dev.langchain4j.agent.tool.ToolParameters.builder()
                    .properties(toMap(properties))
                    .required(required)
                    .build())
                .build());
        }
        return specs;
    }

    private static Map<String, Map<String, Object>> toMap(
            com.fasterxml.jackson.databind.node.ObjectNode node) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            Map<String, Object> prop = new LinkedHashMap<>();
            e.getValue().fields().forEachRemaining(f ->
                prop.put(f.getKey(), f.getValue().asText()));
            result.put(e.getKey(), prop);
        });
        return result;
    }
}
