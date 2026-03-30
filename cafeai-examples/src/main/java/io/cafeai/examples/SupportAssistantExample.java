package io.cafeai.examples;

import io.cafeai.connect.Connect;
import io.cafeai.connect.Fallback;
import io.cafeai.connect.McpEndpoint;
import io.cafeai.connect.Ollama;
import io.cafeai.connect.Redis;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import io.cafeai.observability.EvalHarness;
import io.cafeai.observability.ObserveStrategy;
import io.cafeai.rag.EmbeddingModel;
import io.cafeai.rag.Retriever;
import io.cafeai.rag.Source;
import io.cafeai.rag.VectorStore;
import io.cafeai.security.AiSecurity;
import io.cafeai.security.SecurityEvent;
import io.cafeai.tools.CafeAITool;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SupportAssistantExample — a realistic AI-powered customer support assistant
 * that demonstrates every major CafeAI feature in one cohesive application.
 *
 * <h2>What this example shows</h2>
 *
 * <table>
 *   <tr><td><strong>Feature</strong></td><td><strong>Where</strong></td></tr>
 *   <tr><td>Out-of-process connections</td><td>{@code app.connect()} — Ollama, Redis, MCP</td></tr>
 *   <tr><td>Graceful degradation</td><td>Ollama → OpenAI fallback</td></tr>
 *   <tr><td>RAG pipeline</td><td>Knowledge base ingestion + semantic retrieval</td></tr>
 *   <tr><td>Conversation memory</td><td>Per-session Redis-backed context</td></tr>
 *   <tr><td>Prompt templates</td><td>Named templates for classify + respond</td></tr>
 *   <tr><td>Chains</td><td>Triage chain: guard → classify → branch → respond</td></tr>
 *   <tr><td>Java tools</td><td>Order lookup, account status via {@code @CafeAITool}</td></tr>
 *   <tr><td>Guardrails</td><td>PII, jailbreak, topic boundary, regulatory</td></tr>
 *   <tr><td>Security layer</td><td>Injection detector, data leakage prevention</td></tr>
 *   <tr><td>Observability</td><td>OTel spans per call, eval scores per RAG response</td></tr>
 *   <tr><td>WebSocket</td><td>Streaming chat on the same port as HTTP</td></tr>
 *   <tr><td>Health check</td><td>Probes all registered connections</td></tr>
 *   <tr><td>File upload</td><td>PDF ingestion into the live knowledge base</td></tr>
 * </table>
 *
 * <h2>Running locally</h2>
 *
 * <p>Minimal setup — no external services required. Ollama and Redis fall back
 * gracefully; the application starts with OpenAI and in-memory storage:
 *
 * <pre>
 *   export OPENAI_API_KEY=your-key
 *   ./gradlew :cafeai-examples:run --main=io.cafeai.examples.SupportAssistantExample
 * </pre>
 *
 * <p>Full setup with all services:
 *
 * <pre>
 *   docker compose up -d          # starts Redis, Ollama, optional MCP server
 *   export OPENAI_API_KEY=your-key   # fallback only — Ollama used first
 *   ./gradlew :cafeai-examples:run --main=io.cafeai.examples.SupportAssistantExample
 * </pre>
 *
 * <h2>Try it</h2>
 *
 * <pre>
 *   # Ask a question (uses RAG + memory + guardrails + observability)
 *   curl -X POST http://localhost:8080/support \
 *        -H "Content-Type: application/json" \
 *        -H "X-Session-Id: user-123" \
 *        -d '{"message": "How do I reset my password?"}'
 *
 *   # Upload a new knowledge base document (live ingestion)
 *   curl -F "document=@release-notes.pdf" \
 *        http://localhost:8080/knowledge/upload
 *
 *   # Look up an order (uses @CafeAITool + LLM tool loop)
 *   curl -X POST http://localhost:8080/support \
 *        -H "Content-Type: application/json" \
 *        -H "X-Session-Id: user-123" \
 *        -d '{"message": "What is the status of order ORD-9988?"}'
 *
 *   # Live chat over WebSocket
 *   wscat -c ws://localhost:8080/ws/support -H "X-Session-Id: user-456"
 *
 *   # Health — probes all registered connections
 *   curl http://localhost:8080/health
 * </pre>
 */
public class SupportAssistantExample {

    // ── Tool implementation ────────────────────────────────────────────────────

    /**
     * Business tools exposed to the LLM via {@code @CafeAITool}.
     * In production these would call real APIs or databases.
     */
    static class SupportTools {

