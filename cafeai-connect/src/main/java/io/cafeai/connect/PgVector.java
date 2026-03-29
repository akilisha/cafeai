package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;

/**
 * Out-of-process pgvector (PostgreSQL + vector extension) connection.
 *
 * <p>Probes via a JDBC connection attempt, then registers a pgvector-backed
 * {@code VectorStore} with the application.
 *
 * <p>Requires {@code cafeai-rag} on the classpath for the actual
 * {@code PgVectorStore} implementation.
 *
 * <pre>{@code
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai"));
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai")
 *       .credentials("cafeai", "secret"));
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai")
 *       .onUnavailable(Fallback.use(VectorStore.inMemory())));
 * }</pre>
 */
public final class PgVector implements Connection {

    private static final Logger log = LoggerFactory.getLogger(PgVector.class);

    private final String jdbcUrl;
    private String username;
    private String password;

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
        try {
            // PgVectorStore lives in cafeai-rag -- use reflection to avoid circular dep
            Class<?> configClass = Class.forName("io.cafeai.rag.PgVectorConfig");
            Object config = username != null
                ? configClass.getMethod("of", String.class, String.class, String.class)
                    .invoke(null, jdbcUrl, username, password)
                : configClass.getMethod("of", String.class).invoke(null, jdbcUrl);

            Class<?> pgClass = Class.forName("io.cafeai.rag.PgVector");
            Object store = pgClass.getMethod("connect", configClass).invoke(null, config);
            app.vectordb(store);
            log.info("Connected: {} -> registered as vector store", name());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "PgVector connection requires cafeai-rag on the classpath. " +
                "Add: implementation 'io.cafeai:cafeai-rag'", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to register PgVector connection: " + e.getMessage(), e);
        }
    }
}
