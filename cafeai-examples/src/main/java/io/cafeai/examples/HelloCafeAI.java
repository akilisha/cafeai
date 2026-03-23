package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.*;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.observability.ObserveStrategy;
import io.cafeai.core.rag.Retriever;
import io.cafeai.core.rag.Source;
import io.cafeai.core.tools.McpServer;

import java.util.Map;

/**
 * HelloCafeAI — The canonical CafeAI bootstrap example.
 *
 * <p>This is the "hello world" that demonstrates the full CafeAI API
 * in one readable, top-to-bottom declaration. Every line is intentional.
 * Every method name is self-explanatory.
 *
 * <p>Run it. Read it. Fork it. This is your starting point.
 *
 * <p>To run:
 * <pre>
 *   ./gradlew :cafeai-examples:run
 * </pre>
 */
public class HelloCafeAI {

    public static void main(String[] args) {

        var app = CafeAI.create();

        // ── Infrastructure ────────────────────────────────────────────────
        // Declare the AI provider. Swap models without changing anything else.
        app.ai(OpenAI.gpt4o());

        // Tiered memory — SSD-backed via Java FFM.
        // No Redis. No network overhead. No cloud tax.
        app.memory(MemoryStrategy.mapped());

        // Vector DB for RAG
        // app.vectordb(PgVector.connect(config));   // production
        // app.vectordb(VectorStore.inMemory());     // uncomment for prototype

        // Embedding model — local ONNX via FFM, no external API call
        // app.embed(EmbeddingModel.local());

        // Observability — every LLM call is a traced, measured event
        app.observe(ObserveStrategy.console()); // use .otel() in production

        // ── Knowledge ─────────────────────────────────────────────────────
        // app.ingest(Source.pdf("docs/handbook.pdf"));
        // app.ingest(Source.url("https://docs.example.com"));
        // app.rag(Retriever.semantic(5));

        // ── Safety ────────────────────────────────────────────────────────
        app.guard(GuardRail.pii());
        app.guard(GuardRail.jailbreak());
        app.guard(GuardRail.promptInjection());

        // ── Persona ───────────────────────────────────────────────────────
        app.system("""
            You are a helpful, empathetic customer service agent for Acme Corp.
            You are concise, accurate, and always escalate unresolved issues.
            You never discuss competitor products or share internal pricing.
            """);

        // ── Tools ─────────────────────────────────────────────────────────
        // app.tool(OrderLookupTool.create());
        // app.mcp(McpServer.github());

        // ── Global Middleware ─────────────────────────────────────────────
        app.use(Middleware.requestLogger());
        app.use(Middleware.json());
        app.use(Middleware.rateLimit(60));

        // ── Routes ────────────────────────────────────────────────────────

        // Health check — no AI involved, plain HTTP
        app.get("/health", (req, res) -> {
            res.json(Map.of("status", "ok", "service", "cafeai"));
        });

        // The chat endpoint — AI-powered, streamed
        app.post("/chat", (req, res) -> {
            var message = req.body("message");
            var sessionId = req.header("X-Session-Id");
            res.stream(app.prompt(message)); // token-streamed SSE response
        });

        // ── Start ──────────────────────────────────────────────────────────
        app.listen(8080, () ->
            System.out.println("""
                ☕ CafeAI is brewing on http://localhost:8080
                   POST /chat   → AI-powered chat endpoint
                   GET  /health → Health check
                """)
        );
    }
}
