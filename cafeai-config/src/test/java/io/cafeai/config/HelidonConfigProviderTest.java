package io.cafeai.config;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HelidonConfigProvider")
class HelidonConfigProviderTest {

    private static final ConfigKey<String> KEY_1 = ConfigKey.of(
        "test.pcp.key1", String.class, "coded-default-1", "Overridden by the profile overlay in this test.");
    private static final ConfigKey<String> KEY_2 = ConfigKey.of(
        "test.pcp.key2", String.class, "coded-default-2", "Present only in the base file.");
    private static final ConfigKey<String> UNSET_KEY = ConfigKey.of(
        "test.pcp.unset", String.class, "coded-default-3", "Present in neither file.");
    private static final ConfigKey<String> YAML_KEY = ConfigKey.of(
        "test.pcp.yaml.key", String.class, "coded-default-yaml", "Present only in application.yaml.");

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("cafeai.profile");
        System.clearProperty("cafeai.config.file");
        System.clearProperty(KEY_1.name());
    }

    @Test
    @DisplayName("loads application.properties when no profile is active")
    void loadsBaseFile_noProfile() {
        AppConfig config = new HelidonConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("base-value");
        assertThat(config.get(KEY_2)).isEqualTo("base-value-2");
    }

    @Test
    @DisplayName("also reads application.yaml transparently, alongside the .properties file")
    void alsoReadsYaml() {
        AppConfig config = new HelidonConfigProvider().config();
        assertThat(config.get(YAML_KEY)).isEqualTo("from-yaml");
    }

    @Test
    @DisplayName("a profile overlay overrides only the keys it defines")
    void profileOverlay_overridesOnlyItsOwnKeys() {
        System.setProperty("cafeai.profile", "testprofile");
        AppConfig config = new HelidonConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("profile-value"); // overridden
        assertThat(config.get(KEY_2)).isEqualTo("base-value-2");  // untouched by the overlay
    }

    @Test
    @DisplayName("a key present in neither file falls back to the ConfigKey's own default")
    void unsetKey_fallsBackToCodedDefault() {
        AppConfig config = new HelidonConfigProvider().config();
        assertThat(config.get(UNSET_KEY)).isEqualTo("coded-default-3");
    }

    @Test
    @DisplayName("a nonexistent profile is silently ignored, not an error")
    void nonexistentProfile_silentlyIgnored() {
        System.setProperty("cafeai.profile", "no-such-profile");
        AppConfig config = new HelidonConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("base-value"); // base file only
    }

    @Test
    @DisplayName("an external file (CAFEAI_CONFIG_FILE / cafeai.config.file) overrides the bundled classpath files")
    void externalFile_overridesClasspathFiles(@TempDir Path tempDir) throws Exception {
        Path external = tempDir.resolve("external.properties");
        Files.writeString(external, "test.pcp.key1=from-external-file\n");
        System.setProperty("cafeai.config.file", external.toString());

        AppConfig config = new HelidonConfigProvider().config();

        assertThat(config.get(KEY_1)).isEqualTo("from-external-file"); // beats the classpath base file
        assertThat(config.get(KEY_2)).isEqualTo("base-value-2");       // untouched, still from the base file
    }

    @Test
    @DisplayName("a system property outranks every file, resolved entirely within this provider")
    void systemProperty_outranksFiles() {
        AppConfig config = new HelidonConfigProvider().config();
        assertThat(config.get(KEY_1)).isEqualTo("base-value");

        System.setProperty(KEY_1.name(), "from-system-property");
        assertThat(new HelidonConfigProvider().config().get(KEY_1)).isEqualTo("from-system-property");
    }

    @Test
    @DisplayName("AppConfig.load() discovers this provider via ServiceLoader")
    void appConfigLoad_discoversProvider() {
        assertThat(AppConfig.load().get(KEY_1)).isEqualTo("base-value");
    }
}
