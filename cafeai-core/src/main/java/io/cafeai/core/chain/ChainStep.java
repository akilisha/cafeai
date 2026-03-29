package io.cafeai.core.chain;

import io.cafeai.core.middleware.Middleware;

/**
 * A single step in a {@link Chain}.
 *
 * <p>{@code ChainStep} is a semantic alias for {@link Middleware}. Every
 * middleware can be used as a chain step, and every chain step is a middleware.
 * The distinction is intent: a {@code ChainStep} communicates that this
 * middleware is part of an ordered AI processing pipeline, not just a
 * generic request handler.
 *
 * <p>Use {@link Steps} to create built-in steps, or implement this
 * interface directly for custom steps:
 *
 * <pre>{@code
 *   ChainStep myStep = (req, res, next) -> {
 *       req.setAttribute("processed", true);
 *       next.run();
 *   };
 *
 *   app.chain("pipeline", myStep, Steps.prompt("classify"));
 * }</pre>
 */
@FunctionalInterface
public interface ChainStep extends Middleware {
    // Marker interface -- inherits handle(Request, Response, Next) from Middleware.
    // The functional interface allows lambda ChainSteps without explicit casting.
}
