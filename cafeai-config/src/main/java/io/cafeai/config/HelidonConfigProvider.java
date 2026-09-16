package io.cafeai.config;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.spi.ConfigProvider;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Full configuration resolution for CafeAI, built entirely on Helidon
 * Config — the same engine {@code cafeai-core} already builds its HTTP
 * layer on (ADR-001). {@code cafeai-core} resolves only a
 * {@link io.cafeai.core.config.ConfigKey}'s coded default; every other
 * source layer is this class's job in full, including system properties
 * and environment variables — {@code AppConfig.load()} does not check
 * anything itself before reaching here.
 *
 * <p>A configuration key is always a single dotted name, Spring/Helidon
 * style — {@code "cafeai.rag.chunk.size"}. There is no second,
 * CafeAI-specific spelling for any layer. Whether, and how, that name maps
 * onto an environment variable is entirely Helidon's own established
 * mapping ({@link ConfigSources#environmentVariables()}), not a convention
 * this module invents.
 *
 * <p>Sources, highest precedence first:
 * <ol>
 *   <li>system properties</li>
 *   <li>environment variables</li>
 *   <li>an external file, if {@code CAFEAI_CONFIG_FILE} (env var) or
 *       {@code cafeai.config.file} (system property, wins if both set)
 *       points to one — for configuration mounted outside the jar, e.g. a
 *       Kubernetes ConfigMap volume</li>
 *   <li>{@code application-{profile}.yaml}/{@code .yml}/{@code .properties}
 *       on the classpath, when a profile is active</li>
 *   <li>{@code application.yaml}/{@code .yml}/{@code .properties} on the
 *       classpath</li>
 * </ol>
 *
 * <p>Profile is read from the {@code CAFEAI_PROFILE} environment variable or
 * the {@code cafeai.profile} system property (the latter wins if both are
 * set). Every file-based source is optional — a missing one is silently
 * skipped, not an error, since an application may configure entirely
 * through system properties and environment variables and ship no file at
 * all.
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
        sources.add(ConfigSources.systemProperties());
        sources.add(ConfigSources.environmentVariables());

        String externalFile = resolve("cafeai.config.file", "CAFEAI_CONFIG_FILE");
        if (externalFile != null && !externalFile.isBlank()) {
            sources.add(ConfigSources.file(Path.of(externalFile.trim())).optional());
        }

        String profile = resolve("cafeai.profile", "CAFEAI_PROFILE");
        if (profile != null && !profile.isBlank()) {
            addClasspathSourcesFor("application-" + profile.trim(), sources);
        }
        addClasspathSourcesFor("application", sources);

        this.config = Config.builder().sources(sources).build();
        log.info("Configuration sources: {}{}{}", sources.size(),
                profile != null && !profile.isBlank() ? ", profile: " + profile : "",
                externalFile != null && !externalFile.isBlank() ? ", external file: " + externalFile : "");
    }

    private static void addClasspathSourcesFor(String baseName, List<Supplier<? extends ConfigSource>> sources) {
        for (String ext : EXTENSIONS) {
            sources.add(ConfigSources.classpath(baseName + "." + ext).optional());
        }
    }

    /** System property first, then environment variable — same precedence as every other key. */
    private static String resolve(String systemProperty, String envVar) {
        String sysProp = System.getProperty(systemProperty);
        if (sysProp != null) {
            return sysProp;
        }
        return System.getenv(envVar);
    }

    @Override
    public AppConfig config() {
        return key -> config.get(key.name()).asString().asOptional();
    }
}
