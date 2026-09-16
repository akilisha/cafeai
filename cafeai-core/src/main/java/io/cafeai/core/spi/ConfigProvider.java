package io.cafeai.core.spi;

import io.cafeai.core.config.AppConfig;

/**
 * SPI allowing {@code cafeai-config} to supply file-based configuration
 * (application properties plus any active profile overlay) without a
 * compile-time dependency from {@code cafeai-core} on {@code cafeai-config}.
 *
 * <p>{@link AppConfig#load()} checks system properties and environment
 * variables itself before ever consulting this provider — an implementation
 * only needs to handle its own layer (files) and does not need to re-check
 * system properties or environment variables.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConfigProvider}
 */
public interface ConfigProvider {

    /** File-based configuration — application properties plus any active profile. */
    AppConfig config();
}
