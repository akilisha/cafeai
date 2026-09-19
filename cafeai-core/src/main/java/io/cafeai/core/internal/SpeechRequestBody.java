package io.cafeai.core.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON body of a request to OpenAI's {@code /v1/audio/speech}. Built with a JSON writer, not by
 * joining strings: text to be spoken can hold quotes, backslashes, tabs, line breaks and characters
 * outside ASCII, and one missing quote in a hand-built body makes the whole call fail.
 */
final class SpeechRequestBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpeechRequestBody() {}

    static String json(String model, String text, String voice, String format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("voice", voice);
        body.put("response_format", format);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build the speech request body", e);
        }
    }
}
