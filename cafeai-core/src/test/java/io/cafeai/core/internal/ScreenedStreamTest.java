package io.cafeai.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScreenedStream.releasePoint")
class ScreenedStreamTest {

    @Test @DisplayName("releases after the last sentence end or line break, and holds a sentence still being written")
    void releasePoints() {
        assertThat(ScreenedStream.releasePoint("One. Two")).isEqualTo(5);
        assertThat(ScreenedStream.releasePoint("Really? Yes! And")).isEqualTo(13);
        assertThat(ScreenedStream.releasePoint("a line\nnext")).isEqualTo(7);
        assertThat(ScreenedStream.releasePoint("No end yet")).isZero();
        assertThat(ScreenedStream.releasePoint("pi is 3.14 roughly")).isZero();   // a dot inside a number ends nothing
    }

    @Test @DisplayName("text with no sentence end is released at a word break once enough is held")
    void longText() {
        String held = "word ".repeat(100);   // 500 characters
        int cut = ScreenedStream.releasePoint(held);
        assertThat(cut).isEqualTo(held.length());
        assertThat(ScreenedStream.releasePoint("x".repeat(ScreenedStream.MAX_HELD))).isEqualTo(ScreenedStream.MAX_HELD);
    }
}
