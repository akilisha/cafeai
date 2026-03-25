package io.cafeai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Rung 4: Redis-backed distributed conversation memory via Lettuce.
 *
 * <p>The distributed escape valve. Reach for this when:
 * <ul>
 *   <li>You run multiple application instances and sessions must be shared</li>
 *   <li>You need session TTL enforcement at the infrastructure level</li>
 *   <li>You need sessions to survive application deployments</li>
 * </ul>
 *
 * <p>Uses Lettuce's synchronous API — not reactive. Virtual threads park
 * while the Redis call completes, making sync the right choice here.
 * Blocking code reads as blocking code. No reactive ceremony.
 *
 * <p>Session serialisation: JSON via Jackson. Each session is a single
 * Redis string key {@code cafeai:session:<sessionId>} with a TTL set
 * from {@link RedisConfig#sessionTtl()} (default 24 hours).
 *
 * <p>Connection lifecycle: one connection per strategy instance.
 * Call {@link #close()} on application shutdown.
 */
public final class RedisMemoryStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryStrategy.class);
    private static final String KEY_PREFIX = "cafeai:session:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final RedisConfig                      config;
    private final RedisClient                      client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String>    commands;
    private final Duration                         ttl;

    public RedisMemoryStrategy(RedisConfig config) {
        this.config = config;
        this.ttl    = config.sessionTtl() != null ? config.sessionTtl() : Duration.ofHours(24);

        RedisURI uri = buildUri(config);
        this.client     = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands   = connection.sync();

        log.info("RedisMemoryStrategy: connected to {}:{}, TTL={}",
            config.host(), config.port(), ttl);
    }

    @Override
    public void store(String sessionId, ConversationContext context) {
        try {
            String json = MAPPER.writeValueAsString(context);
            commands.set(key(sessionId), json,
                SetArgs.Builder.ex(ttl.getSeconds()));
        } catch (Exception e) {
            log.error("Redis store failed for session {}: {}", sessionId, e.getMessage());
            throw new RedisMemoryException("Cannot store session: " + sessionId, e);
        }
    }

    @Override
    public ConversationContext retrieve(String sessionId) {
        try {
            String json = commands.get(key(sessionId));
            if (json == null) return null;
            // Refresh TTL on access — active sessions stay alive
            commands.expire(key(sessionId), ttl.getSeconds());
            return MAPPER.readValue(json, ConversationContext.class);
        } catch (Exception e) {
            log.warn("Redis retrieve failed for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void evict(String sessionId) {
        commands.del(key(sessionId));
    }

    @Override
    public boolean exists(String sessionId) {
        Long count = commands.exists(key(sessionId));
        return count != null && count > 0;
    }

    /**
     * Closes the Lettuce connection and shuts down the Redis client.
     * Call on application shutdown.
     */
    public void close() {
        try {
            connection.close();
            client.shutdown();
            log.debug("RedisMemoryStrategy: connection closed");
        } catch (Exception e) {
            log.warn("Error closing Redis connection: {}", e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private static RedisURI buildUri(RedisConfig cfg) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(cfg.host())
            .withPort(cfg.port())
            .withDatabase(cfg.database())
            .withSsl(cfg.ssl());

        if (cfg.password() != null && !cfg.password().isBlank()) {
            builder.withPassword(cfg.password().toCharArray());
        }
        if (cfg.sessionTtl() != null) {
            builder.withTimeout(cfg.sessionTtl());
        }

        return builder.build();
    }

    public static final class RedisMemoryException extends RuntimeException {
        public RedisMemoryException(String msg, Throwable cause) { super(msg, cause); }
    }
}
