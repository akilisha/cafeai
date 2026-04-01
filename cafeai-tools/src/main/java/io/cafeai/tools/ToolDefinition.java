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
     * Coerces numeric arguments to the exact primitive type the method expects.
     * All exceptions are caught and returned as error strings — tools
     * never propagate exceptions to the LLM.
     *
     * @param args positional arguments matching {@link #parameters()} order
     * @return the tool's result as a string, or an error message prefixed with "ERROR:"
     */
    public String invoke(Object... args) {
        try {
            // Coerce each argument to the exact type the method parameter expects
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[]   coerced   = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                coerced[i] = coerce(args[i], paramTypes[i]);
            }
            Object result = method.invoke(instance, coerced);
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

    private static Object coerce(Object value, Class<?> target) {
        if (value == null) return null;
        if (target == double.class || target == Double.class)
            return value instanceof Number n ? n.doubleValue()
                 : Double.parseDouble(value.toString());
        if (target == int.class || target == Integer.class)
            return value instanceof Number n ? n.intValue()
                 : Integer.parseInt(value.toString().replace(".0",""));
        if (target == long.class || target == Long.class)
            return value instanceof Number n ? n.longValue()
                 : Long.parseLong(value.toString());
        if (target == boolean.class || target == Boolean.class)
            return value instanceof Boolean b ? b
                 : Boolean.parseBoolean(value.toString());
        return value; // String and everything else — pass through
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
            // With -parameters compiler flag, p.getName() returns the real name.
            // Without it, Java returns arg0, arg1, etc. Either way, include the
            // Java type in the description so the LLM knows what to pass.
            String paramName = p.getName();
            String typeName  = p.getType().getSimpleName();
            String desc      = paramName.startsWith("arg")
                ? typeName + " parameter (position " + paramName.substring(3) + ")"
                : paramName + " (" + typeName + ")";
            params.add(new ParameterSchema(paramName, javaTypeToJsonType(p.getType()), desc));
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
