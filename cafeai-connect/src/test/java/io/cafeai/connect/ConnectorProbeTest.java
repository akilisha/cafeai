package io.cafeai.connect;

import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.connect.Connection;
import io.cafeai.core.connect.HealthStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Each connector's {@code probe()} and {@code register()} against a fake local service: a JDK
 * {@link HttpServer} for Ollama, a bare {@link ServerSocket} for Redis, a closed port for "down".
 */
@DisplayName("connector probes")
class ConnectorProbeTest {

    private HttpServer http;

    @AfterEach
    void stop() {
        if (http != null) http.stop(0);
    }

    private static int closedPort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    // -- Ollama ------------------------------------------------------------------------------

    @Nested @DisplayName("Ollama")
    class OllamaProbe {

        private String serve(int status, String body) throws IOException {
            http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            http.createContext("/api/tags", ex -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            http.start();
            return "http://localhost:" + http.getAddress().getPort();
        }

        private static final String TAGS = "{\"models\":[{\"name\":\"llama3:latest\",\"model\":\"llama3:latest\"},"
            + "{\"name\":\"llama3.1:8b\",\"model\":\"llama3.1:8b\"},{\"name\":\"mistral:7b\",\"model\":\"mistral:7b\"}]}";

        @Test @DisplayName("reachable when the model is pulled: an untagged id means :latest")
        void pulled() throws IOException {
            HealthStatus s = Ollama.at(serve(200, TAGS)).model("llama3").probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.REACHABLE);
            assertThat(s.isHealthy()).isTrue();
            assertThat(s.latencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test @DisplayName("reachable for an explicit tag that is installed")
        void explicitTag() throws IOException {
            assertThat(Ollama.at(serve(200, TAGS)).model("mistral:7b").probe().isHealthy()).isTrue();
        }

        @Test @DisplayName("degraded, with the command to fix it, when Ollama runs but the model is not pulled")
        void notPulled() throws IOException {
            HealthStatus s = Ollama.at(serve(200, TAGS)).model("gemma3").probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.DEGRADED);
            assertThat(s.detail()).contains("gemma3").contains("ollama pull gemma3");
        }

        @Test @DisplayName("a model whose name merely contains the id is not a match")
        void substringIsNotAMatch() throws IOException {
            // only llama3.1:8b installed; asking for "llama3" must not be satisfied by it
            String onlyPointOne = "{\"models\":[{\"name\":\"llama3.1:8b\"}]}";

            assertThat(Ollama.at(serve(200, onlyPointOne)).model("llama3").probe().state())
                .isEqualTo(HealthStatus.State.DEGRADED);
        }

        @Test @DisplayName("an untagged id does not match a different tag of the same model")
        void differentTag() throws IOException {
            assertThat(Ollama.at(serve(200, "{\"models\":[{\"name\":\"mistral:7b\"}]}")).model("mistral:13b").probe().state())
                .isEqualTo(HealthStatus.State.DEGRADED);
        }

        @Test @DisplayName("an HTTP error is unreachable, with the status")
        void httpError() throws IOException {
            HealthStatus s = Ollama.at(serve(500, "boom")).model("llama3").probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.UNREACHABLE);
            assertThat(s.detail()).contains("500");
        }

        @Test @DisplayName("nothing listening is unreachable, not an exception")
        void refused() throws IOException {
            HealthStatus s = Ollama.at("http://localhost:" + closedPort()).model("llama3").probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.UNREACHABLE);
            assertThat(s.isHealthy()).isFalse();
        }

        @Test @DisplayName("a trailing slash on the base URL is ignored, and a userinfo part never appears in the name")
        void nameIsClean() {
            assertThat(Ollama.at("http://host:11434/").model("m").name()).isEqualTo("Ollama(http://host:11434/m)");
            assertThat(Ollama.at("http://user:secret@host:11434").model("m").name())
                .doesNotContain("secret").doesNotContain("user");
        }

