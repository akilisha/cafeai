package io.cafeai.rag;

import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

/**
 * Self-registration module for {@code cafeai-rag}.
 *
 * <p>Registers embedding models and vector store implementations.
 * The local ONNX embedding model and in-memory vector store are
 * available immediately; cloud and database-backed variants
 * require connection configuration.
 */
public final class CafeAIRagModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-rag"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public void register(CafeAIRegistry registry) {
        registry.registerEmbeddingModel("local",  () -> EmbeddingModel.local());
        registry.registerEmbeddingModel("openai", () -> EmbeddingModel.openAi());
        registry.registerVectorStore("inmemory",  () -> VectorStore.inMemory());
        registry.registerVectorStore("pgvector",  () -> null); // requires connection config
        registry.registerVectorStore("chroma",    () -> null); // requires connection config
    }
}
