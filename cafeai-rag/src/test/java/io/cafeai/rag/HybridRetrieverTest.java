package io.cafeai.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HybridRetriever — dense + keyword fusion")
class HybridRetrieverTest {

    /**
     * Deterministic embedding: the vector is fixed per document by a marker in
     * the text, so "semantic" closeness is fully controlled by the test.
     * The query embeds nearest to the POLICY doc; the PROCEDURE doc only wins
     * once keyword weight is high enough.
     */
    static final class RiggedEmbedding implements EmbeddingModel {
        @Override public float[] embed(String text) {
            String t = text.toLowerCase();
            if (t.contains("query"))      return new float[] {0.95f, 0.30f, 0f, 0f};
            if (t.contains("[policy]"))   return new float[] {1.00f, 0.00f, 0f, 0f};
            if (t.contains("[procedure]"))return new float[] {0.00f, 1.00f, 0f, 0f};
            return new float[] {0f, 0f, 1f, 0f};
        }
        @Override public int dimensions() { return 4; }
        @Override public String modelId() { return "rigged-4d"; }
    }

    private VectorStore store;
    private final EmbeddingModel embed = new RiggedEmbedding();

    @BeforeEach
    void seed() {
        store = VectorStore.inMemory();
        // POLICY doc: no keyword, but semantically closest to the query.
        store.upsert("p-0",
            "[POLICY] Coverage overview and general terms for auto policies.",
            embed.embed("[policy]"), "policy-doc", 0);
        // PROCEDURE doc: contains the exact identifier the user asked about.
        store.upsert("q-0",
            "[PROCEDURE] To reopen claim PO106068 file form 27B and notify the adjuster.",
            embed.embed("[procedure]"), "procedure-doc", 0);
    }

    @Test
    @DisplayName("semantic-only ranks the semantically-closest doc first")
    void semanticOnly() {
        var docs = Retriever.semantic(2).retrieve(
            "query about reopening PO106068", embed, store);
        assertThat(docs.get(0).sourceId()).isEqualTo("policy-doc");
    }

    @Test
    @DisplayName("hybrid surfaces the exact-identifier doc when keyword weight is high")
    void hybridPrefersKeywordMatch() {
        var docs = Retriever.hybrid(2)
            .denseWeight(0.2)
            .sparseWeight(0.8)
            .retrieve("query about reopening PO106068", embed, store);
        assertThat(docs.get(0).sourceId()).isEqualTo("procedure-doc");
        assertThat(docs).hasSize(2);
    }

    @Test
    @DisplayName("hybrid with no keyword hits falls back to dense order")
    void hybridFallsBackToDense() {
        var docs = Retriever.hybrid(2)
            .denseWeight(0.5).sparseWeight(0.5)
            .retrieve("query with no matching identifier at all", embed, store);
        assertThat(docs.get(0).sourceId()).isEqualTo("policy-doc");
    }

    @Test
    @DisplayName("negative weights are rejected")
    void rejectsNegativeWeights() {
        assertThatThrownBy(() -> Retriever.hybrid(3).sparseWeight(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
