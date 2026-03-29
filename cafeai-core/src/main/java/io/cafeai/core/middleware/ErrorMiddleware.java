package io.cafeai.core.middleware;

import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

/**
 * Error-handling middleware -- the 4-argument form invoked when
 * {@link Next#fail(Throwable)} is called anywhere upstream.
 *
 * <p>Mirrors Express: {@code function(err, req, res, next)}
 *
 * <p>Register via {@code app.onError()}:
 * <pre>{@code
 *   app.onError((err, req, res, next) -> {
 *       if (err instanceof NotFoundException) {
 *           res.status(404).json(Map.of("error", err.getMessage()));
 *       } else if (err instanceof AuthException) {
 *           res.status(401).json(Map.of("error", "Unauthorized"));
 *       } else {
 *           log.error("Unhandled error: {}", err.getMessage(), err);
 *           res.status(500).json(Map.of("error", "Internal server error"));
 *       }
 *   });
 * }</pre>
 *
 * <p>Trigger from any middleware or handler:
 * <pre>{@code
 *   app.get("/users/:id", (req, res, next) -> {
 *       try {
 *           res.json(userService.find(req.params("id")));
 *       } catch (NotFoundException e) {
 *           next.fail(e);   // -> routed to error middleware
 *       }
 *   });
 * }</pre>
 *
 * <p>Multiple error handlers are chained in registration order.
 * Call {@code next.run()} to pass to the next error handler.
 * Not calling {@code next.run()} terminates the error chain.
 *
 * <p><strong>ADR-002 note:</strong> {@code ErrorMiddleware} is deliberately
 * a separate interface from {@link Middleware}. Express uses the same function
 * type and distinguishes by arity -- Java cannot. The separation is cleaner:
 * error handlers are explicit about their role and cannot be accidentally
 * registered as normal middleware.
 */
@FunctionalInterface
public interface ErrorMiddleware {

    /**
     * Handles an error that was routed via {@link Next#fail(Throwable)}.
     *
     * @param error the thrown error
     * @param req   the request that triggered the error
     * @param res   the response -- may already be partially committed
     * @param next  call {@code next.run()} to pass to the next error handler,
     *              or {@code next.fail(err)} to re-throw a different error
     */
    void handle(Throwable error, Request req, Response res, Next next);
}
