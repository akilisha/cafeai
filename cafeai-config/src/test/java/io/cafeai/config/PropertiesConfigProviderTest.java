package io.cafeai.config;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PropertiesConfigProvider")
class PropertiesConfigProviderTest {

    private static final ConfigKey<String> KEY_1 = ConfigKey.of(
        "test.pcp.key1", String.class, "coded-default-1", "Overridden by the profile overlay in this test.");
    private static final ConfigKey<String> KEY_2 = ConfigKey.of(
        "test.pcp.key2", String.class, "coded-default-2", "Present only in the base file.");
    private static final ConfigKey<String> UNSET_KEY = ConfigKey.of(
        "test.pcp.unset", String.class, "coded-default-3", "Present in neither file.");

    @AfterEach
    void clearProfile() {
        System.clearProperty("cafeai.profile");
        System.clearProperty(KEY_1.name());
    }

    @Test
    @DisplayName("loads application.properties when no profile is active")
    void loadsBaseFile_noProfile() {
        AppConfig config = new PropertiesConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("base-value");
        assertThat(config.get(KEY_2)).isEqualTo("base-value-2");
    }

    @Test
    @DisplayName("a profile overlay overrides only the keys it defines")
    void profileOverlay_overridesOnlyItsOwnKeys() {
        System.setProperty("cafeai.profile", "testprofile");
        AppConfig config = new PropertiesConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("profile-value"); // overridden
        assertThat(config.get(KEY_2)).isEqualTo("base-value-2");  // untouched by the overlay
    }

    @Test
    @DisplayName("a key present in neither file falls back to the ConfigKey's own default")
    void unsetKey_fallsBackToCodedDefault() {
        AppConfig config = new PropertiesConfigProvider().config();
        assertThat(config.get(UNSET_KEY)).isEqualTo("coded-default-3");
    }

    @Test
    @DisplayName("a nonexistent profile is silently ignored, not an error")
    void nonexistentProfile_silentlyIgnored() {
        System.setProperty("cafeai.profile", "no-such-profile");
        AppConfig config = new PropertiesConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("base-value"); // base file only
    }

    @Test
    @DisplayName("AppConfig.load() discovers this provider via ServiceLoader and layers under system properties")
    void appConfigLoad_discoversProviderAndRespectsPrecedence() {
        // Without any system property: the file value wins over the coded default.
        assertThat(AppConfig.load().get(KEY_1)).isEqualTo("base-value");

        // A system property still outranks the file.
        System.setProperty(KEY_1.name(), "from-system-property");
        assertThat(AppConfig.load().get(KEY_1)).isEqualTo("from-system-property");
    }
}