        @Test @DisplayName("register() gives the app an Ollama provider for that model")
        void register() {
            CafeAI app = mock(CafeAI.class);

            Ollama.at("http://host:11434").model("llama3").register(app);

            ArgumentCaptor<AiProvider> captor = ArgumentCaptor.forClass(AiProvider.class);
            verify(app).ai(captor.capture());
            assertThat(captor.getValue().name()).isEqualTo("ollama");
            assertThat(captor.getValue().modelId()).isEqualTo("llama3");
        }
    }

    // -- Redis (the probe only: register() connects, and is covered against a real Redis) -----

    @Nested @DisplayName("Redis")
    class RedisProbe {

        @Test @DisplayName("reachable when something accepts the connection")
        void reachable() throws IOException {
            try (var server = new ServerSocket(0)) {
                HealthStatus s = Redis.at("localhost:" + server.getLocalPort()).probe();

                assertThat(s.state()).isEqualTo(HealthStatus.State.REACHABLE);
                assertThat(s.latencyMs()).isGreaterThanOrEqualTo(0);
            }
        }

        @Test @DisplayName("unreachable, not an exception, when nothing is listening")
        void unreachable() throws IOException {
            HealthStatus s = Redis.at("localhost:" + closedPort()).probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.UNREACHABLE);
        }

        @Test @DisplayName("a bare host uses port 6379; the name and type are stable")
        void parsing() {
            Redis r = Redis.at("cache.internal");

            assertThat(r.config().port()).isEqualTo(6379);
            assertThat(r.name()).isEqualTo("Redis(cache.internal:6379)");
            assertThat(r.type()).isEqualTo(Connection.ServiceType.MEMORY);
        }

        @Test @DisplayName("a non-numeric port is refused with a message that says so")
        void badPort() {
            assertThatThrownBy(() -> Redis.at("host:abc")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port").hasMessageContaining("abc");
        }

        @Test @DisplayName("withPassword, withDatabase, withTtl and withSsl reach the configuration")
        void settings() {
            var c = Redis.at("h:1").withPassword("pw").withDatabase(4).withTtl(java.time.Duration.ofMinutes(5))
                .withSsl(true).config();

            assertThat(c.password()).isEqualTo("pw");
            assertThat(c.database()).isEqualTo(4);
            assertThat(c.sessionTtl()).isEqualTo(java.time.Duration.ofMinutes(5));
            assertThat(c.ssl()).isTrue();
        }
    }

    // -- PgVector ------------------------------------------------------------------------------

    @Nested @DisplayName("PgVector")
    class PgProbe {

        @Test @DisplayName("unreachable, not an exception, when nothing is listening")
        void unreachable() throws IOException {
            HealthStatus s = PgVector.at("jdbc:postgresql://localhost:" + closedPort() + "/db").probe();

            assertThat(s.state()).isEqualTo(HealthStatus.State.UNREACHABLE);
        }

        @Test @DisplayName("the name hides credentials, in userinfo or in the query")
        void nameHidesCredentials() {
            assertThat(PgVector.at("jdbc:postgresql://h:5432/db?user=app&password=hunter2").name())
                .isEqualTo("PgVector(jdbc:postgresql://h:5432/db)");
            assertThat(PgVector.at("jdbc:postgresql://app:hunter2@h:5432/db").name())
                .doesNotContain("hunter2").doesNotContain("app:");
        }

        @Test @DisplayName("register() insists on a dimension: the wrong one corrupts the index rather than failing")
        void dimensionRequired() {
            assertThatThrownBy(() -> PgVector.at("jdbc:postgresql://h/db").register(mock(CafeAI.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dimension");
        }

        @Test @DisplayName("the JDBC URL is turned into host, port and database")
        void urlParsing() {
            var c = PgVector.at("jdbc:postgresql://db.internal:6543/cafeai").dimension(384).toConfig();

            assertThat(c.host()).isEqualTo("db.internal");
            assertThat(c.port()).isEqualTo(6543);
            assertThat(c.database()).isEqualTo("cafeai");
            assertThat(c.dimension()).isEqualTo(384);
        }

        @Test @DisplayName("credentials() win; otherwise user= and password= from the URL query are used")
        void credentialSources() {
            var explicit = PgVector.at("jdbc:postgresql://h/db?user=urluser&password=urlpw")
                .credentials("explicit", "pw").dimension(4).toConfig();
            var fromUrl = PgVector.at("jdbc:postgresql://h/db?user=urluser&password=url%40pw")
                .dimension(4).toConfig();

            assertThat(explicit.user()).isEqualTo("explicit");
            assertThat(explicit.password()).isEqualTo("pw");
            assertThat(fromUrl.user()).isEqualTo("urluser");
            assertThat(fromUrl.password()).as("percent-decoded").isEqualTo("url@pw");
        }
    }
}
