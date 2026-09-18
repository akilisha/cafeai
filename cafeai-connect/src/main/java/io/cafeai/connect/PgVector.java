package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.connect.Connection;
import io.cafeai.core.connect.HealthStatus;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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

    @Override public String name()      { return "PgVector(" + Urls.redact(jdbcUrl) + ")"; }
    @Override public ServiceType type() { return ServiceType.VECTOR_DB; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try {
            var props = new Properties();
            if (username != null) props.setProperty("user", username);
            if (password != null) props.setProperty("password", password);
            props.setProperty("connectTimeout", "3");     // seconds; a probe must not hang on a dead host
            try (var conn = DriverManager.getConnection(jdbcUrl, props)) {
                return HealthStatus.reachable(name(), System.currentTimeMillis() - start);
            }
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

    /**
     * Parses {@code jdbc:postgresql://host:port/database} into a {@link PgVectorConfig}. Credentials
     * come from {@link #credentials}, else from {@code user=} / {@code password=} in the URL's query,
     * which is where {@link #probe()} (through the JDBC driver) already reads them.
     */
    PgVectorConfig toConfig() {
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String database = uri.getPath() != null && uri.getPath().length() > 1
            ? uri.getPath().substring(1) : null;
        Map<String, String> query = queryParams(uri.getRawQuery());
        String user = username != null ? username : query.get("user");
        String pass = password != null ? password : query.get("password");
        var builder = PgVectorConfig.builder()
            .host(uri.getHost())
            .database(database)
            .dimension(dimension);
        if (uri.getPort() > 0) builder.port(uri.getPort());
        if (user != null) builder.user(user);
        if (pass != null) builder.password(pass);
        return builder.build();
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                           URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
