package io.cafeai.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.cafeai.core.rag.EmbeddingProvider;

/**
 * OpenAI embedding provider.
 *
 * <p>Requires {@code OPENAI_API_KEY} environment variable.
 *
 * <p>Package-private — obtained via {@link EmbeddingProvider#openAi()} or
 * {@link EmbeddingProvider#openAi(String)}.
 */
final class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private static final String PROBE_TEXT = "cafeai-dimension-probe";

    private final String modelId;
    private final EmbeddingModel delegate;

    // Measured from a real embedding call, not guessed from the model id —
    // OpenAI's embedding lineup changes over time, and a lookup table keyed
    // on id substrings silently returns the wrong dimensionality for any
    // model it doesn't recognise. Racy but benign: every thread that loses
    // the race computes and stores the same correct value.
    private volatile int dimensions = -1;

    OpenAiEmbeddingProvider(String modelId) {
        this.modelId = modelId;

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Missing API key for OpenAI embedding model. " +
                "Set the OPENAI_API_KEY environment variable, or use:\n" +
                "  app.embed(EmbeddingProvider.local())  // no key required");
        }

        this.delegate = OpenAiEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(modelId)
            .build();
    }

    @Override
    public float[] embed(String text) {
        float[] vector = delegate.embed(text).content().vector();
        if (dimensions < 0) {
            dimensions = vector.length;
        }
        return vector;
    }

    @Override
    public synchronized int dimensions() {
        if (dimensions < 0) {
            dimensions = delegate.embed(PROBE_TEXT).content().vector().length;
        }
        return dimensions;
    }

    @Override
    public String modelId() { return modelId; }
}
