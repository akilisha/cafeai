package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.middleware.Middleware;

import java.time.Duration;
import java.util.Map;

/**
 * RedisMemoryExample — distributed session memory via Redis.
 *
 * <p>Demonstrates {@link MemoryStrategy#redis(RedisConfig)} as a drop-in
 * replacement for {@link MemoryStrategy#inMemory()}. The only code change
 * is one line. The rest of the application is identical.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Sessions persist across application restarts</li>
 *   <li>Multiple application instances share the same sessions</li>
 *   <li>Session TTL is enforced at the Redis level</li>
 * </ol>
 *
 * <h2>Prerequisites</h2>
 * Start Redis before running:
 * <pre>
 *   docker run -d -p 6379:6379 redis:7-alpine
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :cafeai-examples:run
 * </pre>
 *
 * <h2>Proving session persistence</h2>
 * <pre>
 *   # Step 1 — establish context
 *   curl -X POST http://localhost:8080/chat \
 *        -H "Content-Type: application/json" \
 *        -H "X-Session-Id: demo-session" \
 *        -d '{"message": "My name is Alex and I am learning CafeAI."}'
 *
 *   # Step 2 — stop the app (Ctrl+C), restart it
 *
 *   # Step 3 — ask a follow-up. With inMemory() this fails.
 *   #           With redis() the session survives the restart.
 *   curl -X POST http://localhost:8080/chat \
 *        -H "Content-Type: application/json" \
 *        -H "X-Session-Id: demo-session" \
 *        -d '{"message": "What is my name?"}'
 *
 *   # Expected with redis(): "Your name is Alex."
 *   # Expected with inMemory(): "I don't have any previous context..."
 * </pre>
 *
 * <h2>Inspecting sessions directly</h2>
 * <pre>
 *   redis-cli keys "cafeai:session:*"
 *   redis-cli get "cafeai:session:demo-session"
 * </pre>
 *
 * <h2>The tiered memory model</h2>
 * <pre>
 *   MemoryStrategy.inMemory()           Rung 1: JVM heap, dev/test only
 *   MemoryStrategy.mapped()             Rung 2: SSD-backed FFM, single-node production
 *   MemoryStrategy.redis(config)        Rung 4: Redis, multi-node / cloud deployments
 *   MemoryStrategy.hybrid()             Rung 5: Warm SSD + cold Redis, best of both
 * </pre>
 *
 * <p>Choose the lowest rung that meets your requirements. Most single-instance
 * production applications do not need Redis — use {@code mapped()} instead.
 * Redis is the escape valve for multi-instance deployments.
 */
public class RedisMemoryExample {

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI Provider ───────────────────────────────────────────────────────
        app.ai(OpenAI.gpt4oMini());

        // ── System Prompt ─────────────────────────────────────────────────────
        app.system("""
            You are a helpful assistant demonstrating CafeAI's Redis-backed
            session memory. You remember everything said in the conversation.
            Keep responses concise.
            """);

        // ── Memory — Redis (Rung 4) ───────────────────────────────────────────
        //
        // THIS IS THE ONLY LINE THAT DIFFERS FROM HelloCafeAI.
        //
        // Replace:
        //   app.memory(MemoryStrategy.inMemory());
        //
        // With:
        //   app.memory(MemoryStrategy.redis(RedisConfig.of("localhost", 6379)));
        //
        // Everything else — the routes, the prompts, the session threading —
        // is identical. The framework handles the rest.
        //
        // Sessions are stored as JSON strings in Redis under the key:
        //   cafeai:session:<sessionId>
        // with a TTL that refreshes on every access.
        //
        app.memory(MemoryStrategy.redis(
            RedisConfig.builder()
                .host("localhost")
                .port(6379)
                .sessionTtl(Duration.ofHours(24))
                .build()));

        // ── Middleware ────────────────────────────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(Middleware.requestLogger());

        // ── Routes ────────────────────────────────────────────────────────────

        app.get("/health", (req, res, next) ->
            res.json(Map.of("status", "ok", "memory", "redis")));

        // Session-aware chat — identical to HelloCafeAI except memory is Redis
        app.post("/chat", (req, res, next) -> {
            String message   = req.body("message");
            String sessionId = req.header("X-Session-Id");

            if (message == null || message.isBlank()) {
                res.status(400).json(Map.of("error", "message field required"));
                return;
            }

            var response = app.prompt(message)
                .session(sessionId)   // loads from Redis, writes back to Redis
                .call();

            res.json(Map.of(
                "response",  response.text(),
                "model",     response.modelId(),
                "sessionId", sessionId != null ? sessionId : "none"
            ));
        });

        // ── Start ─────────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.println("""
            ☕ RedisMemoryExample running on http://localhost:8080

               GET  /health   → health check (shows memory=redis)
               POST /chat     → session-aware chat backed by Redis

            Memory: Redis at localhost:6379
            Sessions persist across restarts. Try:

              # 1. Send a message
              curl -X POST http://localhost:8080/chat \\
                   -H "Content-Type: application/json" \\
                   -H "X-Session-Id: my-session" \\
                   -d '{"message": "My favourite language is Java."}'

              # 2. Stop the app (Ctrl+C) and restart it

              # 3. Ask a follow-up — session is still there
              curl -X POST http://localhost:8080/chat \\
                   -H "Content-Type: application/json" \\
                   -H "X-Session-Id: my-session" \\
                   -d '{"message": "What is my favourite language?"}'

            Inspect sessions:
              redis-cli keys "cafeai:session:*"
              redis-cli get "cafeai:session:my-session"

            Press Ctrl+C to stop.
            """));
    }
}
