package io.cafeai.rag;

import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.RagIngestion;
import io.cafeai.core.rag.Source;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full RAG round-trip against a real Chroma in a container — the same steps as
 * {@link PgVectorStoreIntegrationTest}, plus re-ingesting a source through
 * {@link RagIngestion}, which must replace its chunks rather than add copies.
 *
 * <p>Skipped automatically when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Chroma VectorStore — integration")
class ChromaVectorStoreIntegrationTest {

    @Container
    static final GenericContainer<?> CHROMA = new GenericContainer<>("chromadb/chroma:0.5.23")
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/api/v1/heartbeat").forStatusCode(200));

    private String baseUrl;

    /** 4-dim unit vectors so cosine ordering is exact and predictable. */
    private static float[] v(float a, float b, float c, float d) {
        return new float[] { a, b, c, d };
    }

    @BeforeAll
    void connect() {
        baseUrl = "http://" + CHROMA.getHost() + ":" + CHROMA.getMappedPort(8000);
    }

    @Test
    @DisplayName("ingest, search by cosine similarity, delete by source, re-ingest")
    void fullPipeline() {
        var store = (ChromaVectorStoreAdapter) Chroma.connect(baseUrl, "pipeline");

        // ── ingest ────────────────────────────────────────────────────────────
        store.upsert("a-0", "alpha content", v(1, 0, 0, 0), "doc-a", 0);
        store.upsert("a-1", "second alpha",  v(0.94f, 0.34f, 0, 0), "doc-a", 1);
        store.upsert("b-0", "beta content",  v(0, 1, 0, 0), "doc-b", 0);
        store.upsert("c-0", "gamma content", v(0, 0, 1, 0), "doc-c", 0);
        assertThat(store.count()).isEqualTo(4);

        // ── search: a query near [1,0,0,0] returns the doc-a chunks first ─────
        List<RagDocument> hits = store.search(v(0.97f, 0.24f, 0, 0), 3);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).sourceId()).isEqualTo("doc-a");
        assertThat(hits)
                .extracting(RagDocument::score)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(hits.get(0).content()).contains("alpha");

        // ── idempotent upsert: same id, no duplication, new content wins ─────
        store.upsert("a-0", "alpha revised", v(1, 0, 0, 0), "doc-a", 0);
        assertThat(store.count()).isEqualTo(4);
        assertThat(store.search(v(1, 0, 0, 0), 1).get(0).content()).isEqualTo("alpha revised");
        assertThat(store.exists("a-0")).isTrue();
        assertThat(store.exists("nope")).isFalse();

        // ── delete by source ────────────────────────────────────────────────
        store.deleteBySource("doc-a");
        assertThat(store.count()).isEqualTo(2);
        assertThat(store.exists("a-0")).isFalse();
        assertThat(store.search(v(1, 0, 0, 0), 5))
                .extracting(RagDocument::sourceId)
                .doesNotContain("doc-a");

        // ── re-ingest ───────────────────────────────────────────────────────
        store.upsert("a-0", "alpha content", v(1, 0, 0, 0), "doc-a", 0);
        assertThat(store.count()).isEqualTo(3);
        assertThat(store.exists("a-0")).isTrue();
    }

    @Test
    @DisplayName("ingesting the same source twice leaves one copy of each chunk")
    void reIngestDoesNotDuplicate() {
        var store = Chroma.connect(baseUrl, "reingest");
        EmbeddingProvider embeddings = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return v(1, text.length(), 0, 0); }
            @Override public int dimensions()           { return 4; }
            @Override public String modelId()           { return "fixed"; }
        };
        Source source = Source.text("Refunds are issued within 14 days of a return.", "policy");

        RagIngestion.ingest(source, store, embeddings);
        long once = store.count();
        assertThat(once).isPositive();

        RagIngestion.ingest(source, store, embeddings);
        assertThat(store.count()).isEqualTo(once);
    }
}
