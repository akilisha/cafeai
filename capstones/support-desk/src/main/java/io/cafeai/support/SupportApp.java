package io.cafeai.support;

import io.cafeai.connect.Fallback;
import io.cafeai.connect.Ollama;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import io.cafeai.observability.ObserveStrategy;
import io.cafeai.rag.EmbeddingModel;
import io.cafeai.rag.Retriever;
import io.cafeai.rag.VectorStore;
import io.cafeai.security.AiSecurity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SupportApp {

    static final String SYSTEM_PROMPT = """
            You are a technical support assistant for Helios, an open source
            Java connection pooling library.

            Answer questions using the provided context from the Helios
            documentation. Be specific — use the actual class names, method
            names, and configuration properties from the docs. Use the GitHub
            tools when the user asks about a specific issue or release.

            If the documentation doesn't cover the question, say so clearly.
            Never invent API details that aren't in the provided context.""";

    public static void main(String[] args) {

        var app = CafeAI.create();

        // ── AI provider ────────────────────────────────────────
        app.connect(
                Ollama.at("http://localhost:11434").model("qwen2.5")
                        .onUnavailable(Fallback.use(OpenAI.gpt4oMini()))
        );

        // ── Phase 6: Session memory ────────────────────────────────
        // In-memory stores conversation history per session.
        // Sessions are keyed by X-Session-Id header.
        // In production: connect Redis for persistence across restarts.
        app.memory(MemoryStrategy.inMemory());

        // ── Phase 4: RAG pipeline ──────────────────────────────
        // VectorStore holds document embeddings in memory.
        // In production, swap VectorStore.inMemory() for PgVector.
        app.vectordb(VectorStore.inMemory());

        // EmbeddingModel converts text to vectors for similarity search.
        // local() uses a bundled ONNX model — no API key, no network call.
        app.embed(EmbeddingModel.local());

        // Retriever finds the top 3 most relevant chunks per question.
        // Raising this number gives more context; lowering it speeds things up.
        app.rag(Retriever.semantic(3));

        // ── The support agent ─────────────────────────────────────
        // RAG (Helios docs) and session memory are inherited from the
        // app-level app.rag(...) / app.memory(...); the agent adds the
        // GitHub tools and its own system prompt. Returns LangChain4j's
        // AiService proxy — invoked per request below.
        app.agent("support", SupportAssistant.class)
                .system(SYSTEM_PROMPT)
                .tool(new GitHubTools());

        // ── Phase 7: Guardrails ────────────────────────────────────
        // Guardrails are middleware — they run before every request
        // reaches the LLM. Order matters: check injection first,
        // then jailbreak, then topic scope.
        app.guard(GuardRail.promptInjection());

        app.guard(GuardRail.jailbreak());

        app.guard(GuardRail.topicBoundary()
                .allow("helios", "connection", "pool", "jdbc", "database",
                        "timeout", "configuration", "error", "exception",
                        "driver", "postgresql", "mysql", "spring", "upgrade",
                        "version", "release", "issue", "bug", "fix", "install",
                        "dependency", "gradle", "maven", "thread", "virtual",
                        "idle", "active", "leak", "keepalive", "lifetime",
                        "credential", "url", "username", "password"));

        // ── Phase 10: Security layer ───────────────────────────────
        // Security runs after guardrails but before body parsing.
        // Every blocked request produces a SecurityEvent with a unique
        // eventId for correlation with access logs and OTel traces.
        AiSecurity.onEvent(event -> {
            // In production: forward to SIEM, PagerDuty, audit database.
            // Here: structured log with event type and ID.
            String type = event.getClass().getSimpleName();
            System.out.println("[SECURITY] " + type
                    + " | path=" + event.requestPath()
                    + " | eventId=" + event.eventId());
        });

        app.filter(AiSecurity.promptInjectionDetector());

        // ── Observability ─────────────────────────────────────
        // Console strategy logs a trace per LLM call and per agent invocation.
        app.observe(ObserveStrategy.console());

        // ── Phase 4: Seed the knowledge base ───────────────────
        HeliosKnowledgeBase.seed(app);

        // ── Middleware ─────────────────────────────────────────
        app.filter(Middleware.requestLogger());
        app.filter(CafeAI.json());

        // ── Routes ─────────────────────────────────────────────
        app.get("/health", (req, res, next) ->
                res.json(Map.of(
                        "status", "ok",
                        "service", "support-desk",
                        "version", "1.0"
                )));

        app.post("/support", (req, res, next) -> {
            String message = req.body("message");

            if (message == null || message.isBlank()) {
                res.status(400).json(Map.of(
                        "error", "Field 'message' is required",
                        "hint", "POST {\"message\": \"your question here\"}"
                ));
                return;
            }

            var agent = app.agent("support", SupportAssistant.class,
                    req.header("X-Session-Id"));

            res.json(Map.of(
                    "question", message,
                    "answer", agent.answer(message)
            ));
        });

        // ── Phase 11: WebSocket streaming chat ────────────────────
        // Tracks active sessions so we could broadcast if needed
        Set<WsSession> activeSessions = ConcurrentHashMap.newKeySet();

        app.ws("/ws/support", new WsHandler() {

            @Override
            public void onOpen(WsSession session) {
                activeSessions.add(session);
                session.send("{\"type\":\"connected\"," +
                        "\"message\":\"Helios support ready. How can I help?\"}");
            }

            @Override
            public void onMessage(WsSession session, String message) {
                // Run on a virtual thread — don't block the WebSocket thread
                Thread.ofVirtual().start(() -> {
                    try {
                        // Full AI stack — RAG, memory, tools all active on the agent
                        var agent = app.agent("support", SupportAssistant.class,
                                session.id());

                        session.send("{\"type\":\"response\"," +
                                "\"answer\":" + jsonString(agent.answer(message)) + "}");

                    } catch (Exception e) {
                        session.send("{\"type\":\"error\"," +
                                "\"message\":" + jsonString(e.getMessage()) + "}");
                    }
                });
            }

            @Override
            public void onClose(WsSession session, int code, String reason) {
                activeSessions.remove(session);
            }

            @Override
            public void onError(WsSession session, Throwable error) {
                activeSessions.remove(session);
            }

            // Minimal JSON string escaping — avoids a Jackson dependency here
            private String jsonString(String s) {
                if (s == null) return "\"\"";
                return "\"" + s.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r") + "\"";
            }
        });

        // ── Start ───────────────────────────────────────────────
        app.listen(8080, () -> System.out.println("""
                
                ☕ support-desk running
                
                  GET  http://localhost:8080/health
                  POST http://localhost:8080/support
                """));
    }
}
