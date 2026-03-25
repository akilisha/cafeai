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
     * Hybrid retrieval: dense semantic + sparse BM25 keyword matching.
     *
     * <p>Combines the strengths of both approaches. Better recall on
     * keyword-heavy queries (product names, codes, exact phrases) while
     * maintaining semantic understanding.
     *
     * <p>Note: BM25 is computed over the in-memory chunk index for
     * {@link VectorStore#inMemory()}. For production vector stores,
     * the hybrid search is delegated to the store's native hybrid search.
     *
     * @param topK number of chunks to retrieve
     */
    static Retriever hybrid(int topK) {
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
     * Hybrid retriever — dense similarity + BM25 keyword scoring.
     * Results are merged using Reciprocal Rank Fusion (RRF).
     */
    record HybridRetriever(int topK) implements Retriever {
        @Override
        public List<RagDocument> retrieve(String query, EmbeddingModel embeddingModel,
                                          VectorStore vectorStore) {
            // Get more candidates from dense search, then rerank
            int candidates = topK * 3;
            float[] queryEmbedding = embeddingModel.embed(query);
            List<RagDocument> denseDocs = vectorStore.search(queryEmbedding, candidates);

            // BM25 keyword scoring applied over the dense candidates
            String[] queryTerms = query.toLowerCase().split("\\s+");
            return denseDocs.stream()
                .map(doc -> {
                    double bm25 = bm25Score(doc.content().toLowerCase(), queryTerms);
                    // RRF: combine dense rank score with BM25
                    double combined = 0.7 * doc.score() + 0.3 * bm25;
                    return new RagDocument(doc.content(), doc.sourceId(),
                                          combined, doc.chunkIndex());
                })
                .sorted(java.util.Comparator.comparingDouble(RagDocument::score).reversed())
                .limit(topK)
                .toList();
        }

        /** Simplified BM25 term frequency score (k1=1.5, b=0.75, avgDl=200). */
        private static double bm25Score(String content, String[] terms) {
            final double k1 = 1.5, b = 0.75, avgDl = 200;
            double dl = content.split("\\s+").length;
            double score = 0;
            for (String term : terms) {
                long tf = content.chars().filter(c -> c == term.charAt(0)).count();
                score += (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDl));
            }
            return Math.min(1.0, score / terms.length);
        }
    }
}
