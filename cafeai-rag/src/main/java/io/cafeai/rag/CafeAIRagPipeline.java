package io.cafeai.rag;

import io.cafeai.core.spi.RagPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ServiceLoader registration for the RAG pipeline in {@code cafeai-rag}.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.RagPipeline}
 *
 * <p>Receives {@code Object} parameters from {@code cafeai-core} and casts them
 * to the concrete {@code cafeai-rag} types it owns, avoiding the circular
 * compile-time dependency.
 */
public final class CafeAIRagPipeline implements RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(CafeAIRagPipeline.class);

    @Override
    public void ingest(Object sourceObj, Object vectorStoreObj, Object embeddingModelObj) {
        Source        source        = requireType(sourceObj,        Source.class,        "Source");
        VectorStore   vectorStore   = requireType(vectorStoreObj,   VectorStore.class,   "VectorStore");
        EmbeddingModel embeddingModel = requireType(embeddingModelObj, EmbeddingModel.class, "EmbeddingModel");

        log.info("Ingesting source: {}", source.sourceId());

        // Delete existing chunks for idempotent re-ingestion
        vectorStore.deleteBySource(source.sourceId());

        // Load → chunk → embed → upsert
        List<Source.RawDocument> docs = source.load();
        Chunker chunker = new Chunker();
        int chunkCount = 0;

        for (Source.RawDocument doc : docs) {
            for (Chunker.Chunk chunk : chunker.chunk(doc.content(), doc.sourceId())) {
                float[] embedding = embeddingModel.embed(chunk.content());
                vectorStore.upsert(chunk.id(), chunk.content(), embedding,
                                   chunk.sourceId(), chunk.index());
                chunkCount++;
            }
        }

        log.info("Ingested {} chunks from: {}", chunkCount, source.sourceId());
    }

    @Override
    public List<Object> retrieve(String query, Object retrieverObj,
                                 Object vectorStoreObj, Object embeddingModelObj) {
        Retriever      retriever      = requireType(retrieverObj,      Retriever.class,      "Retriever");
        VectorStore    vectorStore    = requireType(vectorStoreObj,    VectorStore.class,    "VectorStore");
        EmbeddingModel embeddingModel = requireType(embeddingModelObj, EmbeddingModel.class, "EmbeddingModel");

        List<RagDocument> docs = retriever.retrieve(query, embeddingModel, vectorStore);
        // Cast each RagDocument to Object for the core-level return type
        return docs.stream().map(d -> (Object) d).toList();
    }

    private static <T> T requireType(Object obj, Class<T> type, String name) {
        if (!type.isInstance(obj)) {
            throw new IllegalArgumentException(
                "Expected a " + name + " instance but got: " +
                (obj == null ? "null" : obj.getClass().getName()) +
                ". Ensure you are using the types from the cafeai-rag module.");
        }
        return type.cast(obj);
    }
}
