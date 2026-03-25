package io.cafeai.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits raw document text into overlapping chunks suitable for embedding.
 *
 * <p>Chunk size and overlap are configurable. The defaults (512 chars,
 * 64 overlap) work well for most prose documents. Code or structured
 * text may benefit from larger chunks.
 *
 * <p>Chunk IDs are deterministic: {@code sourceId + "#chunk" + index},
 * enabling idempotent re-ingestion — re-chunking the same source produces
 * the same IDs, so {@link VectorStore#upsert} overwrites existing chunks
 * rather than duplicating them.
 */
final class Chunker {

    /** Default chunk size in characters. */
    static final int DEFAULT_CHUNK_SIZE    = 512;

    /** Default overlap between adjacent chunks in characters. */
    static final int DEFAULT_CHUNK_OVERLAP = 64;

    private final int chunkSize;
    private final int overlap;

    Chunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    Chunker(int chunkSize, int overlap) {
        if (chunkSize <= 0)     throw new IllegalArgumentException("chunkSize must be > 0");
        if (overlap < 0)        throw new IllegalArgumentException("overlap must be >= 0");
        if (overlap >= chunkSize) throw new IllegalArgumentException("overlap must be < chunkSize");
        this.chunkSize = chunkSize;
        this.overlap   = overlap;
    }

    /**
     * Splits {@code text} into overlapping chunks and assigns deterministic IDs.
     *
     * @param text     the raw document text
     * @param sourceId stable source identifier — used as the chunk ID prefix
     * @return ordered list of chunks with content, ID, and index
     */
    List<Chunk> chunk(String text, String sourceId) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // Prefer sentence boundaries — split on ". " then re-assemble into windows.
        // For simplicity and predictability, we use a sliding character window here.
        // A future phase can add sentence-aware splitting.
        int step = chunkSize - overlap;
        int idx  = 0;

        for (int start = 0; start < text.length(); start += step) {
            int end     = Math.min(start + chunkSize, text.length());
            String content = text.substring(start, end).trim();
            if (!content.isBlank()) {
                String chunkId = sourceId + "#chunk" + idx;
                chunks.add(new Chunk(chunkId, content, sourceId, idx));
                idx++;
            }
            if (end == text.length()) break;
        }
        return chunks;
    }

    /** A single chunk ready for embedding. */
    record Chunk(String id, String content, String sourceId, int index) {}
}
