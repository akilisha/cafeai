package io.cafeai.rag;

import dev.langchain4j.data.embedding.Embedding;

/**
 * OpenAI embedding model adapter.
 *
 * <p>Requires {@code OPENAI_API_KEY} environment variable.
 *
 * <p>Package-private — obtained via {@link EmbeddingModel#openAi()} or
 * {@link EmbeddingModel#openAi(String)}.
 */
final class OpenAiEmbeddingAdapter implements EmbeddingModel {

    private static final int ADA_002_DIMENSIONS     = 1536;
    private static final int EMBEDDING_3_DIMENSIONS = 3072;

    private final String modelId;
    private final dev.langchain4j.model.embedding.EmbeddingModel delegate;
    private final int dimensions;

    OpenAiEmbeddingAdapter(String modelId) {
        this.modelId = modelId;
        this.dimensions = modelId.contains("3-large") ? EMBEDDING_3_DIMENSIONS
                        : modelId.contains("3-small") ? 1536
                        : ADA_002_DIMENSIONS;

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Missing API key for OpenAI embedding model. " +
                "Set the OPENAI_API_KEY environment variable, or use:\n" +
                "  app.embed(EmbeddingModel.local())  // no key required");
        }

        // Use the fully-qualified Langchain4j class to avoid name collision
        // with CafeAI's own EmbeddingModel interface in this package
        this.delegate = dev.langchain4j.model.openai.OpenAiEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(modelId)
            .build();
    }

    @Override
    public float[] embed(String text) {
        Embedding embedding = delegate.embed(text).content();
        return embedding.vector();
    }

    @Override
    public int dimensions() { return dimensions; }

    @Override
    public String modelId() { return modelId; }
}
