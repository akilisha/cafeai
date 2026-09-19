package io.cafeai.core.config;

import io.cafeai.core.JsonOptions;
import io.cafeai.core.ai.Nvidia;
import io.cafeai.core.ai.RetryPolicy;
import io.cafeai.core.cache.SemanticCache;
import io.cafeai.core.guardrails.PromptLeakGuardRail;
import io.cafeai.core.memory.HistoryPolicy;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.rag.RagIngestion;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tunable values that used to be constants: each has a key whose default is the value the
 * constant had, so an application that sets nothing behaves as before. The defaults are pinned
 * here so that changing one is a decision, not an accident.
 */
@DisplayName("tunable values are settings, and their defaults are unchanged")
class SweepConfigTest {

    /** An AppConfig that resolves from a map, as a provider would from a file or the environment. */
    static AppConfig config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key.name()));
    }

    // -- the defaults ---------------------------------------------------------------------------------

    @Test @DisplayName("every default is the value the constant had")
    void defaults() {
        assertThat(RetryPolicy.ATTEMPTS.defaultValue()).isEqualTo(3);
        assertThat(RetryPolicy.BACKOFF.defaultValue()).isEqualTo(Duration.ofSeconds(5));
        assertThat(Nvidia.TIMEOUT.defaultValue()).isEqualTo(Duration.ofMinutes(5));
        assertThat(SemanticCache.THRESHOLD.defaultValue()).isEqualTo(0.95);
        assertThat(SemanticCache.MIN_OVERLAP.defaultValue()).isEqualTo(0.80);
        assertThat(SemanticCache.MAX_LENGTH_RATIO.defaultValue()).isEqualTo(1.25);
        assertThat(SemanticCache.TTL.defaultValue()).isEqualTo(Duration.ofHours(1));
        assertThat(SemanticCache.MAX_ENTRIES.defaultValue()).isEqualTo(1_000);
        assertThat(SemanticCache.MAX_RESPONSE_CHARS.defaultValue()).isEqualTo(8_000);
        assertThat(JsonOptions.BODY_LIMIT.defaultValue()).isEqualTo(100 * 1024L);
        assertThat(RagIngestion.CHUNK_SIZE.defaultValue()).isEqualTo(512);
        assertThat(RagIngestion.CHUNK_OVERLAP.defaultValue()).isEqualTo(64);
        assertThat(Response.FILE_BLOCK.defaultValue()).isEqualTo(64 * 1024);
        assertThat(PromptLeakGuardRail.WINDOW.defaultValue()).isEqualTo(8);
        assertThat(RedisConfig.SESSION_TTL.defaultValue()).isEqualTo(Duration.ofHours(24));
        assertThat(HistoryPolicy.WINDOW.defaultValue()).isEqualTo(20);
        assertThat(HistoryPolicy.BUDGET.defaultValue()).isEqualTo(4000);
        assertThat(HistoryPolicy.SUMMARY_AFTER.defaultValue()).isEqualTo(20);
        assertThat(HistoryPolicy.SUMMARY_KEEP.defaultValue()).isEqualTo(6);
        assertThat(HistoryPolicy.SUMMARY_WORDS.defaultValue()).isEqualTo(200);
    }

    @Test @DisplayName("with no configuration provider the objects still get those defaults")
    void objectsGetDefaults() {
        assertThat(RetryPolicy.onRateLimit().maxAttempts()).isEqualTo(3);
        assertThat(JsonOptions.defaultLimit()).isEqualTo(100 * 1024L);
        assertThat(RedisConfig.builder().build().sessionTtl()).isEqualTo(Duration.ofHours(24));
    }

    // -- AppConfig.apply -------------------------------------------------------------------------------

    @Test @DisplayName("apply() hands the configured value to a setter")
    void applyPasses() {
        int[] seen = new int[1];

        config(Map.of("cafeai.retry.attempts", "7")).apply(RetryPolicy.ATTEMPTS, v -> seen[0] = v);

        assertThat(seen[0]).isEqualTo(7);
    }

    @Test @DisplayName("apply() names the setting when its setter refuses the value")
    void applyNamesTheKey() {
        assertThatThrownBy(() -> config(Map.of("cafeai.retry.attempts", "0"))
                .apply(RetryPolicy.ATTEMPTS, v -> { throw new IllegalArgumentException("must be >= 1"); }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cafeai.retry.attempts")
            .hasMessageContaining("\"0\"")
            .hasMessageContaining("must be >= 1");
    }
}
