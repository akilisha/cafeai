package io.cafeai.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(STRING_KEY.name());
        System.clearProperty(INT_KEY.name());
        System.clearProperty(BOOL_KEY.name());
        System.clearProperty(DURATION_KEY.name());
    }

    @Test
    @DisplayName("returns the key's own default when nothing is set anywhere")
    void get_returnsDefault_whenUnset() {
        assertThat(AppConfig.ambient().get(STRING_KEY)).isEqualTo("default-value");
        assertThat(AppConfig.ambient().get(INT_KEY)).isEqualTo(42);
    }

    @Test
    @DisplayName("a system property overrides the default")
    void get_systemPropertyOverridesDefault() {
        System.setProperty(STRING_KEY.name(), "from-system-property");
        assertThat(AppConfig.ambient().get(STRING_KEY)).isEqualTo("from-system-property");
    }

    @Test
    @DisplayName("parses Integer, Boolean, and Duration from their raw string form")
    void get_parsesEachSupportedType() {
        System.setProperty(INT_KEY.name(), "7");
        System.setProperty(BOOL_KEY.name(), "true");
        System.setProperty(DURATION_KEY.name(), "5m");

        assertThat(AppConfig.ambient().get(INT_KEY)).isEqualTo(7);
        assertThat(AppConfig.ambient().get(BOOL_KEY)).isTrue();
        assertThat(AppConfig.ambient().get(DURATION_KEY)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Duration accepts plain seconds with no suffix")
    void get_durationAcceptsPlainSeconds() {
        System.setProperty(DURATION_KEY.name(), "90");
        assertThat(AppConfig.ambient().get(DURATION_KEY)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("an unparsable value throws, naming the key and the bad value")
    void get_invalidValue_throws() {
        System.setProperty(INT_KEY.name(), "not-a-number");
        assertThatThrownBy(() -> AppConfig.ambient().get(INT_KEY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(INT_KEY.name())
            .hasMessageContaining("not-a-number");
    }

    @Test
    @DisplayName("envVarName() converts dots to underscores and uppercases")
    void envVarName_convention() {
        assertThat(STRING_KEY.envVarName()).isEqualTo("TEST_APPCONFIG_STRING");
    }

    @Test
    @DisplayName("declaring a key registers it in the catalog")
    void declaringAKey_registersItInTheCatalog() {
        assertThat(ConfigCatalog.known()).containsKey(STRING_KEY.name());
        assertThat(ConfigCatalog.known().get(STRING_KEY.name()).description())
            .isEqualTo("A test string key.");
    }
}
