package io.cafeai.flight;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-flight} at startup.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when the {@code cafeai-flight}
 * JAR is on the classpath. Unlike most other CafeAI modules, there is no other SPI
 * this module registers with -- {@link FlightBridge} is used directly
 * ({@code FlightBridge.builder().build().start()}), not reached through a
 * {@code cafeai-core} factory method.
 */
public final class CafeAIFlightModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-flight"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
