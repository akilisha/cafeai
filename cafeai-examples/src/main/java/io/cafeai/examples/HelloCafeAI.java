package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;

import java.util.Map;

/**
 * HelloCafeAI — The canonical CafeAI bootstrap example.
 *
 * <p>The living proof that the framework works end to end.
 * Every phase of every roadmap must keep this running.
 *
 * <p>Demonstrates the ADR-009 API:
 * <ul>
 *   <li>{@code app.filter()} for cross-cutting pre-processing</li>
 *   <li>Variadic {@code Middleware} handlers on route methods</li>
 *   <li>Natural post-processing via code after {@code next.run()}</li>
 * </ul>
 *
 * <p>Run:
 * <pre>./gradlew :cafeai-examples:run</pre>
 *
 * <p>Test:
 * <pre>
 *   curl http://localhost:8080/health
 *   curl -X POST http://localhost:8080/echo \
 *        -H "Content-Type: application/json" \
 *        -d '{"message":"hello cafeai"}'
 *   curl http://localhost:8080/users/42
 * </pre>
 */
public class HelloCafeAI {

    public static void main(String[] args) {

        var app = CafeAI.create();

        // ── Memory and Safety ──────────────────────────────────────────────────
        app.memory(MemoryStrategy.inMemory());

        // ── Cross-Cutting Pre-Processing (filter scope) ────────────────────────
        // These run before any route handler, in their own execution frame.
        // Code after next.run() is post-processing — executes after response commits.
        app.filter(Middleware.requestLogger());
        app.filter(Middleware.cors());
        app.filter(CafeAI.json());        // parse JSON bodies before routes read them
        app.filter(GuardRail.pii());      // scrub PII before any handler sees input
        app.filter(GuardRail.jailbreak());

        // ── Timing filter — demonstrates natural post-processing ──────────────
        app.filter((req, res, next) -> {
            long start = System.nanoTime();
            next.run();                   // blocks on virtual thread until response committed
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > 500) {
                System.out.printf("SLOW REQUEST: %s %s took %dms%n",
                    req.method(), req.path(), ms);
            }
        });

        // ── Rate limiting scoped to /api/** ────────────────────────────────────
        app.filter("/api", Middleware.rateLimit(100));

        // ── Routes (variadic Middleware handlers) ──────────────────────────────

        // Health check — single handler, no middleware
        app.get("/health",
            (req, res, next) -> res.json(Map.of(
                "status",  "ok",
                "service", "cafeai",
                "version", "0.1.0-SNAPSHOT")));

        // Echo — demonstrates req/res round-trip with JSON body
        app.post("/echo",
            (req, res, next) -> res.json(Map.of(
                "echo",   req.body("message") != null ? req.body("message") : "(no message)",
                "method", req.method(),
                "path",   req.path())));

        // Path parameter — inline auth + handler pipeline
        // auth is a no-op stub here; in production it would check a token
        Middleware auth = (req, res, next) -> {
            // Real auth would inspect req.header("Authorization") and call next.fail() on reject
            next.run();
        };

        app.get("/users/:id",
            auth,   // inline per-route middleware — runs before the handler below
            (req, res, next) -> res.json(Map.of(
                "userId",  req.params("id"),
                "message", "User " + req.params("id") + " found")));

        // Fluent route builder — multiple methods on one path
        app.route("/items/:id")
           .get((req, res, next) ->
               res.json(Map.of("id", req.params("id"), "action", "get")))
           .put((req, res, next) ->
               res.json(Map.of("id", req.params("id"), "action", "update")))
           .delete((req, res, next) ->
               res.status(204).end());

        // Sub-router — demonstrates app.use(path, router)
        var apiRouter = CafeAI.Router();
        apiRouter.get("/hello",
            (req, res, next) -> res.json(Map.of("message", "Hello from /api/hello")));
        apiRouter.get("/status",
            (req, res, next) -> res.json(Map.of("api", "v1", "status", "healthy")));
        app.use("/api", apiRouter);

        // ── Start ──────────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.println("""
            ☕ CafeAI is brewing on http://localhost:8080

               GET  /health         → health check
               POST /echo           → echo JSON body
               GET  /users/:id      → path param + inline auth middleware
               GET  /items/:id      → fluent route builder (also PUT, DELETE)
               GET  /api/hello      → sub-router, rate-limited
               GET  /api/status     → sub-router endpoint

            Filters active: requestLogger, cors, json, pii, jailbreak, timing, rateLimit(/api)
            Press Ctrl+C to stop.
            """));
    }
}
