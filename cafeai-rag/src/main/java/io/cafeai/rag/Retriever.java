package io.cafeai.rag;

import io.cafeai.core.Attributes;

import java.util.List;

/**
 * Retrieval strategy for the RAG pipeline.
 *
 * <p>A {@code Retriever} is given the user's query, embeds it using the
 * registered {@link EmbeddingModel}, searches the {@link VectorStore}, and
 * returns the top-K most relevant chunks.
 *
 * <p>Register via {@code app.rag(retriever)}:
 *
 * <pre>{@code
 *   // Dense semantic retrieval — most common
 *   app.rag(Retriever.semantic(5));
 *
 *   // Hybrid dense + sparse (BM25) — better for keyword-heavy queries
 *   app.rag(Retriever.hybrid(5));
 * }</pre>
 *
 * <p>Retrieved documents are automatically injected into every
 * {@code app.prompt().call()} as context before the user's message.
 * They are also stored in {@code req.attribute(Attributes.RAG_DOCUMENTS)}
 * for access in route handlers.
 *
 * @see Attributes#RAG_DOCUMENTS
 */
public interface Retriever {

    /**
     * Retrieves the most relevant document chunks for the given query.
     *
     * @param query          the user's natural language question
     * @param embeddingModel the model to use for embedding the query
     * @param vectorStore    the store to search
     * @return ordered list of relevant documents, most relevant first
     */
    List<RagDocument> retrieve(String query, EmbeddingModel embeddingModel,
                               VectorStore vectorStore);

    /**
     * Returns the max number of documents this retriever will return.
     */
    int topK();

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Dense semantic retrieval using cosine similarity.
     *
     * <p>Embeds the query and finds the most similar chunks. Excellent for
     * semantic/conceptual questions. Less effective for exact keyword matches.
     *
     * @param topK number of chunks to retrieve
     */
    static Retriever semantic(int topK) {
        return new SemanticRetriever(topK);
    }

    /**
     * Hybrid retrieval: dense semantic similarity fused with sparse (BM25-style
     * term-frequency) keyword scoring.
     *
     * <p>Better on keyword-heavy queries — product codes, policy numbers, exact
     * identifiers — than dense-only, while keeping semantic recall. The sparse
     * score is computed by re-ranking the top {@code 4 × topK} dense candidates,
     * so it works with every {@link VectorStore} without a separate keyword index.
     *
     * <pre>{@code
     *   app.rag(Retriever.hybrid(5)
     *       .denseWeight(0.6)
     *       .sparseWeight(0.4));
     * }</pre>
     *
     * @param topK number of chunks to retrieve
     */
    static HybridRetriever hybrid(int topK) {
        return new HybridRetriever(topK);
    }

    // ── Implementations ───────────────────────────────────────────────────────

    record SemanticRetriever(int topK) implements Retriever {
        @Override
        public List<RagDocument> retrieve(String query, EmbeddingModel embeddingModel,
                                          VectorStore vectorStore) {
            float[] queryEmbedding = embeddingModel.embed(query);
            return vectorStore.search(queryEmbedding, topK);
        }
    }

    /**
     * Dense similarity fused with a BM25-style term-frequency keyword score.
     *
     * <p>Both score sets are min-max normalised to {@code [0, 1]} over the
     * candidate pool, then combined as
     * {@code denseWeight·dense + sparseWeight·sparse}. The weights need not sum
     * to 1 — they are relative.
     */
    final class HybridRetriever implements Retriever {

        private static final int    CANDIDATE_FACTOR = 4;
        private static final double K1 = 1.5, B = 0.75;

        private final int topK;
        private double denseWeight  = 0.7;
        private double sparseWeight = 0.3;

        HybridRetriever(int topK) {
            if (topK <= 0) throw new IllegalArgumentException("topK must be > 0");
            this.topK = topK;
        }

        /** Weight of the dense (semantic) score. Default 0.7. */
        public HybridRetriever denseWeight(double w) {
            this.denseWeight = requireNonNegative(w, "denseWeight");
            return this;
        }

        /** Weight of the sparse (keyword) score. Default 0.3. */
        public HybridRetriever sparseWeight(double w) {
            this.sparseWeight = requireNonNegative(w, "sparseWeight");
            return this;
        }

        @Override public int topK() { return topK; }

        @Override
        public List<RagDocument> retrieve(String query, EmbeddingModel embeddingModel,
                                          VectorStore vectorStore) {
            float[] queryEmbedding = embeddingModel.embed(query);
            List<RagDocument> candidates =
                vectorStore.search(queryEmbedding, topK * CANDIDATE_FACTOR);
            if (candidates.isEmpty()) return candidates;

            String[] terms = tokenize(query);
            double avgLen = candidates.stream()
                .mapToInt(d -> tokenize(d.content()).length).average().orElse(1);

            double[] dense  = new double[candidates.size()];
            double[] sparse = new double[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                dense[i]  = candidates.get(i).score();
                sparse[i] = bm25(tokenize(candidates.get(i).content()), terms, avgLen);
            }
            normalise(dense);
            normalise(sparse);

            List<RagDocument> fused = new java.util.ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                RagDocument c = candidates.get(i);
                double combined = denseWeight * dense[i] + sparseWeight * sparse[i];
                fused.add(new RagDocument(c.content(), c.sourceId(), combined, c.chunkIndex()));
            }
            fused.sort(java.util.Comparator.comparingDouble(RagDocument::score).reversed());
            return fused.size() > topK ? fused.subList(0, topK) : fused;
        }

        /** BM25 term-frequency component (no IDF — the candidate pool is small and pre-filtered). */
        private static double bm25(String[] docTerms, String[] queryTerms, double avgLen) {
            double dl = docTerms.length;
            double score = 0;
            for (String qt : queryTerms) {
                long tf = 0;
                for (String dt : docTerms) if (dt.equals(qt)) tf++;
                if (tf == 0) continue;
                score += (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgLen));
            }
            return score;
        }

        private static String[] tokenize(String text) {
            return text.toLowerCase().split("[^\\p{Alnum}\\-]+");
        }

        private static void normalise(double[] xs) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double x : xs) { min = Math.min(min, x); max = Math.max(max, x); }
            double range = max - min;
            if (range <= 1e-9) { java.util.Arrays.fill(xs, 0.0); return; }
            for (int i = 0; i < xs.length; i++) xs[i] = (xs[i] - min) / range;
        }

        private static double requireNonNegative(double v, String name) {
            if (v < 0 || Double.isNaN(v)) throw new IllegalArgumentException(name + " must be >= 0");
            return v;
        }
    }
}
