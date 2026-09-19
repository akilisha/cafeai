package io.cafeai.core.rag;

import io.cafeai.core.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("chunk size settings")
class ChunkerConfigTest {

    private static AppConfig config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key.name()));
    }

    private static final String TEXT = "word ".repeat(200);   // 1000 characters

    @Test @DisplayName("cafeai.rag.chunk.size and .overlap set the chunk window")
    void configured() {
        var chunks = new Chunker(config(Map.of("cafeai.rag.chunk.size", "100", "cafeai.rag.chunk.overlap", "20")))
            .chunk(TEXT, "doc");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(100));
        assertThat(chunks.size()).isGreaterThan(new Chunker(config(Map.of())).chunk(TEXT, "doc").size());
    }

    @Test @DisplayName("unset, chunks are 512 characters with 64 of overlap, as before")
    void defaults() {
        var chunks = new Chunker(config(Map.of())).chunk(TEXT, "doc");

        assertThat(chunks.get(0).content().length()).isBetween(500, 512);
        assertThat(chunks.size()).isEqualTo(new Chunker().chunk(TEXT, "doc").size());
    }

    @Test @DisplayName("an overlap that is not smaller than the size is refused")
    void invalid() {
        assertThatThrownBy(() -> new Chunker(config(Map.of("cafeai.rag.chunk.overlap", "600"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap");
    }
}
