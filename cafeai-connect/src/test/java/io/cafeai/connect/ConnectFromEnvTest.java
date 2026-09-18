package io.cafeai.connect;

import io.cafeai.core.connect.Connection;
import io.cafeai.core.memory.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code Connect.fromEnv()} reads the process environment; these tests give it a map instead, so every
 * variable it recognises is checked without touching the real one.
 */
@DisplayName("Connect.fromEnv()")
class ConnectFromEnvTest {

    private static List<Connection> from(Map<String, String> env) {
        return Connect.fromEnv(env::get);
    }

    @Test @DisplayName("an empty environment configures nothing")
    void empty() {
        assertThat(from(Map.of())).isEmpty();
    }

    @Test @DisplayName("the returned list cannot be modified")
    void unmodifiable() {
        assertThatThrownBy(() -> from(Map.of()).add(Ollama.at("http://x").model("m")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("variables for services that cannot be probed are ignored, not guessed at")
    void unsupportedValuesIgnored() {
        assertThat(from(Map.of("CAFEAI_AI_PROVIDER", "openai", "CAFEAI_MEMORY", "mapped",
            "CAFEAI_VECTOR_DB", "inmemory"))).isEmpty();
    }

    @Nested @DisplayName("Ollama")
    class OllamaVars {
        @Test @DisplayName("defaults: localhost:11434 and llama3")
        void defaults() {
            var conns = from(Map.of("CAFEAI_AI_PROVIDER", "ollama"));

            assertThat(conns).hasSize(1);
            assertThat(conns.get(0).type()).isEqualTo(Connection.ServiceType.LLM);
            assertThat(conns.get(0).name()).isEqualTo("Ollama(http://localhost:11434/llama3)");
        }

        @Test @DisplayName("the base URL and model come from OLLAMA_BASE_URL and CAFEAI_AI_MODEL; the provider name is case-insensitive")
        void explicit() {
            var conns = from(Map.of("CAFEAI_AI_PROVIDER", "OLLAMA", "OLLAMA_BASE_URL", "http://gpu:11434/",
                "CAFEAI_AI_MODEL", "mistral"));

            assertThat(conns.get(0).name()).isEqualTo("Ollama(http://gpu:11434/mistral)");
        }
    }

    @Nested @DisplayName("Redis")
    class RedisVars {
        private RedisConfig config(Map<String, String> env) {
            var conns = from(env);
            assertThat(conns).hasSize(1);
            assertThat(conns.get(0).type()).isEqualTo(Connection.ServiceType.MEMORY);
            return ((Redis) conns.get(0)).config();
        }

        @Test @DisplayName("defaults to localhost:6379")
        void defaults() {
            var c = config(Map.of("CAFEAI_MEMORY", "redis"));
            assertThat(c.host()).isEqualTo("localhost");
            assertThat(c.port()).isEqualTo(6379);
        }

        @Test @DisplayName("REDIS_HOST and REDIS_PORT are used")
        void hostAndPort() {
            var c = config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_HOST", "cache.internal", "REDIS_PORT", "6390"));
            assertThat(c.host()).isEqualTo("cache.internal");
            assertThat(c.port()).isEqualTo(6390);
        }

        @Test @DisplayName("a non-numeric REDIS_PORT fails with a message that names the variable")
        void badPort() {
            assertThatThrownBy(() -> from(Map.of("CAFEAI_MEMORY", "redis", "REDIS_PORT", "sixty")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REDIS_PORT").hasMessageContaining("sixty");
        }

        @Test @DisplayName("REDIS_URL: host and port")
        void urlHostPort() {
            var c = config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "redis://cache.internal:6380"));
            assertThat(c.host()).isEqualTo("cache.internal");
            assertThat(c.port()).isEqualTo(6380);
            assertThat(c.password()).isNull();
        }

        @Test @DisplayName("REDIS_URL: the password follows the colon, whether or not a user name is given")
        void urlPassword() {
            assertThat(config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "redis://:s3cret@h:6379")).password())
                .isEqualTo("s3cret");
            assertThat(config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "redis://default:s3cret@h:6379")).password())
                .as("the user name is not part of the password").isEqualTo("s3cret");
            assertThat(config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "redis://token-only@h")).password())
                .as("a bare token is the password").isEqualTo("token-only");
        }

        @Test @DisplayName("REDIS_URL: a /db path selects the database, and rediss:// turns TLS on")
        void urlDatabaseAndTls() {
            var c = config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "rediss://h:6380/3"));
            assertThat(c.database()).isEqualTo(3);
            assertThat(c.ssl()).isTrue();
        }

        @Test @DisplayName("a REDIS_URL that cannot be used falls back to REDIS_HOST / REDIS_PORT")
        void unusableUrlFallsBack() {
            var c = config(Map.of("CAFEAI_MEMORY", "redis", "REDIS_URL", "http://not-redis:1", "REDIS_HOST", "fallback"));
            assertThat(c.host()).isEqualTo("fallback");
        }
    }

    @Nested @DisplayName("pgvector")
    class PgVars {
        @Test @DisplayName("uses DATABASE_URL")
        void databaseUrl() {
            var conns = from(Map.of("CAFEAI_VECTOR_DB", "pgvector", "DATABASE_URL", "jdbc:postgresql://db:5432/cafeai"));

            assertThat(conns).hasSize(1);
            assertThat(conns.get(0).type()).isEqualTo(Connection.ServiceType.VECTOR_DB);
            assertThat(conns.get(0).name()).isEqualTo("PgVector(jdbc:postgresql://db:5432/cafeai)");
        }

        @Test @DisplayName("without DATABASE_URL it is skipped")
        void skippedWithoutUrl() {
            assertThat(from(Map.of("CAFEAI_VECTOR_DB", "pgvector"))).isEmpty();
        }

        @Test @DisplayName("credentials in DATABASE_URL never appear in the connection's name")
        void nameHidesCredentials() {
            var conns = from(Map.of("CAFEAI_VECTOR_DB", "pgvector",
                "DATABASE_URL", "jdbc:postgresql://db:5432/cafeai?user=app&password=hunter2"));

            assertThat(conns.get(0).name()).doesNotContain("hunter2").doesNotContain("app").doesNotContain("password");
        }
    }

    @Test @DisplayName("several variables give several connections, in a fixed order")
    void allTogether() {
        var conns = from(Map.of("CAFEAI_AI_PROVIDER", "ollama", "CAFEAI_MEMORY", "redis",
            "CAFEAI_VECTOR_DB", "pgvector", "DATABASE_URL", "jdbc:postgresql://db/x"));

        assertThat(conns).extracting(Connection::type).containsExactly(
            Connection.ServiceType.LLM, Connection.ServiceType.MEMORY, Connection.ServiceType.VECTOR_DB);
    }
}
