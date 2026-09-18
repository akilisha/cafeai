package io.cafeai.config;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-config} at startup.
 *
 * <p>Signals that file-based configuration is active. Nothing is wired here —
 * {@link io.cafeai.core.config.AppConfig#load()}
 * discovers {@link PropertiesConfigProvider} directly via
 * {@link java.util.ServiceLoader}.
 */
public final class CafeAIConfigModule implements CafeAIModule {

    @Override
    public String name() { return "cafeai-config"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
