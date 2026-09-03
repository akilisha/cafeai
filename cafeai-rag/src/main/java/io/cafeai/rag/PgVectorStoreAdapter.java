package io.cafeai.rag;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Adapts LangChain4j's {@link PgVectorEmbeddingStore} to CafeAI's
 * {@link VectorStore}, over a HikariCP pool.
 *
 * <p>Package-private — obtained via {@link PgVector#connect(PgVectorConfig)}.
 * {@code createTable(true)} + {@code useIndex(true)} give DDL auto-migration
 * (the chunk table and an {@code ivfflat} cosine index) on first connection.
 * {@code upsert} maps CafeAI's stable chunk id to a deterministic UUID primary
 * key, so re-ingesting a source overwrites rather than duplicates.
 */
final class PgVectorStoreAdapter implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreAdapter.class);

    private final HikariDataSource dataSource;
    private final PgVectorEmbeddingStore store;
    private final String table;

    PgVectorStoreAdapter(PgVectorConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.maxPoolSize());
        hc.setPoolName("cafeai-pgvector");
        this.dataSource = new HikariDataSource(hc);
        this.table = config.table();

        this.store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(config.table())
                .dimension(config.dimension())
                .useIndex(config.useIndex())
                .indexListSize(config.indexListSize())
                .createTable(true)
                .build();

        log.info("PgVectorStoreAdapter: connected to {} table='{}' dim={}",
                config.jdbcUrl(), config.table(), config.dimension());
    }

    /** CafeAI's chunk id (arbitrary string) → a stable UUID for the primary key. */
    private static String rowId(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public void upsert(String id, String content, float[] embedding,
                       String sourceId, int chunkIndex) {
        Metadata metadata = Metadata.from(Map.of(
                "cafeaiId",   id,
                "sourceId",   sourceId,
                "chunkIndex", String.valueOf(chunkIndex)));
        store.addAll(
                List.of(rowId(id)),
                List.of(Embedding.from(embedding)),
                List.of(TextSegment.from(content, metadata)));
    }

    @Override
    public List<RagDocument> search(float[] queryEmbedding, int topK) {
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryEmbedding))
                .maxResults(topK)
                .minScore(0.0)
                .build();

        return store.search(request).matches().stream()
                .map(match -> {
                    var segment = match.embedded();
                    var meta    = segment != null ? segment.metadata() : null;
                    String sourceId = meta != null ? meta.getString("sourceId")   : "";
                    String chunkStr = meta != null ? meta.getString("chunkIndex") : "-1";
                    String content  = segment != null ? segment.text() : "";
                    return new RagDocument(content, sourceId, match.score(), parseInt(chunkStr));
                })
                .toList();
    }

    @Override
    public boolean exists(String id) {
        String sql = "SELECT 1 FROM " + table + " WHERE embedding_id = ?::uuid";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, rowId(id));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.debug("PgVectorStoreAdapter.exists({}) failed: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public void deleteBySource(String sourceId) {
        store.removeAll(metadataKey("sourceId").isEqualTo(sourceId));
    }

    @Override
    public long count() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.debug("PgVectorStoreAdapter.count() failed: {}", e.getMessage());
            return -1L;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static int parseInt(String value) {
        if (value == null) return -1;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return -1; }
    }
}