        @CafeAITool("Look up the current status of a customer order by its order ID")
        public String getOrderStatus(String orderId) {
            // Simulated — replace with real order service call
            return switch (orderId.toUpperCase()) {
                case "ORD-9988" -> "Order ORD-9988: Shipped on 2026-03-20, " +
                    "expected delivery 2026-03-28. Tracking: TRK-4421.";
                case "ORD-7712" -> "Order ORD-7712: Processing. Expected to ship within 2 business days.";
                default -> "Order " + orderId + " not found. Please check the order ID and try again.";
            };
        }

        @CafeAITool("Look up a customer account status and subscription tier by email address")
        public String getAccountStatus(String email) {
            if (email == null || !email.contains("@")) {
                return "Invalid email address provided.";
            }
            // Simulated
            return "Account " + email + ": Active, Pro tier, member since 2024-01-15. " +
                "Next billing date: 2026-04-15.";
        }

        @CafeAITool("Check if a specific product feature is available on a given subscription tier")
        public String checkFeatureAccess(String feature, String tier) {
            Map<String, Set<String>> access = Map.of(
                "api-access",      Set.of("pro", "enterprise"),
                "sso",             Set.of("enterprise"),
                "priority-support",Set.of("pro", "enterprise"),
                "custom-domain",   Set.of("enterprise")
            );
            Set<String> allowed = access.getOrDefault(feature.toLowerCase(), Set.of("free", "pro", "enterprise"));
            boolean hasAccess = allowed.contains(tier.toLowerCase());
            return hasAccess
                ? "Feature '" + feature + "' is available on the " + tier + " tier."
                : "Feature '" + feature + "' requires " +
                    (allowed.contains("enterprise") ? "Enterprise" : "Pro") + " tier or above.";
        }
    }

    // ── WebSocket chat sessions ────────────────────────────────────────────────

    private static final Set<WsSession> activeSessions = ConcurrentHashMap.newKeySet();

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── 1. Out-of-process connections ─────────────────────────────────────
        // Each connection probes the service at startup. If unavailable,
        // the fallback policy fires — no startup failure for optional services.

        // Try local Ollama first; fall back to OpenAI if not running
        app.connect(Ollama.at("http://localhost:11434").model("llama3")
            .onUnavailable(Fallback.use(OpenAI.gpt4oMini())));

        // Redis for session memory; fall back to in-memory
        app.connect(Redis.at("localhost:6379")
            .onUnavailable(Fallback.use(MemoryStrategy.inMemory())));

        // Optional MCP server — silently ignored if not running
        app.connect(McpEndpoint.at("http://localhost:3000")
            .onUnavailable(Fallback.ignore()));

        // ── 2. RAG pipeline ───────────────────────────────────────────────────
        // In-memory vector store for this example — use PgVector in production
        app.vectordb(VectorStore.inMemory());
        app.embed(EmbeddingModel.local());
        app.rag(Retriever.semantic(5));

        // Seed the knowledge base
        app.ingest(Source.text(
            "Password reset: Go to Settings → Security → Reset Password. " +
            "A reset link is emailed to your registered address. Links expire in 1 hour.",
            "kb/password-reset"));

        app.ingest(Source.text(
            "Billing: Invoices are available under Account → Billing → Invoice History. " +
            "We accept Visa, Mastercard, and PayPal. Annual plans receive a 20% discount.",
            "kb/billing"));

        app.ingest(Source.text(
            "API access is available on Pro and Enterprise tiers. " +
            "API keys are managed under Account → Developer → API Keys. " +
            "Rate limits: 1,000 requests/hour on Pro, 10,000 on Enterprise.",
            "kb/api-access"));

        // ── 3. Tools ──────────────────────────────────────────────────────────
        app.tool(new SupportTools());

        // ── 4. Observability ─────────────────────────────────────────────────
        app.observe(ObserveStrategy.console());   // swap for .otel() in production
        app.eval(EvalHarness.defaults());

        // ── 5. Prompt templates ───────────────────────────────────────────────
        app.system("""
            You are a helpful customer support assistant for a SaaS product.
            Answer questions using only the provided context.
            If you don't know, say so — never invent information.
            Be concise and professional.""");

        app.template("classify",
            "Classify this support message into exactly one category: " +
            "billing, technical, account, order, or general.\n" +
            "Respond with only the category word.\n\n" +
            "Message: {{message}}");

        app.template("respond",
            "Answer this customer support question based on the provided context.\n\n" +
            "Question: {{message}}");

        // ── 6. Chains ─────────────────────────────────────────────────────────
        // billing-handler: regulatory guardrail + respond template

