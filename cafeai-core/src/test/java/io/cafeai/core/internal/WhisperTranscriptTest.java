package io.cafeai.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transcript in a Whisper response. It used to be cut out by looking for the first two quote
 * characters after the word "text", which ends a transcript at its first quotation mark.
 */
@DisplayName("Whisper transcript parsing")
class WhisperTranscriptTest {

    @Test @DisplayName("a plain transcript")
    void plain() {
        assertThat(AudioMessageBuilder.parseTranscript("{\"text\":\"The quick brown fox.\"}"))
            .isEqualTo("The quick brown fox.");
    }

    @Test @DisplayName("a transcript with quotation marks is not cut off at the first one")
    void quotationMarks() {
        assertThat(AudioMessageBuilder.parseTranscript("{\"text\": \"She said \\\"hello\\\" and left.\"}"))
            .isEqualTo("She said \"hello\" and left.");
    }

    @Test @DisplayName("escaped line breaks, backslashes and unicode come back as the characters they stand for")
    void escapes() {
        assertThat(AudioMessageBuilder.parseTranscript("{\"text\":\"one\\ntwo \\\\ caf\\u00e9 \\u65e5\"}"))
            .isEqualTo("one\ntwo \\ café 日");
    }

    @Test @DisplayName("an empty transcript is an empty string, and other fields do not matter")
    void empty() {
        assertThat(AudioMessageBuilder.parseTranscript("{\"language\":\"en\",\"text\":\"\",\"duration\":1.5}")).isEmpty();
    }

    @Test @DisplayName("a response without text, or with text that is not a string, is refused with the response quoted")
    void unexpected() {
        assertThatThrownBy(() -> AudioMessageBuilder.parseTranscript("{\"error\":\"no\"}"))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("Unexpected Whisper response");
        assertThatThrownBy(() -> AudioMessageBuilder.parseTranscript("{\"text\":5}"))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("Unexpected Whisper response");
        assertThatThrownBy(() -> AudioMessageBuilder.parseTranscript("not json"))
            .isInstanceOf(RuntimeException.class).hasMessageContaining("Unexpected Whisper response");
    }
}
