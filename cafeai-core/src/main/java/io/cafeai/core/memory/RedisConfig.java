package io.cafeai.core.memory;

import java.time.Duration;

/**
 * Redis connection configuration for {@link MemoryStrategy#redis(RedisConfig)}.
 *
 * <pre>{@code
 *   app.memory(MemoryStrategy.redis(
 *       RedisConfig.of("localhost", 6379)));
 *
 *   app.memory(MemoryStrategy.redis(
 *       RedisConfig.builder()
 *           .host("redis.prod.internal")
 *           .port(6379)
 *           .password("secret")
 *           .sessionTtl(Duration.ofHours(24))
 *           .build()));
 * }</pre>
 */
public final class RedisConfig {

    private final String   host;
    private final int      port;
    private final String   password;
    private final int      database;
    private final Duration sessionTtl;
    private final boolean  ssl;

    private RedisConfig(Builder builder) {
        this.host       = builder.host;
        this.port       = builder.port;
        this.password   = builder.password;
        this.database   = builder.database;
        this.sessionTtl = builder.sessionTtl;
        this.ssl        = builder.ssl;
    }

    /** Convenience factory for minimal configuration. */
    public static RedisConfig of(String host, int port) {
        return builder().host(host).port(port).build();
    }

    public static Builder builder() { return new Builder(); }

    public String   host()       { return host; }
    public int      port()       { return port; }
    public String   password()   { return password; }
    public int      database()   { return database; }
    public Duration sessionTtl() { return sessionTtl; }
    public boolean  ssl()        { return ssl; }

    public static final class Builder {
        private String   host       = "localhost";
        private int      port       = 6379;
        private String   password   = null;
        private int      database   = 0;
        private Duration sessionTtl = Duration.ofHours(24);
        private boolean  ssl        = false;

        public Builder host(String host)           { this.host = host;             return this; }
        public Builder port(int port)              { this.port = port;             return this; }
        public Builder password(String password)   { this.password = password;     return this; }
        public Builder database(int database)      { this.database = database;     return this; }
        public Builder sessionTtl(Duration ttl)    { this.sessionTtl = ttl;        return this; }
        public Builder ssl(boolean ssl)            { this.ssl = ssl;               return this; }

        public RedisConfig build() { return new RedisConfig(this); }
    }
}
