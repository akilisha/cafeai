package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.connect.Connection;
import io.cafeai.core.connect.HealthStatus;
import io.cafeai.core.middleware.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Entry point for environment-driven connection configuration and health checks.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>{@link #fromEnv()} -- reads {@code CAFEAI_*} environment variables and
 *       returns a list of configured {@link Connection}s. Pass each to
 *       {@code app.connect()} or register them all at once.</li>
 *   <li>{@link #healthCheck(CafeAI)} -- returns a middleware that probes all
 *       registered connections and reports their status.</li>
 * </ol>
 *
 * <pre>{@code
 *   // Environment-driven -- docker-compose, Kubernetes, .env file,
 *   // CI pipeline -- all the same to CafeAI:
 *   var app = CafeAI.create();
 *   Connect.fromEnv().forEach(app::connect);
 *
 *   app.get("/health", Connect.healthCheck(app));
 *   app.listen(8080);
 * }</pre>
 */
public final class Connect {

    private static final Logger log = LoggerFactory.getLogger(Connect.class);

    private Connect() {}

    /**
     * Reads environment variables and returns configured {@link Connection}s.
     *
     * <p>Each variable is optional -- absent variables are silently skipped.
     * The returned connections still need to be passed to {@code app.connect()},
     * which is where probing and registration happen.
     *
     * <p>Recognised variables:
     * <pre>
     *   CAFEAI_AI_PROVIDER    ollama  (a cloud API has no process to probe; register it with app.ai())
     *   CAFEAI_AI_MODEL       Ollama model ID  (default: llama3)
     *   OLLAMA_BASE_URL       Ollama base URL  (default: http://localhost:11434)
     *   CAFEAI_MEMORY         redis
     *   REDIS_URL             redis://[[user]:password@]host[:port][/db]  (rediss:// for TLS)
     *   REDIS_HOST            Redis hostname   (default: localhost)
     *   REDIS_PORT            Redis port       (default: 6379)
     *   CAFEAI_VECTOR_DB      pgvector
     *   DATABASE_URL          PostgreSQL JDBC URL (for pgvector)
     * </pre>
     *
     * @return ordered list of configured connections -- may be empty if no
     *         relevant environment variables are set
     */
    public static List<Connection> fromEnv() {
        return fromEnv(System::getenv);
    }

    /** {@link #fromEnv()} over any variable source, so it can be tested without the real environment. */
    static List<Connection> fromEnv(Function<String, String> getenv) {
        List<Connection> connections = new ArrayList<>();

        // -- AI provider -------------------------------------------------------
        String provider = env(getenv, "CAFEAI_AI_PROVIDER");
        String model    = env(getenv, "CAFEAI_AI_MODEL");

        if ("ollama".equalsIgnoreCase(provider)) {
            String base = env(getenv, "OLLAMA_BASE_URL", "http://localhost:11434");
            String m    = model != null ? model : "llama3";
            connections.add(Ollama.at(base).model(m));
            log.debug("fromEnv: Ollama({}) model={}", base, m);
        }
        // OpenAI and Anthropic have no separate process to probe --
        // they're cloud APIs. Registered directly, not via Connection.

        // -- Memory ------------------------------------------------------------
        String memory = env(getenv, "CAFEAI_MEMORY");
        if ("redis".equalsIgnoreCase(memory)) {
            connections.add(buildRedisConnection(getenv));
        }

        // -- Vector DB ---------------------------------------------------------
        String vectorDb = env(getenv, "CAFEAI_VECTOR_DB");
        if ("pgvector".equalsIgnoreCase(vectorDb)) {
            String url = env(getenv, "DATABASE_URL");
            if (url != null) {
                connections.add(PgVector.at(url));
                log.debug("fromEnv: PgVector({})", Urls.redact(url));
            } else {
                log.warn("CAFEAI_VECTOR_DB=pgvector but DATABASE_URL is not set -- skipping");
            }
        }

        log.info("Connect.fromEnv(): {} connection(s) configured", connections.size());
        return Collections.unmodifiableList(connections);
    }

    /**
     * Returns a middleware that probes all connections registered on the app
     * and reports their current status.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "status": "healthy" | "degraded",
     *   "connections": {
     *     "Redis(redis:6379)":   { "state": "REACHABLE",   "latencyMs": 2 },
     *     "PgVector(jdbc:...)":  { "state": "UNREACHABLE", "detail": "Connection refused" },
     *     "Ollama(http://...)":  { "state": "DEGRADED",    "detail": "Model not pulled" }
     *   }
     * }
     * </pre>
     *
     * <p>HTTP status: {@code 200} if all connections are {@code REACHABLE},
     * {@code 503} if any are {@code UNREACHABLE} or {@code DEGRADED}.
     *
     * @param app the application whose registered connections to probe
     */
    public static Middleware healthCheck(CafeAI app) {
        return (req, res, next) -> {
            // Retrieve the registered connections from app locals
            @SuppressWarnings("unchecked")
            List<Connection> connections = (List<Connection>)
                app.local(io.cafeai.core.Locals.CONNECTIONS);

            if (connections == null || connections.isEmpty()) {
                res.json(Map.of("status", "healthy", "connections", Map.of()));
                return;
            }

            Map<String, Object> statuses = new LinkedHashMap<>();
            boolean allHealthy = true;

            for (Connection conn : connections) {
                HealthStatus status = conn.probe();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("state", status.state().name());
                if (status.latencyMs() >= 0) entry.put("latencyMs", status.latencyMs());
                if (status.detail() != null)  entry.put("detail",    status.detail());
                statuses.put(conn.name(), entry);
                if (!status.isHealthy()) allHealthy = false;
            }

            res.status(allHealthy ? 200 : 503).json(Map.of(
                "status",      allHealthy ? "healthy" : "degraded",
                "connections", statuses
            ));
        };
    }

    // -- Private helpers -------------------------------------------------------

    private static Redis buildRedisConnection(Function<String, String> getenv) {
        String redisUrl = env(getenv, "REDIS_URL");
        if (redisUrl != null && !redisUrl.isBlank()) {
            try {
                var uri = java.net.URI.create(redisUrl);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ((scheme.equals("redis") || scheme.equals("rediss")) && uri.getHost() != null) {
                    var conn = Redis.at(uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 6379));
                    // redis://:password@host and redis://user:password@host: the password follows the colon
                    String userInfo = uri.getUserInfo();
                    if (userInfo != null) {
                        int colon = userInfo.indexOf(':');
                        String password = colon >= 0 ? userInfo.substring(colon + 1) : userInfo;
                        if (!password.isEmpty()) conn.withPassword(password);
                    }
                    if (scheme.equals("rediss")) conn.withSsl(true);
                    String path = uri.getPath();
                    if (path != null && path.length() > 1 && path.substring(1).chars().allMatch(Character::isDigit)) {
                        conn.withDatabase(Integer.parseInt(path.substring(1)));
                    }
                    log.debug("fromEnv: {}", conn.name());
                    return conn;
                }
                log.warn("REDIS_URL is not a redis:// or rediss:// URL with a host -- using REDIS_HOST/REDIS_PORT");
            } catch (Exception e) {
                // The URL can carry a password: never log it.
                log.warn("Could not parse REDIS_URL ({}) -- using REDIS_HOST/REDIS_PORT", e.getClass().getSimpleName());
            }
        }
        String host = env(getenv, "REDIS_HOST", "localhost");
        String portText = env(getenv, "REDIS_PORT", "6379");
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("REDIS_PORT must be a number, got '" + portText + "'", e);
        }
        log.debug("fromEnv: Redis({}:{})", host, port);
        return Redis.at(host + ":" + port);
    }

    private static String env(Function<String, String> getenv, String name) { return getenv.apply(name); }
    private static String env(Function<String, String> getenv, String name, String defaultValue) {
        String v = getenv.apply(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
