package io.cafeai.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.session.Session;
import io.cafeai.core.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite-backed {@link SessionStore}. Real WAL, real concurrent connections,
 * real file durability -- and explicitly single-instance only. For
 * multi-instance/multi-pod deployments, supply your own {@code SessionStore}
 * (e.g. Redis) -- see {@code RedisSessionExample} in {@code cafeai-examples}.
 *
 * <p>Reached via {@link SessionStore#sqlite()} / {@link SessionStore#sqlite(Path)}
 * once {@code cafeai-session} is on the classpath.
 */
public final class SqliteSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** File path of the SQLite database backing {@link SessionStore#sqlite()}. */
    public static final ConfigKey<String> DB_PATH = ConfigKey.of(
        "cafeai.session.sqlite.path", String.class,
        Path.of(System.getProperty("java.io.tmpdir"), "cafeai", "sessions.db").toString(),
        "File path of the SQLite database backing SessionStore.sqlite().");

    /** Pooled JDBC connections kept open (WAL allows concurrent readers plus one writer). */
    public static final ConfigKey<Integer> POOL_SIZE = ConfigKey.of(
        "cafeai.session.sqlite.pool.size", Integer.class, 4,
        "Pooled JDBC connections kept open (WAL allows concurrent readers plus one writer).");

    private final HikariDataSource dataSource;

    /** Opens (or creates) the database at the configured default path. */
    public SqliteSessionStore() {
        this(Path.of(AppConfig.load().get(DB_PATH)));
    }

    /** Opens (or creates) the database at {@code dbFile}. */
    public SqliteSessionStore(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SessionInitException("Cannot create directory for " + dbFile, e);
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        hc.setMaximumPoolSize(AppConfig.load().get(POOL_SIZE));
        hc.setPoolName("cafeai-session-sqlite");
        hc.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;");
        this.dataSource = new HikariDataSource(hc);

        createSchema();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        log.info("SqliteSessionStore: opened {}", dbFile.toAbsolutePath());
    }

    private void createSchema() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cafeai_sessions (
                id TEXT PRIMARY KEY,
                attributes TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_accessed_at INTEGER NOT NULL
            )""";
        try (Connection c = dataSource.getConnection();
             var stmt = c.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            throw new SessionInitException("Cannot create cafeai_sessions table", e);
        }
    }

    @Override
    public Session create() {
        return new Session(UUID.randomUUID().toString());
    }

    @Override
    public Session load(String sessionId) {
        String sql = "SELECT attributes, created_at, last_accessed_at FROM cafeai_sessions WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> attributes = MAPPER.readValue(rs.getString("attributes"), MAP_TYPE);
                return new Session(sessionId, attributes,
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("last_accessed_at")));
            }
        } catch (Exception e) {
            log.warn("SqliteSessionStore: load failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void save(Session session) {
        String sql = """
            INSERT INTO cafeai_sessions (id, attributes, created_at, last_accessed_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET attributes = excluded.attributes,
                last_accessed_at = excluded.last_accessed_at""";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, session.id());
            ps.setString(2, MAPPER.writeValueAsString(session.attributes()));
            ps.setLong(3, session.createdAt().toEpochMilli());
            ps.setLong(4, session.lastAccessedAt().toEpochMilli());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SessionStoreException("Cannot save session: " + session.id(), e);
        }
    }

    @Override
    public void destroy(String sessionId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM cafeai_sessions WHERE id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionStoreException("Cannot destroy session: " + sessionId, e);
        }
    }

    @Override
    public boolean exists(String sessionId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM cafeai_sessions WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new SessionStoreException("Cannot check session existence: " + sessionId, e);
        }
    }

    /** Closes the connection pool. Also registered as a JVM shutdown hook. */
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            log.debug("SqliteSessionStore: connection pool closed");
        }
    }

    public static final class SessionInitException extends RuntimeException {
        public SessionInitException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static final class SessionStoreException extends RuntimeException {
        public SessionStoreException(String msg, Throwable cause) { super(msg, cause); }
    }
}
