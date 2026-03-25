package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.memory.MemoryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.time.Duration;

/**
 * Out-of-process Redis connection.
 *
 * <p>Probes the Redis port for TCP reachability, then registers
 * a {@code MemoryStrategy.redis()} with the application.
 *
 * <pre>{@code
 *   app.connect(Redis.at("redis:6379"));
 *   app.connect(Redis.at("redis.prod.internal:6379").withPassword("secret"));
 *   app.connect(Redis.at("redis:6379")
 *       .onUnavailable(Fallback.use(MemoryStrategy.inMemory())));
 * }</pre>
 */
public final class Redis implements Connection {

    private static final Logger log = LoggerFactory.getLogger(Redis.class);
    private static final int PROBE_TIMEOUT_MS = 2000;

    private final String host;
    private final int    port;
    private final RedisConfig.Builder configBuilder;

    private Redis(String host, int port) {
        this.host          = host;
        this.port          = port;
        this.configBuilder = RedisConfig.builder().host(host).port(port);
    }

    /**
     * Creates a Redis connection. Accepts {@code "host:port"} or just {@code "host"} (port 6379).
     */
    public static Redis at(String hostAndPort) {
        String[] parts = hostAndPort.split(":");
        String host = parts[0];
        int port    = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;
        return new Redis(host, port);
    }

    public Redis withPassword(String password) {
        configBuilder.password(password);
        return this;
    }

    public Redis withDatabase(int db) {
        configBuilder.database(db);
        return this;
    }

    public Redis withTtl(Duration ttl) {
        configBuilder.sessionTtl(ttl);
        return this;
    }

    public Redis withSsl(boolean ssl) {
        configBuilder.ssl(ssl);
        return this;
    }

    @Override public String name()      { return "Redis(" + host + ":" + port + ")"; }
    @Override public ServiceType type() { return ServiceType.MEMORY; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return HealthStatus.reachable(name(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    @Override
    public void register(CafeAI app) {
        app.memory(MemoryStrategy.redis(configBuilder.build()));
        log.info("Connected: {} → registered as memory strategy", name());
    }
}
