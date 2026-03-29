package io.cafeai.core.spi;

import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;

import java.nio.file.Path;

/**
 * SPI for the {@code cafeai-memory} module to provide real implementations
 * of the higher memory rungs.
 *
 * <p>Mirrors the pattern of {@link ViewEngineProvider} -- adding the
 * {@code cafeai-memory} JAR to the classpath activates all real implementations.
 * No code changes required.
 */
public interface MemoryStrategyProvider {

    MemoryStrategy mapped();

    MemoryStrategy mapped(Path storageDir);

    MemoryStrategy redis(RedisConfig config);

    /** Returns a builder that constructs a hybrid warm+cold strategy. */
    MemoryStrategy.HybridBuilder hybrid();
}
