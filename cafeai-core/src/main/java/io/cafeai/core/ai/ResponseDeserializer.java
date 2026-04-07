package io.cafeai.core.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Strips markdown fences from LLM responses and deserialises to a typed object.
 *
 * <p>LLMs frequently wrap JSON output in markdown code fences even when
 * instructed not to. This class handles all common fence variants:
 * <ul>
 *   <li>{@code ```json ... ```}</li>
 *   <li>{@code ``` ... ```}</li>
 *   <li>Leading/trailing whitespace</li>
 *   <li>Prose before or after the JSON block</li>
 * </ul>
 *
 * <p>Package-private — not part of the public CafeAI API.
 */
final class ResponseDeserializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResponseDeserializer() {}

    /**
     * Strips fences from the raw LLM response and deserialises to the target type.
     *
     * @param raw    the raw LLM response text (may contain markdown fences)
     * @param type   the target Java type
     * @param <T>    the target type parameter
     * @return the deserialised object
     * @throws StructuredOutputException if the response cannot be deserialised
     */
    static <T> T deserialise(String raw, Class<T> type) {
        String clean = strip(raw);
        try {
            return MAPPER.readValue(clean, type);
        } catch (Exception e) {
            throw new StructuredOutputException(
                "Failed to deserialise LLM response to " + type.getSimpleName() + ". " +
                "Raw response: [" + raw + "]", e);
        }
    }

    /**
     * Strips markdown fences and extracts the JSON content from a raw LLM response.
     *
     * <p>Handles all common fence variants. If no fences are present, trims
     * whitespace. If the text contains a JSON object or array that is preceded
     * or followed by prose, extracts it.
     *
     * @param raw the raw LLM response text
     * @return the cleaned JSON string
     */
    static String strip(String raw) {
        if (raw == null) return "";

        String s = raw.trim();

        // Strip ```json ... ``` fences
        if (s.startsWith("```json")) {
            s = s.substring(7);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            return s.trim();
        }

        // Strip ``` ... ``` fences
        if (s.startsWith("```")) {
            s = s.substring(3);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            return s.trim();
        }

        // Extract JSON object or array if surrounded by prose
        int braceStart  = s.indexOf('{');
        int bracketStart = s.indexOf('[');

        if (braceStart >= 0 || bracketStart >= 0) {
            int start;
            char open, close;
            if (braceStart >= 0 && (bracketStart < 0 || braceStart < bracketStart)) {
                start = braceStart;
                open  = '{';
                close = '}';
            } else {
                start = bracketStart;
                open  = '[';
                close = ']';
            }
            // Find the matching closing delimiter
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                    inString = !inString;
                } else if (!inString) {
                    if (c == open)  depth++;
                    else if (c == close) {
                        depth--;
                        if (depth == 0) return s.substring(start, i + 1).trim();
                    }
                }
            }
        }

        return s;
    }

    /**
     * Thrown when the LLM response cannot be deserialised to the requested type.
     *
     * <p>Contains the original raw response for debugging and logging.
     */
    public static final class StructuredOutputException extends RuntimeException {
        public StructuredOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
