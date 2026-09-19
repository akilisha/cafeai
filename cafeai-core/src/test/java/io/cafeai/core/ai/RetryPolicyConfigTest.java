package io.cafeai.core.ai;

import io.cafeai.core.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RetryPolicy settings")
class RetryPolicyConfigTest {

    private static AppConfig config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key.name()));
    }

    @Test @DisplayName("cafeai.retry.attempts and cafeai.retry.backoff set what onRateLimit() starts with")
    void configured() {
        var policy = RetryPolicy.onRateLimit(config(Map.of(
            "cafeai.retry.attempts", "6", "cafeai.retry.backoff", "2s")));

        assertThat(policy.maxAttempts()).isEqualTo(6);
        assertThat(policy.backoff()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test @DisplayName("unset, it is 3 attempts and a 5 second backoff, as before")
    void defaults() {
        var policy = RetryPolicy.onRateLimit(config(Map.of()));

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.backoff()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test @DisplayName("fluent setters still win over the settings")
    void fluentWins() {
        var policy = RetryPolicy.onRateLimit(config(Map.of("cafeai.retry.attempts", "6")))
            .maxAttempts(2);

        assertThat(policy.maxAttempts()).isEqualTo(2);
    }

    @Test @DisplayName("a value the policy cannot use is refused, naming the setting")
    void invalid() {
        assertThatThrownBy(() -> RetryPolicy.onRateLimit(config(Map.of("cafeai.retry.attempts", "0"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cafeai.retry.attempts");
    }
}
