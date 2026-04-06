package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;

import java.nio.file.Path;
import java.util.Map;

/**
 * FfmMemoryExample — SSD-backed session memory via Java 21 FFM.
 *
 * <p>Demonstrates {@link MemoryStrategy#mapped()} — conversation history
 * stored in off-heap {@code MemorySegment}s, backed by files on disk.
 * No Redis. No network. No cloud cost. Sessions survive JVM restarts
 * and crashes out of the box.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Sessions survive JVM restarts (crash recovery is free)</li>
 *   <li>Session data lives on disk — visible as {@code .json} files</li>
 *   <li>No external dependencies — zero infrastructure overhead</li>
 *   <li>The same FFM API CafeAI uses for ONNX ML bindings also backs memory</li>
 * </ol>
 *
 * <h2>Prerequisites</h2>
 * None. Java 21+ only. No Docker required.
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
 *        -d '{"message": "Remember: the answer to everything is 42."}'
 *
 *   # Step 2 — check the session file exists on disk
 *   ls /tmp/cafeai/sessions/
 *   # demo-session.json  <-- session is on disk, not in heap
 *
 *   # Step 3 — stop the app (Ctrl+C), restart it
 *
 *   # Step 4 — the session is still there (file survived the restart)
 *   curl -X POST http://localhost:8080/chat \
 *        -H "Content-Type: application/json" \
 *        -H "X-Session-Id: demo-session" \
 *        -d '{"message": "What is the answer to everything?"}'
 *
 *   # Expected: "42."
 * </pre>
 *
 * <h2>Why FFM for memory is architecturally coherent</h2>
 * CafeAI uses the Java 21 FFM API in two places:
 * <ol>
 *   <li><b>Native ML bindings</b> — ONNX runtime, llama.cpp, without JNI ceremony</li>
 *   <li><b>Off-heap session memory</b> — {@code MemorySegment} for SSD-backed
 *       conversation context</li>
 * </ol>
 * The same API surface. The same developer skills. Two completely different
 * use cases. One coherent mental model.
 *
 * <h2>The tiered memory model</h2>
 * <pre>
 *   MemoryStrategy.inMemory()           Rung 1: JVM heap — dev/test, no persistence
 *   MemoryStrategy.mapped()             Rung 2: SSD-backed FFM — single-node production
 *   MemoryStrategy.mapped(Path)         Rung 2: same, custom storage directory
 *   MemoryStrategy.redis(config)        Rung 4: Redis — multi-node / cloud
 *   MemoryStrategy.hybrid()             Rung 5: Warm SSD + cold Redis
 * </pre>
 *
 * <p>For single-instance production, {@code mapped()} is the right choice.
 * It costs nothing extra, survives restarts, and keeps session data off the
 * JVM heap. Reach for Redis only when you genuinely need multiple instances
 * or cross-deployment session sharing.
 */
public class FfmMemoryExample {

    // Sessions stored here — visible on the filesystem
    private static final Path SESSION_DIR = Path.of(
        System.getProperty("java.io.tmpdir"), "cafeai", "sessions");

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI Provider ───────────────────────────────────────────────────────
        app.ai(OpenAI.gpt4oMini());

        // ── System Prompt ─────────────────────────────────────────────────────
        app.system("""
            You are a helpful assistant demonstrating CafeAI's FFM-backed
            session memory. You remember everything said in the conversation.
            Keep responses concise.
            """);

        // ── Memory — SSD-backed FFM (Rung 2) ─────────────────────────────────
        //
        // THIS IS THE ONLY LINE THAT DIFFERS FROM HelloCafeAI.
        //
        // Replace:
        //   app.memory(MemoryStrategy.inMemory());
        //
        // With:
        //   app.memory(MemoryStrategy.mapped());
        //
        // Sessions are stored as .json files in SESSION_DIR.
        // The OS page cache handles hot sessions automatically.
        // The in-memory index maps session IDs to file paths for O(1) lookup.
        // On startup, the index is rebuilt from existing files — crash recovery.
        //
        // Use mapped(Path) to specify a custom directory, e.g. for a volume mount:
        //   app.memory(MemoryStrategy.mapped(Path.of("/var/cafeai/sessions")));
        //
        app.memory(MemoryStrategy.mapped(SESSION_DIR));

        // ── Middleware ────────────────────────────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(Middleware.requestLogger());

        // ── Routes ────────────────────────────────────────────────────────────

        app.get("/health", (req, res, next) ->
            res.json(Map.of(
                "status",     "ok",
                "memory",     "ffm-mapped",
                "sessionDir", SESSION_DIR.toString())));

        // Session-aware chat — identical to HelloCafeAI except memory is FFM-mapped
        app.post("/chat", (req, res, next) -> {
            String message   = req.body("message");
            String sessionId = req.header("X-Session-Id");

            if (message == null || message.isBlank()) {
                res.status(400).json(Map.of("error", "message field required"));
                return;
            }

            var response = app.prompt(message)
                .session(sessionId)   // loads from disk, writes back to disk
                .call();

            // Show the session file path so the developer can inspect it
            String sessionFile = sessionId != null
                ? SESSION_DIR.resolve(sessionId + ".json").toString()
                : "none";

            res.json(Map.of(
                "response",    response.text(),
                "model",       response.modelId(),
                "sessionId",   sessionId != null ? sessionId : "none",
                "sessionFile", sessionFile
            ));
        });

        // ── Start ─────────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.printf("""
            ☕ FfmMemoryExample running on http://localhost:8080

               GET  /health   → health check (shows memory=ffm-mapped, sessionDir)
               POST /chat     → session-aware chat backed by FFM MemorySegment

            Memory: SSD-backed FFM at %s
            Sessions survive restarts. No Redis. No network. No cloud cost.

            Try it:

              # 1. Establish context
              curl -X POST http://localhost:8080/chat \\
                   -H "Content-Type: application/json" \\
                   -H "X-Session-Id: my-session" \\
                   -d '{"message": "Remember: my lucky number is 7."}'

              # 2. See the session file on disk
              ls %s
              cat %s/my-session.json

              # 3. Stop (Ctrl+C) and restart — the file survives

              # 4. Session still there
              curl -X POST http://localhost:8080/chat \\
                   -H "Content-Type: application/json" \\
                   -H "X-Session-Id: my-session" \\
                   -d '{"message": "What is my lucky number?"}'

            Press Ctrl+C to stop.
            %n""", SESSION_DIR, SESSION_DIR, SESSION_DIR));
    }
}
