package io.cafeai.core.spi;

import io.cafeai.core.config.AppConfig;

/**
 * SPI allowing {@code cafeai-config} to supply real configuration resolution
 * without a compile-time dependency from {@code cafeai-core} on
 * {@code cafeai-config}.
 *
 * <p>{@code cafeai-core} resolves only a {@link io.cafeai.core.config.ConfigKey}'s
 * coded default. Everything else — system properties, environment variables,
 * configuration files, whatever layering the implementation composes — is
 * this provider's job in full; {@link AppConfig#load()} does not check
 * anything itself before consulting it.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConfigProvider}
 */
public interface ConfigProvider {

    /** Full configuration resolution — every source layer this provider composes. */
    AppConfig config();
}
