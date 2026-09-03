package io.cafeai.rag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full RAG round-trip against a real pgvector database in a container:
 * ingest → search (cosine order) → delete by source → re-ingest (idempotent).
 *
 * <p>Skipped automatically when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PgVector VectorStore — integration")
class PgVectorStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cafeai")
            .withUsername("cafeai")
            .withPassword("cafeai");

    private PgVectorStoreAdapter store;

    /** 4-dim unit vectors so cosine ordering is exact and predictable. */
    private static float[] v(float a, float b, float c, float d) {
        return new float[] { a, b, c, d };
    }

    @BeforeAll
    void connect() {
        var config = PgVectorConfig.builder()
                .host(POSTGRES.getHost())
                .port(POSTGRES.getFirstMappedPort())
                .database("cafeai")
                .user("cafeai")
                .password("cafeai")
                .dimension(4)
                .build();
        store = (PgVectorStoreAdapter) PgVector.connect(config);
    }

    @AfterAll
    void closePool() {
        store.close();   // Phase 8 criterion: pool closed after the test
    }

    @Test
    @DisplayName("ingest, search by cosine similarity, delete by source, re-ingest")
    void fullPipeline() {
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

        // ── idempotent upsert: same id, no duplication ───────────────────────
        store.upsert("a-0", "alpha content", v(1, 0, 0, 0), "doc-a", 0);
        assertThat(store.count()).isEqualTo(4);
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
}
