package io.cafeai.core.spi;

import java.util.function.Supplier;

/**
 * The capability registration surface used by {@link CafeAIModule} implementations.
 *
 * <p>Each {@code cafeai-*} module calls methods on this registry during
 * startup to make its capabilities available to the application.
 *
 * <p>Capabilities registered here become available as factory methods
 * on the corresponding strategy/model/store interfaces — e.g.
 * {@code MemoryStrategy.chronicle()} works only when {@code cafeai-memory}
 * is on the classpath and has registered its Chronicle implementation.
 */
public interface CafeAIRegistry {

    /**
     * Registers a named memory strategy factory.
     * Example: {@code registry.registerMemoryStrategy("chronicle", ChronicleMemoryStrategy::new)}
     */
    void registerMemoryStrategy(String name, Supplier<?> factory);

    /**
     * Registers a named embedding model factory.
     */
    void registerEmbeddingModel(String name, Supplier<?> factory);

    /**
     * Registers a named vector store factory.
     */
    void registerVectorStore(String name, Supplier<?> factory);

    /**
     * Registers a named guardrail factory.
     */
    void registerGuardRail(String name, Supplier<?> factory);

    /**
     * Registers a named middleware factory.
     */
    void registerMiddleware(String name, Supplier<?> factory);
}
