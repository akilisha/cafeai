package io.cafeai.core.rag;

import io.cafeai.core.config.ConfigKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Orchestrates document ingestion: load → chunk → embed → upsert.
 *
 * <p>Lives in {@code io.cafeai.core.rag} rather than {@code CafeAIApp} because
 * it needs package-private {@link Chunker}. Called from {@code CafeAIApp.ingest()}.
 */
public final class RagIngestion {

    /** Characters per chunk when a source is split for embedding. */
    public static final ConfigKey<Integer> CHUNK_SIZE = ConfigKey.of(
        "cafeai.rag.chunk.size", Integer.class, Chunker.DEFAULT_CHUNK_SIZE,
        "Characters per chunk when a source is split for embedding.");

    /** Characters shared by neighbouring chunks, so a sentence on a boundary stays whole in one. */
    public static final ConfigKey<Integer> CHUNK_OVERLAP = ConfigKey.of(
        "cafeai.rag.chunk.overlap", Integer.class, Chunker.DEFAULT_CHUNK_OVERLAP,
        "Characters shared by neighbouring chunks; must be smaller than cafeai.rag.chunk.size.");

    private static final Logger log = LoggerFactory.getLogger(RagIngestion.class);

    private RagIngestion() {}

    public static void ingest(Source source, VectorStore vectorStore, EmbeddingProvider embeddingModel) {
        log.info("Ingesting source: {}", source.sourceId());

        // Delete existing chunks for idempotent re-ingestion
        vectorStore.deleteBySource(source.sourceId());

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
}
