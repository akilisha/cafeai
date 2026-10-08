package io.cafeai.rag;

import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.VectorStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL decides who reads which chunks, with row-level security over the caller's claims,
 * the way a database owner would set it up: an app role that does not own the table, a table of
 * who may read each source, and a policy joining the two.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PgVector with row-level security: PostgreSQL decides who reads what")
class PgVectorRowLevelSecurityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cafeai").withUsername("cafeai").withPassword("cafeai");

    private static final String TABLE = "rls_chunks";
    private static final float[] QUERY = {1f, 0f, 0f};

    private static PgVectorStoreAdapter store;
    private static CafeAI app;

    private static PgVectorConfig config(String user, String password, String table, boolean rls) {
        return PgVectorConfig.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getMappedPort(5432)).database("cafeai")
                .user(user).password(password).table(table).dimension(3)
                .maxPoolSize(1)                     // one connection: every caller shares it
                .rowLevelSecurity(rls).build();
    }

    private static void sql(String... statements) throws Exception {
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "cafeai", "cafeai");
             var st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        // The owner (a superuser here) creates and fills the tables.
        try (var owner = new PgVectorStoreAdapter(config("cafeai", "cafeai", TABLE, false))) {
            owner.upsert("finance-1", "Q3 revenue was 12M", new float[]{1f, 0f, 0f}, "finance-report", 0);
            owner.upsert("faq-1", "Opening hours are 9 to 5", new float[]{0.9f, 0.1f, 0f}, "public-faq", 0);
        }
        try (var owner = new PgVectorStoreAdapter(config("cafeai", "cafeai", "plain_chunks", false))) {
            owner.upsert("x", "anything", new float[]{1f, 0f, 0f}, "s", 0);
        }
        // The database owner's policy: who may read each source, checked against the caller's groups.
        sql("CREATE ROLE app_user LOGIN PASSWORD 'app' NOSUPERUSER NOBYPASSRLS",
            "GRANT USAGE, CREATE ON SCHEMA public TO app_user",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON " + TABLE + ", plain_chunks TO app_user",
            "CREATE TABLE source_acl (source_id text, grp text)",
            "INSERT INTO source_acl VALUES ('finance-report', 'finance'), ('public-faq', 'staff'), "
                    + "('public-faq', 'finance')",
            "GRANT SELECT ON source_acl TO app_user",
            "ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY",
            "CREATE POLICY read_by_group ON " + TABLE + " FOR SELECT USING (EXISTS ("
                    + "SELECT 1 FROM source_acl a WHERE a.source_id = " + TABLE + ".metadata->>'sourceId' "
                    + "AND a.grp IN (SELECT jsonb_array_elements_text(COALESCE("
                    + "NULLIF(current_setting('request.jwt.claims', true), '')::jsonb -> 'groups', '[]'::jsonb)))))");

        store = new PgVectorStoreAdapter(config("app_user", "app", TABLE, true));

        app = CafeAI.create();
        // Stands in for cafeai-identity, which puts the verified caller on the request.
        app.filter((req, res, next) -> {
            String who = req.header("X-Test-Subject");
            if (who != null) {
                List<String> groups = Arrays.stream(req.header("X-Test-Groups").split(","))
                        .filter(g -> !g.isBlank()).toList();
                req.setAttribute(Attributes.IDENTITY, Identity.builder("https://issuer.example.com", who)
                        .expiresAt(Instant.now().plusSeconds(3600)).groups(groups)
                        .claims(Map.of("groups", groups)).build());
            }
            next.run();
        });
        app.get("/search", (req, res, next) -> res.send(store.search(QUERY, 10).stream()
                .map(RagDocument::sourceId).sorted().collect(Collectors.joining(","))));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterAll
    static void tearDown() {
        if (app != null) app.stop();
        if (store != null) store.close();
    }

    private static String searchAs(String subject, String groups) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/search"));
        if (subject != null) request.header("X-Test-Subject", subject).header("X-Test-Groups", groups);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test @DisplayName("each caller reads only the sources their groups may read")
    void perCaller() throws Exception {
        assertThat(searchAs("fiona", "finance")).isEqualTo("finance-report,public-faq");
        assertThat(searchAs("sam", "staff")).isEqualTo("public-faq");
        assertThat(searchAs("guest", "")).isEmpty();
        assertThat(searchAs(null, null)).isEmpty();   // anonymous: no claims, no rows
    }

    @Test @DisplayName("a pooled connection never carries a caller's claims into the next query")
    void noLeakBetweenCallers() throws Exception {
        assertThat(searchAs("fiona", "finance")).isEqualTo("finance-report,public-faq");
        assertThat(searchAs(null, null)).isEmpty();
        assertThat(searchAs("sam", "staff")).isEqualTo("public-faq");
        assertThat(searchAs("guest", "")).isEmpty();
    }

    @Test @DisplayName("with no request in scope there are no claims, so no rows")
    void noRequestNoRows() {
        assertThat(store.search(QUERY, 10)).isEmpty();
    }

    @Test @DisplayName("it declares per-caller access, which an app serving verified callers requires")
    void declaresPerCaller() {
        assertThat(store.access()).isEqualTo(VectorStore.Access.PER_CALLER);
    }

    @Test @DisplayName("refuses to start when PostgreSQL wouldn't enforce it: row-level security off")
    void refusesWithoutRowLevelSecurity() {
        assertThatThrownBy(() -> new PgVectorStoreAdapter(config("app_user", "app", "plain_chunks", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not enabled");
    }

    @Test @DisplayName("refuses to start when PostgreSQL wouldn't enforce it: a superuser bypasses it")
    void refusesForASuperuser() {
        assertThatThrownBy(() -> new PgVectorStoreAdapter(config("cafeai", "cafeai", TABLE, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("superuser");
    }
}
