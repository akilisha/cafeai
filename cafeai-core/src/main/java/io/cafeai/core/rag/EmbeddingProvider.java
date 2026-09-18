package io.cafeai.core.rag;

import io.cafeai.core.spi.RagProvider;

import java.util.ServiceLoader;

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
 *
 * <p>Both factories require {@code com.akilisha.oss:cafeai-rag} on the
 * classpath — {@code cafeai-core} has no built-in embedding implementation,
 * since even the "local" option needs a real ONNX model bundle.
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
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static EmbeddingProvider local() {
        return loadProvider().localEmbedding();
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
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static EmbeddingProvider openAi(String modelId) {
        return loadProvider().openAiEmbedding(modelId);
    }

    /**
     * OpenAI embedding provider using the model id from the
     * {@code CAFEAI_EMBEDDING_MODEL} environment variable — convenient for
     * pinning a model per-environment without touching code.
     *
     * @throws IllegalStateException if {@code CAFEAI_EMBEDDING_MODEL} is not set
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
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

    /**
     * Any LangChain4j {@code EmbeddingModel} as a CafeAI embedding provider — Ollama, Bedrock,
     * Vertex, an in-process ONNX model, or anything else in LangChain4j's catalogue — with no
     * adapter for you to write. CafeAI takes LangChain4j's own type rather than wrapping its surface.
     *
     * <pre>{@code
     *   app.embed(EmbeddingProvider.of(OllamaEmbeddingModel.builder()
     *       .baseUrl("http://localhost:11434").modelName("nomic-embed-text").build()));
     * }</pre>
     */
    static EmbeddingProvider of(dev.langchain4j.model.embedding.EmbeddingModel model) {
        return new LangChain4jEmbeddingProvider(model);
    }

    // ── ServiceLoader discovery ──────────────────────────────────────────────

    private static RagProvider loadProvider() {
        return ServiceLoader.load(RagProvider.class)
            .findFirst()
            .orElseThrow(() -> new RagModuleNotFoundException(
                "Embedding providers require the cafeai-rag module. " +
                "Add the following dependency:\n\n" +
                "  Gradle: implementation 'com.akilisha.oss:cafeai-rag'\n" +
                "  Maven:  <artifactId>cafeai-rag</artifactId>"));
    }
}
