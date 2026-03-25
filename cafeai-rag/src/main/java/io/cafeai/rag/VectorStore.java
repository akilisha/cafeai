package io.cafeai.rag;

import java.util.List;

/**
 * Provider-agnostic vector store for CafeAI's RAG pipeline.
 *
 * <p>Register once at startup via {@code app.vectordb(store)}. All subsequent
 * {@code app.ingest()} and {@code app.rag()} calls use this store automatically.
 *
 * <pre>{@code
 *   // Zero infrastructure — development and testing
 *   app.vectordb(VectorStore.inMemory());
 *
 *   // PgVector — production single-node
 *   app.vectordb(PgVector.connect(PgVectorConfig.of("jdbc:postgresql://localhost/cafeai")));
 *
 *   // Chroma — local lightweight
 *   app.vectordb(Chroma.local());
 * }</pre>
 */
public interface VectorStore {

    /**
     * Stores a document chunk with its embedding vector.
     *
     * @param id        stable identifier for this chunk — used for idempotent upsert
     * @param content   the text content of the chunk
     * @param embedding the embedding vector produced by the registered {@link EmbeddingModel}
     * @param sourceId  the source document identifier (file path, URL, etc.)
     * @param chunkIndex position of this chunk within the source document
     */
    void upsert(String id, String content, float[] embedding, String sourceId, int chunkIndex);

    /**
     * Searches for the top-K most similar chunks to the query embedding.
     *
     * @param queryEmbedding embedding of the user's query
     * @param topK           number of results to return
     * @return list of retrieved documents ordered by descending similarity score
     */
    List<RagDocument> search(float[] queryEmbedding, int topK);

    /**
     * Returns {@code true} if a chunk with the given ID already exists.
     * Used to implement idempotent ingestion.
     */
    boolean exists(String id);

    /**
     * Deletes all chunks associated with the given source ID.
     * Called before re-ingesting a source to prevent duplication.
     */
    void deleteBySource(String sourceId);

    /**
     * Returns the total number of chunks stored.
     */
    long count();

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * In-memory vector store. Zero dependencies. Zero infrastructure.
     * Uses brute-force cosine similarity search — appropriate up to ~10,000 chunks.
     *
     * <p>Appropriate for: development, testing, small knowledge bases.
     * Does not survive restarts.
     */
    static VectorStore inMemory() {
        return new InMemoryVectorStore();
    }
}
