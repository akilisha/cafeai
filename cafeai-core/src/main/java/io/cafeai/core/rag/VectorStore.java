package io.cafeai.core.rag;

import io.cafeai.core.spi.RagProvider;

import java.util.List;
import java.util.ServiceLoader;

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
 *   // Chroma — local, lightweight, restart-durable
 *   app.vectordb(VectorStore.chroma("http://localhost:8000", "acme-claims"));
 *
 *   // PgVector — production, on Postgres you already run
 *   app.vectordb(VectorStore.pgVector(
 *       PgVectorConfig.builder().host("localhost").database("cafeai").dimension(384).build()));
 * }</pre>
 *
 * <p>{@code chroma(...)} and {@code pgVector(...)} require
 * {@code com.akilisha.oss:cafeai-rag} on the classpath — {@code inMemory()}
 * does not.
 */
public interface VectorStore {

    /**
     * Stores a document chunk with its embedding vector.
     *
     * @param id        stable identifier for this chunk — used for idempotent upsert
     * @param content   the text content of the chunk
     * @param embedding the embedding vector produced by the registered {@link EmbeddingProvider}
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

    /**
     * Chroma vector store. Lightweight, local-first, document-persistent.
     *
     * <p>Connects to Chroma running on {@code http://localhost:8000} using
     * the default collection name {@code "cafeai"}. Documents survive
     * application restarts.
     *
     * <p>Requires Chroma 0.5.x running locally:
     * <pre>
     *   docker run -p 8000:8000 chromadb/chroma:0.5.23
     * </pre>
     *
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static VectorStore chroma() {
        return loadProvider().chroma();
    }

    /**
     * Chroma vector store at the given base URL.
     *
     * <pre>{@code
     *   app.vectordb(VectorStore.chroma("http://localhost:8000"));
     * }</pre>
     *
     * @param baseUrl Chroma base URL
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static VectorStore chroma(String baseUrl) {
        return loadProvider().chroma(baseUrl);
    }

    /**
     * Chroma vector store at the given base URL with a specific collection name.
     *
     * <pre>{@code
     *   app.vectordb(VectorStore.chroma("http://localhost:8000", "acme-claims"));
     * }</pre>
     *
     * @param baseUrl        Chroma base URL
     * @param collectionName Chroma collection to use
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static VectorStore chroma(String baseUrl, String collectionName) {
        return loadProvider().chroma(baseUrl, collectionName);
    }

    /**
     * PostgreSQL/pgvector vector store — production single-node, on infrastructure
     * you already run. ACID, SQL-queryable, restart-durable. The chunk table is
     * created on first connection; search is exact, and an approximate {@code ivfflat} index is
     * opt-in for very large corpora ({@link PgVectorConfig}).
     *
     * <pre>{@code
     *   app.vectordb(VectorStore.pgVector(
     *       PgVectorConfig.builder()
     *           .host("localhost").database("cafeai")
     *           .user("cafeai").password(System.getenv("PGPASSWORD"))
     *           .dimension(384)        // match the registered EmbeddingProvider
     *           .build()));
     * }</pre>
     *
     * @param config connection + schema settings
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static VectorStore pgVector(PgVectorConfig config) {
        return loadProvider().pgVector(config);
    }

    // ── ServiceLoader discovery ──────────────────────────────────────────────

    private static RagProvider loadProvider() {
        return ServiceLoader.load(RagProvider.class)
            .findFirst()
            .orElseThrow(() -> new RagModuleNotFoundException(
                "Chroma and PgVector vector stores require the cafeai-rag module. " +
                "Add the following dependency:\n\n" +
                "  Gradle: implementation 'com.akilisha.oss:cafeai-rag'\n" +
                "  Maven:  <artifactId>cafeai-rag</artifactId>\n\n" +
                "For development, use VectorStore.inMemory() (zero dependencies)."));
    }
}
