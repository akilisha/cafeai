package io.cafeai.rag;

import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.Source;
import io.cafeai.core.rag.VectorStore;
import io.cafeai.core.spi.RagProvider;

import java.nio.file.Path;

/**
 * ServiceLoader registration supplying {@code cafeai-rag}'s real
 * implementations of {@code cafeai-core}'s RAG contracts.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.RagProvider}
 */
public final class CafeAIRagProviderImpl implements RagProvider {

    @Override public VectorStore chroma() { return Chroma.local(); }

    @Override public VectorStore chroma(String baseUrl) { return Chroma.connect(baseUrl); }

    @Override public VectorStore chroma(String baseUrl, String collectionName) {
        return Chroma.connect(baseUrl, collectionName);
    }

    @Override public VectorStore pgVector(PgVectorConfig config) { return PgVector.connect(config); }

    @Override public EmbeddingProvider localEmbedding() { return new LocalEmbeddingProvider(); }

    @Override public EmbeddingProvider openAiEmbedding(String modelId) {
        return new OpenAiEmbeddingProvider(modelId);
    }

    @Override public Source pdfSource(String path) { return new FileSource(Path.of(path), "application/pdf"); }

    @Override public Source fileSource(String path) { return new FileSource(Path.of(path), "text/plain"); }

    @Override public Source directorySource(String path) { return new DirectorySource(Path.of(path)); }

    @Override public Source urlSource(String url) { return new UrlSource(url); }
}
