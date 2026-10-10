package io.cafeai.desk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.PgVectorConfig;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.RagIngestion;
import io.cafeai.core.rag.Retriever;
import io.cafeai.core.rag.Source;
import io.cafeai.core.rag.VectorStore;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import io.cafeai.core.session.SessionStore;
import io.cafeai.identity.Auth;
import io.cafeai.identity.Issuer;
import io.cafeai.identity.OAuthCredentials;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

/**
 * acme-desk: a team knowledge desk where <b>what you can read decides what the AI tells you</b>.
 *
 * <ul>
 *   <li>Three documents: a Q3 finance report (finance only), a staff handbook (staff and
 *       finance), a public FAQ (everyone signed in).</li>
 *   <li>PostgreSQL decides who reads which, with row-level security over the caller's claims.
 *       CafeAI holds no permissions; it hands PostgreSQL the verified caller on every query.</li>
 *   <li>People sign in in the browser and chat over a WebSocket that runs as them; the model is
 *       called on their behalf (token exchange), with no API key.</li>
 *   <li>The same desk is an MCP tool, {@code ask_desk}, for AI agents with their own identity,
 *       protected per the MCP authorization specification.</li>
 * </ul>
 *
 * <p>Run {@code docker compose -f capstones/acme-desk/docker-compose.yml up -d}, have Ollama with
 * {@code llama3.2}, then {@code ./gradlew :capstones:acme-desk:run} and open http://localhost:8090.
 * {@code ./gradlew :capstones:acme-desk:demo} runs the whole story without a browser.
 */
public class DeskApp {

