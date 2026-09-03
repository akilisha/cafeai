package io.cafeai.rag;

/**
 * Factory for PostgreSQL/pgvector-backed {@link VectorStore} instances.
 *
 * <p>A production vector store on infrastructure most teams already run:
 * ACID transactions, SQL-queryable, backed up with the rest of the database,
 * no new service to operate. The {@code pgvector} extension must be installed
 * (the {@code pgvector/pgvector} Docker images have it; on a managed Postgres
 * run {@code CREATE EXTENSION IF NOT EXISTS vector}).
 *
 * <pre>{@code
 *   app.vectordb(VectorStore.pgVector(
 *       PgVectorConfig.builder()
 *           .host("localhost").database("cafeai")
 *           .user("cafeai").password(System.getenv("PGPASSWORD"))
 *           .dimension(384)
 *           .build()));
 * }</pre>
 *
 * <p>On first connection the adapter creates the chunk table and an
 * {@code ivfflat} cosine index if they do not exist. Chunks survive restarts.
 */
public final class PgVector {

    private PgVector() {}

    /**
     * Connects to pgvector using the given configuration.
     *
     * @param config connection + schema settings; {@code dimension} must match
     *               the registered {@code EmbeddingModel}
     */
    public static VectorStore connect(PgVectorConfig config) {
        return new PgVectorStoreAdapter(config);
    }
}
