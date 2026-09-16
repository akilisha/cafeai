package io.cafeai.rag;

/**
 * Provider-agnostic embedding provider for the RAG pipeline.
 *
 * <p>Register once at startup via {@code app.embed(provider)}. Used automatically
 * during {@code app.ingest()} (to embed document chunks) and at query time
 * (to embed the user's question for retrieval).
 *
 * <pre>{@code
 *   // Local ONNX model — no API key, no latency, no cost
 *   app.embed(EmbeddingProvider.local());
 *
 *   // OpenAI embeddings — higher quality, requires API key and a model id
 *   app.embed(EmbeddingProvider.openAi("text-embedding-3-large"));
 * }</pre>
 */
public interface EmbeddingProvider {

    /**
     * Embeds a single text string and returns the embedding vector.
     *
     * <p>Implementations must be thread-safe — this method will be called
     * from concurrent virtual threads during bulk ingestion.
     *
     * @param text the text to embed
     * @return embedding vector (dimensionality depends on the model)
     */
    float[] embed(String text);

    /**
     * Returns the dimensionality of vectors produced by this model.
     * Used by vector stores to configure their index.
     */
    int dimensions();

    /**
     * The model's identifier string — used in observability traces.
     */
    String modelId();

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Local ONNX embedding model via Langchain4j.
     *
     * <p>No API key required. Runs entirely on the JVM using the
     * {@code all-MiniLM-L6-v2} model (384 dimensions). Appropriate for
     * production single-node deployments where data sovereignty matters.
     *
     * <p>Requires {@code com.akilisha.oss:cafeai-rag} on the classpath.
     */
    static EmbeddingProvider local() {
        return new LocalEmbeddingProvider();
    }

    /**
     * OpenAI embedding provider by model id. There is no default — OpenAI's
     * embedding lineup changes over time ({@code text-embedding-ada-002} is
     * already retired in favour of the {@code text-embedding-3} family), and
     * a framework that bakes in a specific id ships a landmine the day the
     * provider retires it. Pass the id explicitly, or set
     * {@code CAFEAI_EMBEDDING_MODEL} and call {@link #openAi()} instead.
     *
     * <p>Requires {@code OPENAI_API_KEY} environment variable. Dimensionality
     * is measured from the real model on first use, not guessed from the id.
     *
     * @param modelId the OpenAI embedding model id
     */
    static EmbeddingProvider openAi(String modelId) {
        return new OpenAiEmbeddingProvider(modelId);
    }

    /**
     * OpenAI embedding provider using the model id from the
     * {@code CAFEAI_EMBEDDING_MODEL} environment variable — convenient for
     * pinning a model per-environment without touching code.
     *
     * @throws IllegalStateException if {@code CAFEAI_EMBEDDING_MODEL} is not set
     */
    static EmbeddingProvider openAi() {
        String modelId = System.getenv("CAFEAI_EMBEDDING_MODEL");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalStateException(
                "No OpenAI embedding model id given. Either call "
                + "EmbeddingProvider.openAi(\"text-embedding-3-small\") explicitly, "
                + "or set the CAFEAI_EMBEDDING_MODEL environment variable.");
        }
        return openAi(modelId);
    }
}
