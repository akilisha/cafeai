package io.cafeai.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

/**
 * Local ONNX embedding model using {@code all-MiniLM-L6-v2-quantized}.
 *
 * <p>384-dimensional embeddings. No API key. No network call. Appropriate for
 * production deployments where data sovereignty or latency matter.
 *
 * <p>The ONNX model is bundled in the Langchain4j dependency — no separate
 * download required.
 *
 * <p>Package-private — obtained via {@link EmbeddingModel#local()}.
 */
final class LocalEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 384;

    // Thread-safe — Langchain4j's ONNX model is safe for concurrent use
    private final AllMiniLmL6V2QuantizedEmbeddingModel delegate;

    LocalEmbeddingModel() {
        this.delegate = new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Override
    public float[] embed(String text) {
        Embedding embedding = delegate.embed(text).content();
        return embedding.vector();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelId() {
        return "all-minilm-l6-v2-quantized";
    }
}
