package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.connect.HealthStatus;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.rag.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The connectors against real services in containers: probe a live Redis and a live pgvector, register
 * them, and use what was registered. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("connectors — against real services")
class ServicesIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static String redisAddress() {
        return REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    // -- Redis -------------------------------------------------------------------------------------

    @Test @DisplayName("a live Redis probes healthy")
    void redisProbe() {
        HealthStatus status = Redis.at(redisAddress()).probe();

        assertThat(status.state()).isEqualTo(HealthStatus.State.REACHABLE);
    }

    @Test @DisplayName("Redis registers as the memory strategy, and a conversation survives a round trip")
    void redisRegisters() {
        CafeAI app = mock(CafeAI.class);

        Redis.at(redisAddress()).register(app);

        ArgumentCaptor<MemoryStrategy> captor = ArgumentCaptor.forClass(MemoryStrategy.class);
        verify(app).memory(captor.capture());
        MemoryStrategy memory = captor.getValue();
        var context = new ConversationContext("connect-it-1");
        context.addMessage("user", "hello from the integration test");
        memory.store("connect-it-1", context);

        assertThat(memory.exists("connect-it-1")).isTrue();
        assertThat(memory.retrieve("connect-it-1").messages()).extracting(m -> m.content())
            .contains("hello from the integration test");
        memory.evict("connect-it-1");
        assertThat(memory.exists("connect-it-1")).isFalse();
    }

    // -- pgvector ----------------------------------------------------------------------------------

    private static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    @Test @DisplayName("a live pgvector probes healthy with explicit credentials")
    void pgProbe() {
        HealthStatus status = PgVector.at(jdbcUrl()).credentials(POSTGRES.getUsername(), POSTGRES.getPassword())
            .probe();

        assertThat(status.state()).isEqualTo(HealthStatus.State.REACHABLE);
    }

    @Test @DisplayName("wrong credentials are unreachable, not an exception")
    void pgWrongPassword() {
        HealthStatus status = PgVector.at(jdbcUrl()).credentials(POSTGRES.getUsername(), "wrong").probe();

        assertThat(status.state()).isEqualTo(HealthStatus.State.UNREACHABLE);
    }

    @Test @DisplayName("pgvector registers as the vector store, with credentials taken from the URL query")
    void pgRegistersWithCredentialsInTheUrl() {
        String url = jdbcUrl() + (jdbcUrl().contains("?") ? "&" : "?")
            + "user=" + POSTGRES.getUsername() + "&password=" + POSTGRES.getPassword();
        PgVector connection = PgVector.at(url).dimension(4);
        CafeAI app = mock(CafeAI.class);

        assertThat(connection.probe().isHealthy()).isTrue();
        assertThat(connection.name()).doesNotContain(POSTGRES.getPassword()).doesNotContain("password");
        connection.register(app);

        ArgumentCaptor<VectorStore> captor = ArgumentCaptor.forClass(VectorStore.class);
        verify(app).vectordb(captor.capture());
        VectorStore store = captor.getValue();
        store.upsert("a", "alpha", new float[]{1, 0, 0, 0}, "doc", 0);
        store.upsert("b", "beta", new float[]{0, 1, 0, 0}, "doc", 1);
        assertThat(store.count()).isEqualTo(2);
        assertThat(store.search(new float[]{1, 0, 0, 0}, 1)).singleElement()
            .satisfies(hit -> assertThat(hit.content()).isEqualTo("alpha"));
    }
}
