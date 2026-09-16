package io.cafeai.core.spi;

import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.Source;
import io.cafeai.core.rag.VectorStore;

/**
 * SPI for the {@code cafeai-rag} module to provide real implementations of
 * the RAG capabilities that need dependencies {@code cafeai-core} must not
 * carry (Apache Tika, the Postgres driver, the Chroma client, an ONNX model
 * bundle).
 *
 * <p>Mirrors the pattern of {@link MemoryStrategyProvider} and
 * {@link ViewEngineProvider} — adding the {@code cafeai-rag} JAR to the
 * classpath activates all real implementations. No code changes required.
 */
public interface RagProvider {

    // ── Vector stores ─────────────────────────────────────────────────────────

    VectorStore chroma();

    VectorStore chroma(String baseUrl);

    VectorStore chroma(String baseUrl, String collectionName);

    VectorStore pgVector(PgVectorConfig config);

    // ── Embedding providers ──────────────────────────────────────────────────

    EmbeddingProvider localEmbedding();

    EmbeddingProvider openAiEmbedding(String modelId);

    // ── Document sources ─────────────────────────────────────────────────────

    Source pdfSource(String path);

    Source fileSource(String path);

    Source directorySource(String path);

    Source urlSource(String url);
}
