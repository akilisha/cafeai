package io.cafeai.core.spi;

/**
 * SPI allowing {@code cafeai-rag} to implement RAG ingestion without
 * creating a circular compile-time dependency.
 *
 * <p>{@code cafeai-core} calls this via {@link java.util.ServiceLoader};
 * {@code cafeai-rag} provides the implementation. All parameters are typed
 * as {@code Object} here; the implementing class casts them to the concrete
 * {@code cafeai-rag} types it owns.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.RagPipeline}
 */
public interface RagPipeline {

    /**
     * Ingests a knowledge source into the vector store.
     *
     * @param source         a {@code io.cafeai.rag.Source} instance
     * @param vectorStore    a {@code io.cafeai.rag.VectorStore} instance
     * @param embeddingModel a {@code io.cafeai.rag.EmbeddingModel} instance
     */
    void ingest(Object source, Object vectorStore, Object embeddingModel);

    /**
     * Retrieves relevant documents for a query.
     *
     * @param query          the user's question
     * @param retriever      a {@code io.cafeai.rag.Retriever} instance
     * @param vectorStore    a {@code io.cafeai.rag.VectorStore} instance
     * @param embeddingModel a {@code io.cafeai.rag.EmbeddingModel} instance
     * @return list of {@code io.cafeai.rag.RagDocument} instances, as {@code Object}
     */
    java.util.List<Object> retrieve(String query, Object retriever,
                                    Object vectorStore, Object embeddingModel);
}
