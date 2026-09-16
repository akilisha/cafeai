package io.cafeai.config;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.spi.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Loads {@code application.properties} from the classpath, overlaid by
 * {@code application-{profile}.properties} when a profile is active.
 *
 * <p>Profile is read from the {@code CAFEAI_PROFILE} environment variable or
 * the {@code cafeai.profile} system property (the latter wins if both are
 * set — same precedence as every other key). Absent either, no profile
 * overlay is applied and {@code application.properties} alone is used.
 *
 * <p>Neither file is required to exist — a missing file is silently skipped,
 * not an error, since an application may configure entirely through
 * environment variables and never ship a properties file at all.
 *
 * <p>This provider does not check environment variables or system properties
 * itself; {@link AppConfig#load()} already does that before ever reaching
 * here.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConfigProvider}
 */
public final class PropertiesConfigProvider implements ConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(PropertiesConfigProvider.class);

    private final Properties merged = new Properties();

    public PropertiesConfigProvider() {
        load("application.properties");
        String profile = resolveProfile();
        if (profile != null && !profile.isBlank()) {
            load("application-" + profile.trim() + ".properties");
        }
    }

    private void load(String resourceName) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in == null) {
                return;
            }
            Properties layer = new Properties();
            layer.load(in);
            merged.putAll(layer);
            log.info("Loaded {} ({} propert{})", resourceName, layer.size(), layer.size() == 1 ? "y" : "ies");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resourceName + " from the classpath", e);
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
        return key -> Optional.ofNullable(merged.getProperty(key.name()));
    }
}
