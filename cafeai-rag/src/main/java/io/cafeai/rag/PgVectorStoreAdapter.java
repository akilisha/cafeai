package io.cafeai.rag;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
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
 * {@code createTable(true)} gives DDL auto-migration of the chunk table on first connection; the
 * optional {@code ivfflat} cosine index is created only when {@code useIndex} is set.
 * {@code upsert} maps CafeAI's stable chunk id to a deterministic UUID primary
 * key, so re-ingesting a source overwrites rather than duplicates.
 */
final class PgVectorStoreAdapter implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreAdapter.class);

    private final HikariDataSource dataSource;
    /** What queries run on: the pool, or the pool with the caller's claims on every connection. */
    private final DataSource queries;
    private final PgVectorEmbeddingStore store;
    private final String table;
    private final boolean rowLevelSecurity;

    PgVectorStoreAdapter(PgVectorConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.maxPoolSize());
        hc.setPoolName("cafeai-pgvector");
        this.dataSource = new HikariDataSource(hc);
        this.table = config.table();
        this.rowLevelSecurity = config.rowLevelSecurity();
        this.queries = rowLevelSecurity ? new CallerClaimsDataSource(dataSource) : dataSource;

        this.store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(queries)
                .table(config.table())
                .dimension(config.dimension())
                .useIndex(config.useIndex())
                .indexListSize(config.indexListSize())
                .createTable(true)
                .build();

        if (rowLevelSecurity) {
            try {
                requireRowLevelSecurity();
            } catch (RuntimeException e) {
                dataSource.close();
                throw e;
            }
        }
        log.info("PgVectorStoreAdapter: connected to {} table='{}' dim={}{}",
                config.jdbcUrl(), config.table(), config.dimension(),
                rowLevelSecurity ? " (row-level security, per caller)" : "");
    }

    /**
     * Fails unless PostgreSQL will really apply the table's row-level security to this role:
     * enabled on the table, and the role neither a superuser, nor {@code BYPASSRLS}, nor the
     * owner of a table that isn't {@code FORCE ROW LEVEL SECURITY}. Declaring per-caller access
     * that the database doesn't enforce would give every caller every row.
     */
    private void requireRowLevelSecurity() {
        String sql = "SELECT c.relrowsecurity, c.relforcerowsecurity, "
                + "pg_get_userbyid(c.relowner) = current_user AS owner, r.rolsuper, r.rolbypassrls "
                + "FROM pg_class c, pg_roles r WHERE c.oid = to_regclass(?) AND r.rolname = current_user";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Row-level security: table '" + table + "' not found");
                }
                boolean enabled = rs.getBoolean(1), forced = rs.getBoolean(2), owner = rs.getBoolean(3);
                boolean superuser = rs.getBoolean(4), bypass = rs.getBoolean(5);
                String why = !enabled ? "row-level security is not enabled on table '" + table + "'"
                        + " (ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY)"
                        : superuser ? "the connecting role is a superuser, which bypasses row-level security"
                        : bypass ? "the connecting role has BYPASSRLS"
                        : owner && !forced ? "the connecting role owns table '" + table + "' and owners bypass "
                                + "row-level security unless it is forced (ALTER TABLE " + table
                                + " FORCE ROW LEVEL SECURITY)"
                        : null;
                if (why != null) {
                    throw new IllegalStateException("rowLevelSecurity(true), but PostgreSQL won't enforce it: "
                            + why + ". Every caller would read every row.");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check row-level security on '" + table + "': "
                    + e.getMessage(), e);
        }
    }

    /** Per caller when PostgreSQL enforces row-level security on the caller's claims. */
    @Override
    public Access access() {
        return rowLevelSecurity ? Access.PER_CALLER : Access.UNENFORCED;
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
        try (Connection c = queries.getConnection();
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
        try (Connection c = queries.getConnection();
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
