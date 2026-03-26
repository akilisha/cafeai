package io.cafeai.memory;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-memory}.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when the
 * {@code cafeai-memory} JAR is on the classpath. Registers the
 * module's capabilities — mapped, Redis, and hybrid memory strategies —
 * into the CafeAI registry.
 *
 * <p>Registration means the strategies are available; they still require
 * explicit configuration via {@code app.memory(MemoryStrategy.redis(...))}
 * or {@code app.connect(Redis.at(...))} to activate.
 */
public final class CafeAIMemoryModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-memory"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        registry.registerMemoryStrategy("mapped",  MappedMemoryStrategy::new);
        registry.registerMemoryStrategy("redis",   () -> null); // requires config — registered on connect
        registry.registerMemoryStrategy("hybrid",  () -> null); // requires config
    }
}
