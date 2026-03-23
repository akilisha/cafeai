package io.cafeai.core.internal;

import io.cafeai.core.spi.CafeAIRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Concrete implementation of {@link CafeAIRegistry}.
 * Holds capability factories registered by {@link io.cafeai.core.spi.CafeAIModule} instances.
 * Package-private — only accessible internally.
 */
final class CafeAIRegistryImpl implements CafeAIRegistry {

    private static final Logger log = LoggerFactory.getLogger(CafeAIRegistryImpl.class);

    private final Map<String, Supplier<?>> memoryStrategies  = new ConcurrentHashMap<>();
    private final Map<String, Supplier<?>> embeddingModels   = new ConcurrentHashMap<>();
    private final Map<String, Supplier<?>> vectorStores      = new ConcurrentHashMap<>();
    private final Map<String, Supplier<?>> guardRails        = new ConcurrentHashMap<>();
    private final Map<String, Supplier<?>> middlewares       = new ConcurrentHashMap<>();

    @Override
    public void registerMemoryStrategy(String name, Supplier<?> factory) {
        register("MemoryStrategy", name, memoryStrategies, factory);
    }

    @Override
    public void registerEmbeddingModel(String name, Supplier<?> factory) {
        register("EmbeddingModel", name, embeddingModels, factory);
    }

    @Override
    public void registerVectorStore(String name, Supplier<?> factory) {
        register("VectorStore", name, vectorStores, factory);
    }

    @Override
    public void registerGuardRail(String name, Supplier<?> factory) {
        register("GuardRail", name, guardRails, factory);
    }

    @Override
    public void registerMiddleware(String name, Supplier<?> factory) {
        register("Middleware", name, middlewares, factory);
    }

    private void register(String type, String name,
                          Map<String, Supplier<?>> registry, Supplier<?> factory) {
        if (registry.containsKey(name)) {
            log.warn("CafeAI registry: duplicate {} registration '{}' — last registration wins",
                type, name);
        }
        registry.put(name, factory);
        log.debug("CafeAI registry: {} '{}' registered", type, name);
    }

    // Accessors for use within the framework
    Supplier<?> memoryStrategy(String name)  { return memoryStrategies.get(name); }
    Supplier<?> embeddingModel(String name)  { return embeddingModels.get(name); }
    Supplier<?> vectorStore(String name)     { return vectorStores.get(name); }
    Supplier<?> guardRail(String name)       { return guardRails.get(name); }
    Supplier<?> middleware(String name)      { return middlewares.get(name); }
}
