package io.cafeai.rag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory vector store with brute-force cosine similarity search.
 *
 * <p>Zero dependencies. Zero infrastructure. Appropriate for development,
 * testing, and small knowledge bases (up to ~10,000 chunks before the
 * O(n) scan becomes noticeable).
 *
 * <p>Package-private — obtained via {@link VectorStore#inMemory()}.
 */
final class InMemoryVectorStore implements VectorStore {

    private record Entry(String id, String content, float[] embedding,
                         String sourceId, int chunkIndex) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(String id, String content, float[] embedding,
                       String sourceId, int chunkIndex) {
        store.put(id, new Entry(id, content, embedding, sourceId, chunkIndex));
    }

    @Override
    public List<RagDocument> search(float[] queryEmbedding, int topK) {
        return store.values().stream()
            .map(e -> new RagDocument(
                e.content(),
                e.sourceId(),
                cosineSimilarity(queryEmbedding, e.embedding()),
                e.chunkIndex()))
            .sorted(Comparator.comparingDouble(RagDocument::score).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public boolean exists(String id) {
        return store.containsKey(id);
    }

    @Override
    public void deleteBySource(String sourceId) {
        store.entrySet().removeIf(e -> sourceId.equals(e.getValue().sourceId()));
    }

    @Override
    public long count() {
        return store.size();
    }

    /**
     * Cosine similarity between two vectors.
     * Returns 0.0 if either vector has zero magnitude.
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, magA = 0, magB = 0;
        for (int i = 0; i < a.length; i++) {
            dot  += a[i] * b[i];
            magA += a[i] * a[i];
            magB += b[i] * b[i];
        }
        double denom = Math.sqrt(magA) * Math.sqrt(magB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