    static final int PORT = 8090;
    static final String BASE = "http://localhost:" + PORT;
    static final String ISSUER = env("ISSUER", "http://localhost:8280/realms/acme-desk");
    static final String MODEL = env("OLLAMA_MODEL", "llama3.2");
    private static final String TABLE = "desk_chunks";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        start();
        System.out.println("\nacme-desk on " + BASE + "  (alice/alice: finance, bob/bob: staff)\n");
    }

    /** Starts the desk; returns the running app. */
    static CafeAI start() throws Exception {
        var embeddings = EmbeddingProvider.local();   // 384 dimensions, no API key
        prepareDocuments(embeddings);

        var issuer = Issuer.discover(ISSUER);
        var app = CafeAI.create();

        // ── The model, called on each caller's behalf ─────────────────────────────────
        app.ai(OpenAI.of(MODEL).withBaseUrl("http://localhost:11434/v1")
            .withCredentials(OAuthCredentials.tokenExchange(issuer, "desk-api", "desk-api-secret", "model-gateway")));
        app.system("""
            You answer questions for Acme staff from the documents you are given, in at most two
            sentences. If the documents don't contain the answer, say you don't have that information.""");
        app.memory(MemoryStrategy.inMemory());

        // ── RAG: PostgreSQL decides, per caller, which chunks are retrieved ──────────────
        app.vectordb(VectorStore.pgVector(pg("desk_app", "desk_app").rowLevelSecurity(true).build()));
        app.embed(embeddings);
        app.rag(Retriever.semantic(3));

        // ── Who is calling: browsers sign in; agents bring a token ──────────────────────
        app.filter(Middleware.session(SessionStore.inMemory()));
        app.filter(CafeAI.urlencoded());   // the sign-out form's CSRF token
        app.filter(Auth.login(issuer, "desk-web", "desk-web-secret", BASE + "/auth/callback")
            .afterSignOut(BASE + "/"));
        app.filter(Auth.bearer(issuer, "desk-api", BASE + "/mcp").optional());

        app.get("/", (req, res, next) -> res.type("text/html").send(
            req.identity().map(who -> chatPage(who, Auth.csrfToken(req).orElse(""))).orElse(WELCOME)));

        // One question, answered under the caller's identity: for MCP agents, and for anyone with a token.
        app.get("/ask", Auth.signedIn(), (req, res, next) ->
            res.json(answer(app, req.query("question"))));

        // The chat: a WebSocket that runs as whoever opened it.
        app.ws("/ws", new WsHandler() {
            @Override public void onOpen(WsSession session) {
                if (session.identity().isEmpty()) session.close(1008, "Sign in first");
            }
            @Override public void onMessage(WsSession session, String question) {
                try {
                    session.send(JSON.writeValueAsString(answer(app, question)));
                } catch (Exception e) {
                    session.send("{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
                }
            }
        });

        // ── The desk as an MCP tool, for agents with their own identity ─────────────────
        app.mcp().tool("ask_desk", "Ask Acme's knowledge desk a question", "GET /ask", Question.class);
        Auth.mcp(app, issuer, BASE + "/mcp");

        var started = new java.util.concurrent.CountDownLatch(1);
        app.listen(PORT, started::countDown);
        started.await();
        return app;
    }

    /** The MCP tool's input. */
    public record Question(String question) { }

    /** Asks the model, with RAG under the caller's identity; returns the answer and the sources used. */
    static Map<String, Object> answer(CafeAI app, String question) {
        Identity who = Identity.current().orElseThrow();
        PromptResponse response = app.prompt(question).session("desk").call();
        List<String> sources = response.ragDocuments().stream().map(RagDocument::sourceId).distinct().sorted().toList();
        return Map.of("asker", who.name().orElse(who.claim("preferred_username").map(String::valueOf).orElse(who.subject())),
            "answer", response.text(), "sources", sources);
    }

    // ── The documents, and the database owner's part ────────────────────────────────────

    /**
     * Ingests the documents as the table's owner, then sets up row-level security: the database
     * owner's job, done here so the capstone runs with one command. CafeAI's app never does this:
     * it connects as {@code desk_app}, which the policy applies to.
     */
    private static void prepareDocuments(EmbeddingProvider embeddings) throws Exception {
        VectorStore store = VectorStore.pgVector(pg("desk_owner", "desk_owner").build());
        try {
            RagIngestion.ingest(Source.text("""
                Acme Q3 finance report. Q3 revenue was 12.4 million euros, up 8 percent on Q2. Operating
                margin was 14 percent. The board approved a 2 million euro budget for the Lisbon office.""",
                "q3-finance-report"), store, embeddings);
            RagIngestion.ingest(Source.text("""
                Acme staff handbook. Every employee has 25 days of annual leave a year, plus public
                holidays. Leave requests go to your manager at least two weeks ahead.""",
                "staff-handbook"), store, embeddings);
            RagIngestion.ingest(Source.text("""
                Acme public FAQ. The office opens at 8:30 and closes at 18:00. Visitors sign in at
                reception on the ground floor.""",
                "public-faq"), store, embeddings);
        } finally {
            if (store instanceof AutoCloseable pool) pool.close();   // the owner's connection pool
        }
        try (var c = DriverManager.getConnection(jdbcUrl(), "desk_owner", "desk_owner"); var st = c.createStatement()) {
            for (String sql : List.of(
                "CREATE TABLE IF NOT EXISTS source_acl (source_id text, grp text)",
                "TRUNCATE source_acl",
                "INSERT INTO source_acl VALUES ('q3-finance-report', 'finance'), ('staff-handbook', 'staff'),"
                    + " ('staff-handbook', 'finance'), ('public-faq', '*')",
                "GRANT SELECT ON source_acl TO desk_app",
                "GRANT SELECT, INSERT, UPDATE, DELETE ON " + TABLE + " TO desk_app",
                "ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY",
                "DROP POLICY IF EXISTS read_by_group ON " + TABLE,
                // A chunk is readable when its source is public and there is a caller, or one of
                // the caller's groups may read its source.
                "CREATE POLICY read_by_group ON " + TABLE + " FOR SELECT USING (EXISTS ("
                    + " SELECT 1 FROM source_acl a WHERE a.source_id = " + TABLE + ".metadata->>'sourceId' AND ("
                    + "  (a.grp = '*' AND COALESCE(current_setting('request.jwt.claims', true), '') <> '')"
                    + "  OR a.grp IN (SELECT jsonb_array_elements_text(COALESCE("
                    + "   NULLIF(current_setting('request.jwt.claims', true), '')::jsonb -> 'groups', '[]'::jsonb))))))")) {
                st.execute(sql);
            }
        }
    }

    private static PgVectorConfig.Builder pg(String user, String password) {
        return PgVectorConfig.builder().host("localhost").port(5433).database("desk")
            .user(user).password(password).table(TABLE).dimension(384);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://localhost:5433/desk";
    }

    // ── pages ───────────────────────────────────────────────────────────────────────────

    private static final String WELCOME = page("""
        <p>Acme's knowledge desk answers from the documents <i>you</i> may read.</p>
        <p><a href="/auth/login?return=/">Sign in</a> as alice/alice (finance), ask <i>What was Q3
        revenue?</i>, then sign out and ask again as bob/bob (staff).</p>""");

    /** The chat; signing out is a POST carrying the session's CSRF token, so no other site can do it. */
    private static String chatPage(Identity who, String csrf) {
        return page("""
            <form method="post" action="/auth/logout">Signed in as <b id="who"></b>.
              <input type="hidden" name="_csrf" value="%s"><button>Sign out</button></form>
            <p>Ask something, e.g. <i>What was Q3 revenue?</i> or <i>How many days of leave do I get?</i></p>
            <div id="log"></div>
            <form id="ask"><input id="q" size="60" autofocus> <button>Ask</button></form>
            <script>
              document.getElementById('who').textContent = %s;
              const ws = new WebSocket('ws://' + location.host + '/ws');
              const log = document.getElementById('log');
              ws.onmessage = e => {
                const r = JSON.parse(e.data), p = document.createElement('p');
                p.textContent = r.error ? 'Error: ' + r.error
                  : r.answer + '  (sources: ' + (r.sources.join(', ') || 'none you may read') + ')';
                log.append(p);
              };
              document.getElementById('ask').onsubmit = e => {
                e.preventDefault();
                const q = document.getElementById('q'), p = document.createElement('p');
                p.innerHTML = '<b></b>'; p.firstChild.textContent = q.value; log.append(p);
                ws.send(q.value); q.value = '';
              };
            </script>""".formatted(csrf, jsString(who.name().orElse(who.subject()))));
    }

    private static String page(String body) {
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>acme-desk</title>
            <style>body { font: 16px/1.5 system-ui, sans-serif; max-width: 46rem; margin: 2rem auto; padding: 0 1rem }</style>
            </head><body><h1>acme-desk</h1>%s</body></html>""".formatted(body);
    }

    private static String jsString(String s) {
        try {
            return JSON.writeValueAsString(s).replace("<", "\\u003c");
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
