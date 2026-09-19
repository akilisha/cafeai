package io.cafeai.core.cache;

import io.cafeai.core.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("semantic cache settings")
class CacheConfigTest {

    private static final String NS = "ns";
    private static final String QUESTION = "How do I reset my password on the customer portal";

    private static AppConfig config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key.name()));
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test @DisplayName("cafeai.cache.ttl sets how long an entry is served")
    void ttl() {
        var clock = new TestClock();
        var cache = InMemorySemanticCache.builder(TestEmbeddings.bagOfWords(), config(Map.of("cafeai.cache.ttl", "2m")))
            .clock(clock).build();
        cache.store(NS, QUESTION, "answer");

        clock.advance(Duration.ofSeconds(90));
        assertThat(cache.lookup(NS, QUESTION)).isPresent();
        clock.advance(Duration.ofSeconds(40));
        assertThat(cache.lookup(NS, QUESTION)).isEmpty();
    }

    @Test @DisplayName("unset, entries live an hour, as before")
    void ttlDefault() {
        var clock = new TestClock();
        var cache = InMemorySemanticCache.builder(TestEmbeddings.bagOfWords(), config(Map.of()))
            .clock(clock).build();
        cache.store(NS, QUESTION, "answer");

        clock.advance(Duration.ofMinutes(59));
        assertThat(cache.lookup(NS, QUESTION)).isPresent();
        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.lookup(NS, QUESTION)).isEmpty();
    }

    @Test @DisplayName("cafeai.cache.entries bounds the cache")
    void entries() {
        var cache = InMemorySemanticCache.builder(TestEmbeddings.bagOfWords(), config(Map.of("cafeai.cache.entries", "1")))
            .build();
        cache.store(NS, "alpha alpha alpha alpha", "A");
        cache.store(NS, "bravo bravo bravo bravo", "B");

        assertThat(cache.size()).isEqualTo(1);
    }

    @Test @DisplayName("cafeai.cache.response.chars refuses a longer response")
    void responseChars() {
        var cache = InMemorySemanticCache.builder(TestEmbeddings.bagOfWords(), config(Map.of("cafeai.cache.response.chars", "10")))
            .build();

        assertThat(cache.store(NS, QUESTION, "x".repeat(11))).isFalse();
        assertThat(cache.store(NS, QUESTION, "x".repeat(10))).isTrue();
    }

    @Test @DisplayName("a setter on the builder still wins over the setting")
    void fluentWins() {
        var clock = new TestClock();
        var cache = InMemorySemanticCache.builder(TestEmbeddings.bagOfWords(), config(Map.of("cafeai.cache.ttl", "2m")))
            .ttl(Duration.ofMinutes(10)).clock(clock).build();
        cache.store(NS, QUESTION, "answer");

        clock.advance(Duration.ofMinutes(5));
        assertThat(cache.lookup(NS, QUESTION)).isPresent();
    }

    @Test @DisplayName("a value the cache cannot use is refused, naming the setting")
    void invalid() {
        assertThatThrownBy(() -> InMemorySemanticCache.builder(
                TestEmbeddings.bagOfWords(), config(Map.of("cafeai.cache.threshold", "2"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cafeai.cache.threshold");
    }
}
