package io.cafeai.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AppConfig")
class AppConfigTest {

    private static final ConfigKey<String> STRING_KEY = ConfigKey.of(
        "test.appconfig.string", String.class, "default-value", "A test string key.");
    private static final ConfigKey<Integer> INT_KEY = ConfigKey.of(
        "test.appconfig.int", Integer.class, 42, "A test int key.");
    private static final ConfigKey<Boolean> BOOL_KEY = ConfigKey.of(
        "test.appconfig.bool", Boolean.class, false, "A test boolean key.");
    private static final ConfigKey<Duration> DURATION_KEY = ConfigKey.of(
        "test.appconfig.duration", Duration.class, Duration.ofSeconds(60), "A test duration key.");

    @Test
    @DisplayName("returns the key's own default when the AppConfig resolves nothing")
    void get_returnsDefault_whenNothingResolved() {
        AppConfig empty = key -> Optional.empty();
        assertThat(empty.get(STRING_KEY)).isEqualTo("default-value");
        assertThat(empty.get(INT_KEY)).isEqualTo(42);
    }

    @Test
    @DisplayName("a resolved value overrides the default")
    void get_resolvedValueOverridesDefault() {
        AppConfig overridden = key -> Optional.of("from-somewhere");
        assertThat(overridden.get(STRING_KEY)).isEqualTo("from-somewhere");
    }

    @Test
    @DisplayName("parses Integer, Boolean, and Duration from their raw string form")
    void get_parsesEachSupportedType() {
        AppConfig config = key -> switch (key.name()) {
            case "test.appconfig.int" -> Optional.of("7");
            case "test.appconfig.bool" -> Optional.of("true");
            case "test.appconfig.duration" -> Optional.of("5m");
            default -> Optional.empty();
        };

        assertThat(config.get(INT_KEY)).isEqualTo(7);
        assertThat(config.get(BOOL_KEY)).isTrue();
        assertThat(config.get(DURATION_KEY)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Duration accepts plain seconds with no suffix")
    void get_durationAcceptsPlainSeconds() {
        AppConfig config = key -> Optional.of("90");
        assertThat(config.get(DURATION_KEY)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("an unparsable value throws, naming the key and the bad value")
    void get_invalidValue_throws() {
        AppConfig config = key -> Optional.of("not-a-number");
        assertThatThrownBy(() -> config.get(INT_KEY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(INT_KEY.name())
            .hasMessageContaining("not-a-number");
    }

    @Test
    @DisplayName("load() returns the coded default when no ConfigProvider is on the classpath")
    void load_returnsCodedDefault_whenCafeaiConfigAbsent() {
        // cafeai-core's own test classpath has no cafeai-config dependency,
        // so this exercises the real "module absent" path, not a fake.
        assertThat(AppConfig.load().get(STRING_KEY)).isEqualTo("default-value");
    }

    @Test
    @DisplayName("declaring a key registers it in the catalog")
    void declaringAKey_registersItInTheCatalog() {
        assertThat(ConfigCatalog.known()).containsKey(STRING_KEY.name());
        assertThat(ConfigCatalog.known().get(STRING_KEY.name()).description())
            .isEqualTo("A test string key.");
    }
}
