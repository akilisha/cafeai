package io.cafeai.config;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.spi.ConfigProvider;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Loads application configuration via Helidon Config — the same engine
 * {@code cafeai-core} already builds its HTTP layer on (ADR-001), applied
 * here instead of hand-rolling a properties merger. {@code helidon-config}
 * and {@code helidon-config-yaml} are dependencies of this module only; no
 * other module ever sees {@code io.helidon.config} directly, only
 * {@code AppConfig}/{@code ConfigKey} in {@code cafeai-core}.
 *
 * <p>Both {@code .properties} and {@code .yaml}/{@code .yml} are recognised
 * transparently — Helidon's {@code ConfigParser} SPI picks the format by
 * file extension, so an application can use either without this class
 * caring which. Recognised files, profile overlay first (so it takes
 * precedence over the base file):
 *
 * <pre>
 *   application-{profile}.yaml / .yml / .properties
 *   application.yaml / .yml / .properties
 * </pre>
 *
 * <p>Profile is read from the {@code CAFEAI_PROFILE} environment variable or
 * the {@code cafeai.profile} system property (the latter wins if both are
 * set — same precedence as every other key). Every source is optional — a
 * missing file is silently skipped, not an error, since an application may
 * configure entirely through environment variables and ship no file at all.
 *
 * <p>This provider does not itself check environment variables or system
 * properties; {@link AppConfig#load()} already does that before ever
 * reaching here.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConfigProvider}
 */
public final class HelidonConfigProvider implements ConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(HelidonConfigProvider.class);
    private static final List<String> EXTENSIONS = List.of("yaml", "yml", "properties");

    private final Config config;

    public HelidonConfigProvider() {
        List<Supplier<? extends ConfigSource>> sources = new ArrayList<>();
        String profile = resolveProfile();
        if (profile != null && !profile.isBlank()) {
            addSourcesFor("application-" + profile.trim(), sources);
        }
        addSourcesFor("application", sources);

        this.config = Config.builder().sources(sources).build();
        log.info("Configuration sources checked: {}{}", sources.size(),
                profile != null && !profile.isBlank() ? " (profile: " + profile + ")" : "");
    }

    private static void addSourcesFor(String baseName, List<Supplier<? extends ConfigSource>> sources) {
        for (String ext : EXTENSIONS) {
            sources.add(ConfigSources.classpath(baseName + "." + ext).optional());
        }
    }

    private static String resolveProfile() {
        String sysProp = System.getProperty("cafeai.profile");
        if (sysProp != null) {
            return sysProp;
        }
        return System.getenv("CAFEAI_PROFILE");
    }

    @Override
    public AppConfig config() {
        return key -> config.get(key.name()).asString().asOptional();
    }
}
