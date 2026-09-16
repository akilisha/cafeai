package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.DriverManager;

/**
 * Out-of-process pgvector (PostgreSQL + vector extension) connection.
 *
 * <p>Probes via a JDBC connection attempt, then registers a pgvector-backed
 * {@link VectorStore} with the application via
 * {@code io.cafeai.core.rag.VectorStore.pgVector(...)} — which itself requires
 * {@code cafeai-rag} on the classpath for the real implementation.
 *
 * <pre>{@code
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai").dimension(384));
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai")
 *       .credentials("cafeai", "secret")
 *       .dimension(1536));
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai").dimension(384)
 *       .onUnavailable(Fallback.use(VectorStore.inMemory())));
 * }</pre>
 */
public final class PgVector implements Connection {

    private static final Logger log = LoggerFactory.getLogger(PgVector.class);

    private final String jdbcUrl;
    private String username;
    private String password;
    private int    dimension;

    private PgVector(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Creates a pgvector connection targeting the given JDBC URL. */
    public static PgVector at(String jdbcUrl) {
        return new PgVector(jdbcUrl);
    }

    public PgVector credentials(String username, String password) {
        this.username = username;
        this.password = password;
        return this;
    }

    /**
     * Vector dimensionality — must match the {@code EmbeddingProvider}
     * registered with {@code app.embed(...)} (384 for
     * {@code EmbeddingProvider.local()}, 1536 for OpenAI's
     * {@code text-embedding-3-small}). Required — there is no default,
     * since the wrong dimension silently corrupts the index rather than
     * failing loudly.
     */
    public PgVector dimension(int dimension) {
        this.dimension = dimension;
        return this;
    }

    @Override public String name()      { return "PgVector(" + jdbcUrl + ")"; }
    @Override public ServiceType type() { return ServiceType.VECTOR_DB; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try {
            var conn = username != null
                ? DriverManager.getConnection(jdbcUrl, username, password)
                : DriverManager.getConnection(jdbcUrl);
            conn.close();
            return HealthStatus.reachable(name(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    @Override
    public void register(CafeAI app) {
        if (dimension <= 0) {
            throw new IllegalStateException(
                "PgVector.dimension(int) must be set to the registered EmbeddingProvider's " +
                "vector size (e.g. 384 for local, 1536 for OpenAI text-embedding-3-small) " +
                "before connecting.");
        }
        app.vectordb(VectorStore.pgVector(toConfig()));
        log.info("Connected: {} -> registered as vector store", name());
    }

    /** Parses {@code jdbc:postgresql://host:port/database} into a {@link PgVectorConfig}. */
    private PgVectorConfig toConfig() {
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String database = uri.getPath() != null && uri.getPath().length() > 1
            ? uri.getPath().substring(1) : null;
        var builder = PgVectorConfig.builder()
            .host(uri.getHost())
            .database(database)
            .dimension(dimension);
        if (uri.getPort() > 0) builder.port(uri.getPort());
        if (username != null)  builder.user(username);
        if (password != null)  builder.password(password);
        return builder.build();
    }
}
