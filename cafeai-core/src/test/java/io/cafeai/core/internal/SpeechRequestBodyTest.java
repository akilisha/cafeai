package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body sent to OpenAI's speech endpoint is valid JSON that says what was asked. A live call found
 * that it never was: the closing quote after the format was missing, so every synthesis request
 * was refused with "could not parse the JSON body".
 */
@DisplayName("text-to-speech request body")
class SpeechRequestBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test @DisplayName("is valid JSON carrying the model, text, voice and format")
    void validJson() throws Exception {
        JsonNode body = MAPPER.readTree(SpeechRequestBody.json("tts-1", "Hello there.", "alloy", "mp3"));

        assertThat(body.get("model").asText()).isEqualTo("tts-1");
        assertThat(body.get("input").asText()).isEqualTo("Hello there.");
        assertThat(body.get("voice").asText()).isEqualTo("alloy");
        assertThat(body.get("response_format").asText()).isEqualTo("mp3");
        assertThat(body.size()).isEqualTo(4);
    }

    @Test @DisplayName("keeps text with quotes, backslashes, line breaks, tabs and non-ASCII characters exactly")
    void awkwardText() throws Exception {
        String text = "She said \"hi\" \\ then left.\nNext line\r\n\tTabbed — café, 日本語, 🎉,  control.";

        JsonNode body = MAPPER.readTree(SpeechRequestBody.json("tts-1", text, "nova", "opus"));

        assertThat(body.get("input").asText()).isEqualTo(text);
        assertThat(body.get("response_format").asText()).isEqualTo("opus");
    }

    @Test @DisplayName("a voice or format cannot break out of its field")
    void fieldsAreEscaped() throws Exception {
        JsonNode body = MAPPER.readTree(SpeechRequestBody.json("tts-1", "x", "a\"b", "m\"p3"));

        assertThat(body.get("voice").asText()).isEqualTo("a\"b");
        assertThat(body.get("response_format").asText()).isEqualTo("m\"p3");
    }
}
