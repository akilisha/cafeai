package io.cafeai.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.helidon.extensions.mcp.server.McpParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON for tool arguments and schemas. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() { }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A tool call's arguments as plain Java: maps, lists, strings, numbers, booleans. */
    static Map<String, Object> arguments(McpParameters arguments) {
        Object value = toJava(arguments);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static Object toJava(McpParameters p) {
        if (p == null || !p.isPresent()) return null;
        if (p.isString()) return p.asString().orElse(null);
        if (p.isNumber()) {
            double d = p.asDouble().orElse(0d);
            long l = (long) d;
            return d == l && !Double.isInfinite(d) ? (Object) l : d;
        }
        try {
            var map = p.asMap();
            if (map.isPresent()) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.get().forEach((k, v) -> out.put(k, toJava(v)));
                return out;
            }
        } catch (RuntimeException notAnObject) { /* try the next shape */ }
        try {
            var list = p.asList();
            if (list.isPresent()) {
                List<Object> out = new ArrayList<>();
                for (McpParameters item : list.get()) out.add(toJava(item));
                return out;
            }
        } catch (RuntimeException notAList) { /* try the next shape */ }
        try {
            var bool = p.asBoolean();
            if (bool.isPresent()) return bool.get();
        } catch (RuntimeException notABoolean) { /* nothing left */ }
        return null;
    }

    /** The JSON schema of {@code type}'s properties, as LangChain4j describes it for tools. */
    static Map<String, Object> schemaOf(Class<?> type) {
        return schemaOf(JsonSchemaElementUtils.jsonSchemaElementFrom(type));
    }

    static Map<String, Object> schemaOf(JsonSchemaElement element) {
        if (element == null) return emptyObjectSchema();
        return new LinkedHashMap<>(JsonSchemaElementUtils.toMap(element));
    }

    static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }
}
