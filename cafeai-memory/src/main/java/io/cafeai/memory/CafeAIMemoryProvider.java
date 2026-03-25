package io.cafeai.memory;

import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.spi.MemoryStrategyProvider;

import java.nio.file.Path;
import java.time.Duration;

/**
 * ServiceLoader registration for {@code cafeai-memory}.
 *
 * <p>Adding {@code io.cafeai:cafeai-memory} to the classpath activates this
 * provider, enabling {@link MemoryStrategy#mapped()}, {@link MemoryStrategy#redis},
 * and {@link MemoryStrategy#hybrid()} without any code changes.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.MemoryStrategyProvider}
 */
public final class CafeAIMemoryProvider implements MemoryStrategyProvider {

    @Override
    public MemoryStrategy mapped() {
        return new MappedMemoryStrategy();
    }

    @Override
    public MemoryStrategy mapped(Path storageDir) {
        return new MappedMemoryStrategy(storageDir);
    }

    @Override
    public MemoryStrategy redis(RedisConfig config) {
        return new RedisMemoryStrategy(config);
    }

    @Override
    public MemoryStrategy.HybridBuilder hybrid() {
        return new HybridBuilder();
    }

    /** Fluent builder that constructs a {@link HybridMemoryStrategy}. */
    private static final class HybridBuilder implements MemoryStrategy.HybridBuilder {

        private MemoryStrategy warm;
        private MemoryStrategy cold;
        private Duration       demoteAfter;

        @Override
        public MemoryStrategy.HybridBuilder warm(MemoryStrategy strategy) {
            this.warm = strategy;
            return this;
        }

        @Override
        public MemoryStrategy.HybridBuilder cold(MemoryStrategy strategy) {
            this.cold = strategy;
            return this;
        }

        @Override
        public MemoryStrategy.HybridBuilder demoteAfter(Duration duration) {
            this.demoteAfter = duration;
            return this;
        }

        @Override
        public MemoryStrategy build() {
            HybridMemoryStrategy hybrid = new HybridMemoryStrategy();
            if (warm != null)        hybrid.warm(warm);
            if (cold != null)        hybrid.cold(cold);
            if (demoteAfter != null) hybrid.demoteAfter(demoteAfter);
            return hybrid;
        }
    }
}
