package io.cafeai.core.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a compact JSON schema hint from a Java class or record.
 *
 * <p>Used by {@link PromptRequest#returning(Class)} to inject a structured
 * output instruction into the prompt. The hint is a compact example JSON
 * object — not a full JSON Schema — which is sufficient to guide the model
 * reliably without excessive token usage.
 *
 * <p>Example output for {@code SentimentResult}:
 * <pre>
 *   {"tone":"string","urgency":"string","escalate":false,"keyPhrases":["string"]}
 * </pre>
 *
 * <p>Package-private — not part of the public CafeAI API.
 */
final class SchemaHintBuilder {

    private SchemaHintBuilder() {}

    /**
     * Builds a compact JSON example hint for the given class.
     *
     * <p>Supports:
     * <ul>
     *   <li>Java records — components inspected via {@link Class#getRecordComponents()}</li>
     *   <li>POJOs — public non-static fields inspected via reflection</li>
     * </ul>
     *
     * @param type the target class
     * @return a compact JSON example string
     */
    static String build(Class<?> type) {
        if (type.isRecord()) {
            return buildFromRecord(type);
        }
        return buildFromFields(type);
    }

    /**
     * Builds the full structured output instruction appended to the prompt.
     *
     * @param type     the target class
     * @param hint     the JSON example hint
     * @return the instruction string to append to the prompt
     */
    static String instruction(Class<?> type, String hint) {
        return """

            Respond ONLY with valid JSON matching this structure. \
            No prose before or after the JSON. No markdown code fences.
            """ + hint;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static String buildFromRecord(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null || components.length == 0) {
            return "{}";
        }
        List<String> fields = new ArrayList<>();
        for (RecordComponent rc : components) {
            fields.add("\"" + rc.getName() + "\":" + exampleValue(rc.getType(), rc.getGenericType()));
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String buildFromFields(Class<?> type) {
        List<String> fields = new ArrayList<>();
        for (Field f : type.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            fields.add("\"" + f.getName() + "\":" + exampleValue(f.getType(), f.getGenericType()));
        }
        if (fields.isEmpty()) {
            // Fall back to declared fields if no public fields
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                fields.add("\"" + f.getName() + "\":" + exampleValue(f.getType(), f.getGenericType()));
            }
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String exampleValue(Class<?> type, java.lang.reflect.Type genericType) {
        if (type == String.class)                          return "\"string\"";
        if (type == boolean.class || type == Boolean.class) return "false";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class) return "0";
        if (type == List.class || type == java.util.ArrayList.class) {
            // Try to get the element type
            if (genericType instanceof ParameterizedType pt) {
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> elementType) {
                    return "[" + exampleValue(elementType, elementType) + "]";
                }
            }
            return "[\"string\"]";
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            if (constants.length > 0) {
                // List all enum values as a hint
                List<String> names = new ArrayList<>();
                for (Object c : constants) names.add(c.toString());
                return "\"" + String.join("|", names) + "\"";
            }
            return "\"string\"";
        }
        if (type == java.time.LocalDate.class
                || type == java.time.LocalDateTime.class) return "\"YYYY-MM-DD\"";
        // Nested object — recurse one level
        if (!type.isPrimitive() && !type.getName().startsWith("java.")) {
            try { return build(type); } catch (Exception e) { /* fall through */ }
        }
        return "\"string\"";
    }
}
