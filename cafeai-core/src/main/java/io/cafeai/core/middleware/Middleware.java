package io.cafeai.core.middleware;

import io.cafeai.core.internal.BuiltInMiddleware;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

/**
 * The fundamental unit of composability in CafeAI. (ADR-002, ADR-009)
 *
 * <p>Everything is a {@code Middleware}: HTTP concerns, AI concerns, security,
 * observability, guardrails, route handlers. No other handler type exists.
 * A terminating route handler is a {@code Middleware} that does not call
 * {@code next.run()}. An inline pipeline middleware does. No other distinction.
 *
 * <p>Mirrors Express: {@code function(req, res, next)}
 *
 * <p>Registering middleware:
 * <pre>{@code
 *   // Cross-cutting pre-processing -- runs before route dispatch (app.filter):
 *   app.filter(Middleware.requestLogger());
 *   app.filter(CafeAI.json());
 *   app.filter("/api", Middleware.auth());
 *
 *   // Per-route inline pipeline (variadic handlers):
 *   app.get("/users/:id", authenticate, authorize("admin"),
 *       (req, res, next) -> res.json(userService.find(req.params("id"))));
 *
 *   // Post-processing -- code after next.run() runs after the full downstream
 *   // chain completes (blocks on virtual thread -- no async needed):
 *   app.filter((req, res, next) -> {
 *       long start = System.nanoTime();
 *       next.run();
 *       log.info("{}ms", (System.nanoTime() - start) / 1_000_000);
 *   });
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {

    /**
     * A no-op middleware that immediately calls {@code next.run()}.
     * Used as a sentinel for empty handler arrays and sub-router placeholders.
     */
    Middleware NOOP = (req, res, next) -> next.run();

    /**
     * Executes this middleware.
     *
     * <p>Call {@code next.run()} to pass control to the next middleware in the chain.
     * Not calling {@code next.run()} terminates the chain -- no further middleware
     * or route handler will execute for this request.
     *
     * <p>Code written after {@code next.run()} executes after the entire downstream
     * chain completes (post-processing). This works naturally on virtual threads
     * because {@code next.run()} is a real blocking call.
     *
     * @param req  the incoming request
     * @param res  the outgoing response
     * @param next call {@code next.run()} to continue, {@code next.fail(err)} for errors
     */
    void handle(Request req, Response res, Next next);

    /**
     * Composes this middleware with {@code other} -- {@code this} runs first, then
     * {@code other} runs when {@code this} calls {@code next.run()}.
     *
     * <p>This is the primitive used by {@code CafeAIApp.compose()} to build the
     * per-route handler chain from variadic middleware arrays:
     * <pre>{@code
     *   // app.get("/x", mw1, mw2, mw3) composes as:
     *   Middleware chain = mw1.then(mw2.then(mw3));
     * }</pre>
     */
    default Middleware then(Middleware other) {
        return (req, res, next) ->
            this.handle(req, res, () -> {
                try {
                    other.handle(req, res, next);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    // -- Built-in Middleware Factory Methods -----------------------------------

    /**
     * JSON body parsing middleware. Parses {@code application/json} bodies into
     * {@code req.body()}. Register via {@code app.filter()} for global pre-processing.
     * Mirrors Express: {@code express.json()}
     */
    static Middleware json() {
        return BuiltInMiddleware.json();
    }

    /**
     * CORS headers middleware -- permissive, for development.
     */
    static Middleware cors() {
        return BuiltInMiddleware.cors();
    }

    /**
     * Structured request/response logging middleware.
     */
    static Middleware requestLogger() {
        return BuiltInMiddleware.requestLogger();
    }

    /**
     * Per-IP rate limiting middleware. Returns 429 on breach.
     *
     * @param requestsPerMinute maximum requests per IP per minute
     */
    static Middleware rateLimit(int requestsPerMinute) {
        return BuiltInMiddleware.rateLimit(requestsPerMinute);
    }

    /**
     * Per-session LLM token budget middleware. Returns 429 on exhaustion.
     *
     * @param maxTokensPerSession maximum tokens allowed per session
     */
    static Middleware tokenBudget(int maxTokensPerSession) {
        return BuiltInMiddleware.tokenBudget(maxTokensPerSession);
    }
}