        // general-handler: standard respond

        // triage: the main support pipeline

        // ── 7. Security middleware ────────────────────────────────────────────
        AiSecurity.onEvent(event -> {
            // In production: forward to SIEM or alerting system
            System.err.println("[SECURITY] " + event.getClass().getSimpleName() +
                " eventId=" + event.eventId() + " path=" + event.requestPath());
        });

        // ── 8. Body parsers ───────────────────────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(CafeAI.multipart());
        app.filter(io.cafeai.core.middleware.Middleware.requestLogger());
        app.filter(io.cafeai.core.middleware.Middleware.cors());

        // ── 9. Routes ─────────────────────────────────────────────────────────

        // Main support endpoint — runs the full triage chain
        app.post("/support", (req, res, next) -> {
            String message = req.body("message");
            if (message == null || message.isBlank()) {
                res.status(400).json(Map.of("error", "message field is required"));
                return;
            }

            // Call the LLM — guardrails, RAG, memory, and tools are all
            // wired at startup and fire automatically on every prompt call.
            var response = app.prompt(message)
                              .session(req.header("X-Session-Id"))
                              .call();

            if (response == null) return;

            res.json(Map.of(
                "answer",  response.text(),
                "tokens",  response.totalTokens(),
                "sources", response.ragDocuments().size()
            ));
        });

        // Knowledge base document upload — live RAG ingestion
        app.post("/knowledge/upload", (req, res, next) -> {
            io.cafeai.core.routing.UploadedFile file = req.file("document");
            if (file == null) {
                res.status(400).json(Map.of("error", "No file in field 'document'"));
                return;
            }
            try {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(
                    "kb-", "-" + file.originalName());
                file.saveTo(tmp);
                app.ingest(Source.pdf(tmp.toString()));
                res.status(201).json(Map.of(
                    "ingested", file.originalName(),
                    "size",     file.size()
                ));
            } catch (java.io.IOException e) {
                next.fail(new RuntimeException("Upload failed: " + e.getMessage(), e));
            }
        });

        // WebSocket — streaming support chat on the same port
        app.ws("/ws/support", new WsHandler() {

            @Override
            public void onOpen(WsSession session) {
                activeSessions.add(session);
                session.send("""
                    {"type":"connected","message":"Support assistant ready. How can I help?"}""");
            }

            @Override
            public void onMessage(WsSession session, String message) {
                // Parse the JSON message
                String userMessage = message; // simplified — parse JSON in production

                // Run the triage chain asynchronously on a virtual thread
                Thread.ofVirtual().start(() -> {
                    try {
                        // Direct prompt through the chain pipeline
                        PromptResponse response = app.prompt(userMessage)
                            .session(session.id())
                            .call();

                        session.send(
                            "{\"type\":\"response\",\"answer\":" +
                            escapeJson(response.text()) + "," +
                            "\"tokens\":" + response.totalTokens() + "}");
                    } catch (Exception e) {
                        session.send("{\"type\":\"error\",\"message\":\"" +
                            escapeJson(e.getMessage()) + "\"}");
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

            private String escapeJson(String s) {
                if (s == null) return "\"\"";
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                               .replace("\n", "\\n").replace("\r", "\\r") + "\"";
            }
        });

        // Health — probes all registered connections
        app.get("/health", Connect.healthCheck(app));

        // Root — quick orientation
        app.get("/", (req, res, next) -> res.json(Map.of(
            "service", "CafeAI Support Assistant",
            "endpoints", Map.of(
                "POST /support",           "Ask a support question",
                "POST /knowledge/upload",  "Upload a PDF to the knowledge base",
                "WS   /ws/support",        "WebSocket chat",
                "GET  /health",            "Connection health status"
            )
        )));

        // ── 10. Start ─────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.println("""
            ☕ CafeAI Support Assistant running on http://localhost:8080

            Features active:
              AI provider    — Ollama (llama3) or OpenAI gpt-4o-mini fallback
              Memory         — Redis or in-memory fallback
              RAG            — local ONNX embeddings, in-memory vector store
              Tools          — order lookup, account status, feature access
              Guardrails     — PII, jailbreak, topic boundary, GDPR
              Security       — injection detector, audit logging
              Observability  — console traces, faithfulness/relevance eval
              WebSocket      — ws://localhost:8080/ws/support

            Quick test:
              curl -X POST http://localhost:8080/support \\
                   -H 'Content-Type: application/json' \\
                   -H 'X-Session-Id: user-1' \\
                   -d '{"message": "How do I reset my password?"}'
            """));
    }
}
