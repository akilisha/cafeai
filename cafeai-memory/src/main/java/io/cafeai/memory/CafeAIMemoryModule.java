package io.cafeai.memory;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-memory} at startup.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when the
 * {@code cafeai-memory} JAR is on the classpath. The mapped, Redis and hybrid
 * memory strategies are not registered here; they are reached through
 * {@code MemoryStrategyProvider}, and still need explicit configuration —
 * {@code app.memory(MemoryStrategy.redis(...))} or
 * {@code app.connect(Redis.at(...))} — to activate.
 */
public final class CafeAIMemoryModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-memory"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
