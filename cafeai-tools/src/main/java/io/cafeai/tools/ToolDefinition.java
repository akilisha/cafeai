package io.cafeai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * A resolved, invocable tool definition.
 *
 * <p>Created by {@link ToolRegistry} when {@code app.tool(instance)} is called.
 * Wraps a {@link CafeAITool}-annotated Java method with its schema and
 * invocation logic.
 *
 * <p>Trust levels:
 * <ul>
 *   <li>{@link TrustLevel#INTERNAL} — Java methods registered via {@code app.tool()}</li>
 *   <li>{@link TrustLevel#EXTERNAL} — tools from MCP servers registered via {@code app.mcp()}</li>
 * </ul>
 */
public final class ToolDefinition {

    private static final Logger log = LoggerFactory.getLogger(ToolDefinition.class);

    private final String     name;
    private final String     description;
    private final List<ParameterSchema> parameters;
    private final TrustLevel trustLevel;
    private final Object     instance;
    private final Method     method;

    private ToolDefinition(String name, String description,
                           List<ParameterSchema> parameters,
                           TrustLevel trustLevel,
                           Object instance, Method method) {
        this.name        = name;
        this.description = description;
        this.parameters  = Collections.unmodifiableList(parameters);
        this.trustLevel  = trustLevel;
        this.instance    = instance;
        this.method      = method;
    }

    /**
     * Invokes the tool with the given arguments.
     * All exceptions are caught and returned as error strings — tools
     * never propagate exceptions to the LLM.
     *
     * @param args positional arguments matching {@link #parameters()} order
     * @return the tool's result as a string, or an error message prefixed with "ERROR:"
     */
    public String invoke(Object... args) {
        try {
            Object result = method.invoke(instance, args);
            return result != null ? result.toString() : "";
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Tool '{}' threw an exception: {}", name, cause.getMessage());
            return "ERROR: " + cause.getMessage();
        } catch (Exception e) {
            log.warn("Tool '{}' invocation failed: {}", name, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public String           name()        { return name; }
    public String           description() { return description; }
    public List<ParameterSchema> parameters() { return parameters; }
    public TrustLevel       trustLevel()  { return trustLevel; }

    /**
     * Creates a {@code ToolDefinition} from a {@link CafeAITool}-annotated method.
     */
    static ToolDefinition fromMethod(Object instance, Method method) {
        CafeAITool ann = method.getAnnotation(CafeAITool.class);
        if (ann == null) {
            throw new IllegalArgumentException(
                "Method " + method.getName() + " is not annotated with @CafeAITool");
        }

        String toolName = ann.name().isBlank() ? method.getName() : ann.name();

        List<ParameterSchema> params = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            params.add(new ParameterSchema(
                p.getName(),
                javaTypeToJsonType(p.getType()),
                "Parameter " + p.getName()));
        }

        method.setAccessible(true);
        return new ToolDefinition(toolName, ann.value(), params,
                                  TrustLevel.INTERNAL, instance, method);
    }

    /**
     * Creates an EXTERNAL {@code ToolDefinition} for a tool discovered from an MCP server.
     */
    static ToolDefinition fromMcp(String name, String description,
                                   List<ParameterSchema> parameters) {
        return new ToolDefinition(name, description, parameters,
                                  TrustLevel.EXTERNAL, null, null);
    }

    private static String javaTypeToJsonType(Class<?> type) {
        if (type == String.class || type == String[].class) return "string";
        if (type == int.class    || type == Integer.class)  return "integer";
        if (type == long.class   || type == Long.class)     return "integer";
        if (type == double.class || type == Double.class)   return "number";
        if (type == boolean.class|| type == Boolean.class)  return "boolean";
        return "string"; // default
    }

    /** Parameter schema for LLM tool descriptions. */
    public record ParameterSchema(String name, String type, String description) {}

    /** Trust level of a tool — affects logging and observability. */
    public enum TrustLevel { INTERNAL, EXTERNAL }
}
