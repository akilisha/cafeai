package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.Setting;
import io.cafeai.core.ai.ModelRouter;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;

import java.util.Map;

/**
 * HelloCafeAI — the canonical CafeAI bootstrap example.
 *
 * <p>Demonstrates the full AI-native request pipeline:
 * <ol>
 *   <li>Provider registration ({@code app.ai()})</li>
 *   <li>System prompt ({@code app.system()})</li>
 *   <li>Named templates ({@code app.template()})</li>
 *   <li>Session memory ({@code app.memory()})</li>
 *   <li>Guardrails as middleware ({@code app.guard()})</li>
 *   <li>LLM invocation ({@code app.prompt().call()})</li>
 *   <li>Model routing ({@code ModelRouter.smart()})</li>
 * </ol>
 *
 * <p>Run with OpenAI (requires OPENAI_API_KEY):
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :cafeai-examples:run
 * </pre>
 *
 * <p>Run with local Ollama (no key needed):
 * <pre>
 *   # Start Ollama: ollama serve
 *   # Pull a model: ollama pull llama3
 *   # Set USE_OLLAMA=true in the source, then:
 *   ./gradlew :cafeai-examples:run
 * </pre>
 */
public class HelloCafeAI {

    // Set to true to use local Ollama instead of OpenAI
    private static final boolean USE_OLLAMA = false;

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI Provider ───────────────────────────────────────────────────────
        if (USE_OLLAMA) {
            app.ai(Ollama.llama3());                   // local — no API key needed
        } else {
            app.ai(ModelRouter.smart()                 // cost-aware routing
                .simple(OpenAI.gpt4oMini())            // fast + cheap for simple queries
                .complex(OpenAI.gpt4o()));              // powerful for complex queries
        }

        // ── System Prompt — the AI's persona ─────────────────────────────────
        app.system("""
            You are a helpful, concise assistant built with CafeAI.
            You answer questions clearly and directly.
            If you don't know something, say so honestly.
            Keep responses under 3 sentences unless asked for more detail.
            """);

        // ── Named Prompt Templates ────────────────────────────────────────────
        app.template("classify",
            "Classify the following message into exactly one category: {{categories}}.\n" +
            "Respond with only the category name, nothing else.\n" +
            "Message: {{message}}");

        app.template("summarize",
            "Summarize the following in {{maxWords}} words or fewer:\n\n{{content}}");

        // ── Memory — conversation context per session ─────────────────────────
        app.memory(MemoryStrategy.inMemory());

        // ── Settings ──────────────────────────────────────────────────────────
        app.set(Setting.ENV, "development");
        app.disable(Setting.X_POWERED_BY);           // don't expose the stack in prod

        // ── Filters — cross-cutting, global ──────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(Middleware.requestLogger());
        app.filter(Middleware.cors());
        app.filter(GuardRail.pii());                 // scrub PII before LLM sees input
        app.filter(GuardRail.jailbreak());

        // ── Routes ────────────────────────────────────────────────────────────

        // Health check
        app.get("/health",
            (req, res, next) -> res.json(Map.of("status", "ok", "ai", "ready")));

        // Simple one-shot prompt
        app.post("/ask",
            (req, res, next) -> {
                String question = req.body("question");
                if (question == null || question.isBlank()) {
                    res.status(400).json(Map.of("error", "question field required"));
                    return;
                }
                var response = app.prompt(question).call();
                res.json(Map.of(
                    "answer",        response.text(),
                    "model",         response.modelId(),
                    "promptTokens",  response.promptTokens(),
                    "outputTokens",  response.outputTokens()
                ));
            });

        // Session-aware chat — maintains conversation history
        app.post("/chat",
            (req, res, next) -> {
                String message   = req.body("message");
                String sessionId = req.header("X-Session-Id");

                if (message == null || message.isBlank()) {
                    res.status(400).json(Map.of("error", "message field required"));
                    return;
                }

                var response = app.prompt(message)
                    .session(sessionId)   // loads history, stores exchange after
                    .call();

                res.json(Map.of(
                    "response",  response.text(),
                    "model",     response.modelId(),
                    "tokens",    response.totalTokens()
                ));
            });

        // Template-based classification
        app.post("/classify",
            (req, res, next) -> {
                String message = req.body("message");
                var response   = app.prompt("classify", Map.of(
                    "categories", "billing, shipping, returns, general",
                    "message",    message
                )).call();
                res.json(Map.of("category", response.text().trim().toLowerCase()));
            });

        // Template retrieval and manual rendering
        app.post("/summarize",
            (req, res, next) -> {
                String rendered = app.template("summarize")
                    .render(Map.of(
                        "maxWords", req.body("maxWords") != null ? req.body("maxWords") : "50",
                        "content",  req.body("content")
                    ));
                var response = app.prompt(rendered).call();
                res.json(Map.of("summary", response.text()));
            });

        // ── Start ──────────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.println("""
            ☕ CafeAI is brewing on http://localhost:8080

               GET  /health              → health check
               POST /ask                 → one-shot question/answer
               POST /chat                → session-aware conversation
               POST /classify            → template-based classification
               POST /summarize           → template-based summarization

            Provider: %s
            Memory:   in-memory (sessions survive until restart)

            Try it:
              curl -X POST http://localhost:8080/ask \\
                   -H "Content-Type: application/json" \\
                   -d '{"question":"What is CafeAI?"}'

              curl -X POST http://localhost:8080/chat \\
                   -H "Content-Type: application/json" \\
                   -H "X-Session-Id: session-1" \\
                   -d '{"message":"Hello! What can you help me with?"}'

            Press Ctrl+C to stop.
            """.formatted(USE_OLLAMA ? "Ollama (local)" : "OpenAI (cloud)")));
    }
}
