package io.cafeai.rag;

/**
 * Provider-agnostic embedding model for the RAG pipeline.
 *
 * <p>Register once at startup via {@code app.embed(model)}. Used automatically
 * during {@code app.ingest()} (to embed document chunks) and at query time
 * (to embed the user's question for retrieval).
 *
 * <pre>{@code
 *   // Local ONNX model — no API key, no latency, no cost
 *   app.embed(EmbeddingModel.local());
 *
 *   // OpenAI embeddings — higher quality, requires API key
 *   app.embed(EmbeddingModel.openAi());
 *   app.embed(EmbeddingModel.openAi("text-embedding-3-large"));
 * }</pre>
 */
public interface EmbeddingModel {

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
     * <p>Requires {@code io.cafeai:cafeai-rag} on the classpath.
     */
    static EmbeddingModel local() {
        return new LocalEmbeddingModel();
    }

    /**
     * OpenAI {@code text-embedding-ada-002} embedding model.
     *
     * <p>Requires {@code OPENAI_API_KEY} environment variable.
     * 1536 dimensions, higher semantic quality than local models.
     */
    static EmbeddingModel openAi() {
        return openAi("text-embedding-ada-002");
    }

    /**
     * OpenAI embedding model by model ID.
     *
     * @param modelId the OpenAI embedding model ID
     */
    static EmbeddingModel openAi(String modelId) {
        return new OpenAiEmbeddingAdapter(modelId);
    }
}
