package io.cafeai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the RAG pipeline components — chunking, vector storage, and retrieval.
 *
 * <p>These tests use no external services — the in-memory vector store and a
 * stub embedding model cover the full pipeline behaviour deterministically.
 */
@DisplayName("RAG Pipeline")
class RagPipelineTest {

    // ── Stub embedding model ──────────────────────────────────────────────────

    /**
     * Deterministic embedding model for tests.
     * Produces a vector based on the character sum of the text — not meaningful
     * for semantic search, but consistent for testing store/retrieve round-trips
     * and score ordering.
     */
    static class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            float[] vec = new float[4];
            for (int i = 0; i < text.length(); i++) {
                vec[i % 4] += (float) text.charAt(i);
            }
            // Normalise
            float mag = 0;
            for (float v : vec) mag += v * v;
            mag = (float) Math.sqrt(mag);
            if (mag > 0) for (int i = 0; i < vec.length; i++) vec[i] /= mag;
            return vec;
        }

        @Override public int    dimensions() { return 4; }
        @Override public String modelId()    { return "stub-4d"; }
    }

    // ── Chunker tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chunker splits text into overlapping chunks")
    void chunker_splitsText() {
        Chunker chunker = new Chunker();
        String text = "A".repeat(600); // longer than default chunk size

        List<Chunker.Chunk> chunks = chunker.chunk(text, "test-source");

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Chunker assigns deterministic IDs for same input")
    void chunker_deterministicIds() {
        Chunker chunker = new Chunker();
        String text = "The quick brown fox jumps over the lazy dog.";

        List<Chunker.Chunk> first  = chunker.chunk(text, "doc-1");
        List<Chunker.Chunk> second = chunker.chunk(text, "doc-1");

        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).id()).isEqualTo(second.get(i).id());
        }
    }

    @Test
    @DisplayName("Chunker preserves source ID on each chunk")
    void chunker_preservesSourceId() {
        Chunker chunker = new Chunker();
        List<Chunker.Chunk> chunks = chunker.chunk("Some content here.", "my-source");

        chunks.forEach(c ->
            assertThat(c.sourceId()).isEqualTo("my-source"));
    }

    @Test
    @DisplayName("Short text produces a single chunk")
    void chunker_shortText_singleChunk() {
        Chunker chunker = new Chunker();
        List<Chunker.Chunk> chunks = chunker.chunk("Short.", "doc");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("Short.");
    }

    // ── InMemoryVectorStore tests ─────────────────────────────────────────────

    @Test
    @DisplayName("VectorStore.inMemory() stores and retrieves by cosine similarity")
    void vectorStore_storeAndRetrieve() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();

        store.upsert("chunk-1", "password reset instructions", model.embed("password reset instructions"), "kb/auth", 0);
        store.upsert("chunk-2", "billing and invoices",        model.embed("billing and invoices"),        "kb/billing", 0);
        store.upsert("chunk-3", "API rate limits",              model.embed("API rate limits"),              "kb/api", 0);

        List<RagDocument> results = store.search(model.embed("password reset"), 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("password reset instructions");
    }

    @Test
    @DisplayName("VectorStore returns top-K results ordered by score descending")
    void vectorStore_topK_orderedByScore() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();

        store.upsert("a", "cats and kittens",   model.embed("cats and kittens"),   "doc", 0);
        store.upsert("b", "dogs and puppies",   model.embed("dogs and puppies"),   "doc", 1);
        store.upsert("c", "invoice and payment",model.embed("invoice and payment"),"doc", 2);

        List<RagDocument> results = store.search(model.embed("cats"), 2);

        assertThat(results).hasSize(2);
        // Scores should be descending
        assertThat(results.get(0).score())
            .isGreaterThanOrEqualTo(results.get(1).score());
    }

    @Test
    @DisplayName("VectorStore.upsert() replaces existing chunk with same ID")
    void vectorStore_upsert_replacesExisting() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();

        store.upsert("chunk-1", "original content", model.embed("original content"), "src", 0);
        store.upsert("chunk-1", "updated content",  model.embed("updated content"),  "src", 0);

        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("VectorStore.deleteBySource() removes all chunks for a source")
    void vectorStore_deleteBySource() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();

        store.upsert("a", "doc A chunk 1", model.embed("doc A chunk 1"), "doc-A", 0);
        store.upsert("b", "doc A chunk 2", model.embed("doc A chunk 2"), "doc-A", 1);
        store.upsert("c", "doc B chunk 1", model.embed("doc B chunk 1"), "doc-B", 0);

        store.deleteBySource("doc-A");

        assertThat(store.count()).isEqualTo(1);
        List<RagDocument> results = store.search(model.embed("doc B"), 5);
        assertThat(results).allMatch(d -> "doc-B".equals(d.sourceId()));
    }

    @Test
    @DisplayName("VectorStore returns empty list when empty")
    void vectorStore_empty_returnsEmpty() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();

        List<RagDocument> results = store.search(model.embed("anything"), 5);

        assertThat(results).isEmpty();
    }

    // ── Retriever tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Retriever.semantic() returns top-K relevant documents")
    void retriever_semantic_topK() {
        VectorStore store = VectorStore.inMemory();
        EmbeddingModel model = new StubEmbeddingModel();
        Retriever retriever = Retriever.semantic(2);

        store.upsert("1", "loan eligibility requirements",  model.embed("loan eligibility requirements"),  "loans", 0);
        store.upsert("2", "mortgage application process",   model.embed("mortgage application process"),   "mortgage", 0);
        store.upsert("3", "car insurance policy details",   model.embed("car insurance policy details"),   "insurance", 0);
        store.upsert("4", "home insurance coverage limits", model.embed("home insurance coverage limits"), "insurance", 1);

        List<RagDocument> results = retriever.retrieve("insurance coverage", model, store);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(d -> d.score() > 0.0);
    }

    @Test
    @DisplayName("RagDocument.toString() returns content for LLM injection")
    void ragDocument_toString_returnsContent() {
        RagDocument doc = new RagDocument("This is the document content.", "source-1", 0.95, 0);

        assertThat(doc.toString()).isEqualTo("This is the document content.");
    }

    // ── Source tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Source.text() loads inline text as a single document")
    void source_text_loadsDocument() {
        Source source = Source.text("CafeAI is a Gen AI framework.", "cafeai-intro");

        List<Source.RawDocument> docs = source.load();

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).content()).isEqualTo("CafeAI is a Gen AI framework.");
        assertThat(docs.get(0).sourceId()).isEqualTo("cafeai-intro");
    }

    @Test
    @DisplayName("Source.text() sourceId matches")
    void source_text_sourceId() {
        Source source = Source.text("content", "my-source-id");

        assertThat(source.sourceId()).isEqualTo("my-source-id");
    }
}
