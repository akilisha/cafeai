package io.cafeai.core.middleware;

import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

/**
 * The fundamental unit of composability in CafeAI.
 *
 * <p>Everything is a middleware. HTTP concerns, AI concerns, security,
 * observability, guardrails — all expressed as middleware in the pipeline.
 * This is the lesson Express taught us. CafeAI carries it forward.
 *
 * <pre>{@code
 *   // A middleware intercepts, transforms, or passes through
 *   Middleware logger = (req, res, next) -> {
 *       log.info("→ {} {}", req.method(), req.path());
 *       next.run();   // pass to the next middleware
 *       log.info("← {}", res.status());
 *   };
 *
 *   app.use(logger);
 * }</pre>
 *
 * <p>The AI pipeline is a middleware chain:
 * <pre>
 *   request
 *     → [auth]
 *     → [rate limiter]
 *     → [PII scrubber]       ← security middleware
 *     → [jailbreak detector] ← security middleware
 *     → [guardrails pre]     ← guardrail middleware
 *     → [token budget]       ← cost middleware
 *     → [semantic cache]     ← memory middleware
 *     → [RAG retrieval]      ← rag middleware
 *     → [LLM call]           ← ai middleware
 *     → [guardrails post]    ← guardrail middleware
 *     → [hallucination check]← guardrail middleware
 *     → [observability]      ← observe middleware
 *     → [memory write]       ← memory middleware
 *     → [streaming response] ← streaming middleware
 * </pre>
 */
@FunctionalInterface
public interface Middleware {

    /**
     * Executes this middleware.
     *
     * @param req  the incoming request
     * @param res  the outgoing response
     * @param next call next.run() to pass control to the next middleware
     */
    void handle(Request req, Response res, Next next);

    /**
     * Composes two middlewares — this runs first, then other.
     * Enables programmatic middleware chaining.
     */
    default Middleware then(Middleware other) {
        return (req, res, next) -> this.handle(req, res, () -> other.handle(req, res, next));
    }

    // ── Built-in Middleware Factory Methods ──────────────────────────────────

    /** CORS headers middleware. */
    static Middleware cors() {
        return BuiltInMiddleware.cors();
    }

    /** Request rate limiter middleware. */
    static Middleware rateLimit(int requestsPerMinute) {
        return BuiltInMiddleware.rateLimit(requestsPerMinute);
    }

    /** Structured request/response logger middleware. */
    static Middleware requestLogger() {
        return BuiltInMiddleware.requestLogger();
    }

    /** JSON body parsing middleware. Mirrors Express's express.json() */
    static Middleware json() {
        return BuiltInMiddleware.json();
    }

    /** Token budget enforcer — caps LLM spend per session. */
    static Middleware tokenBudget(int maxTokensPerSession) {
        return BuiltInMiddleware.tokenBudget(maxTokensPerSession);
    }

    // ── Next ─────────────────────────────────────────────────────────────────

    /** Represents the next middleware in the chain. */
    @FunctionalInterface
    interface Next {
        void run();
    }
}
