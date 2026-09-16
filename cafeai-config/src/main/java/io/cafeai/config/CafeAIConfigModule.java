package io.cafeai.config;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-config}.
 *
 * <p>Signals that file-based configuration is active. No factory
 * registration is needed — {@link io.cafeai.core.config.AppConfig#load()}
 * discovers {@link PropertiesConfigProvider} directly via
 * {@link java.util.ServiceLoader}.
 */
public final class CafeAIConfigModule implements CafeAIModule {

    @Override
    public String name() { return "cafeai-config"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }

    @Override
    public void register(CafeAIRegistry registry) {
        // Nothing to register — see class Javadoc.
    }
}
