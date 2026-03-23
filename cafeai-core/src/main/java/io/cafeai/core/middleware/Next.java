package io.cafeai.core.middleware;

/**
 * Represents the next step in the middleware chain.
 *
 * <p>Call {@link #run()} to pass control to the next middleware.
 * Not calling {@link #run()} short-circuits the chain — no further
 * middleware or route handler will execute for this request.
 *
 * <p>Call {@link #fail(Throwable)} to route the request to the
 * error-handling middleware chain instead.
 *
 * <p>Mirrors Express: {@code next()} and {@code next(err)}
 */
@FunctionalInterface
public interface Next {

    /**
     * Passes control to the next middleware in the chain.
     * Mirrors Express: {@code next()}
     */
    void run();

    /**
     * Routes the request to the error-handling middleware chain.
     * Mirrors Express: {@code next(err)}
     *
     * <p>Default implementation wraps the throwable and calls {@link #run()}.
     * CafeAI's pipeline executor provides a real implementation that
     * routes to registered error middleware.
     *
     * @param error the error to propagate
     */
    default void fail(Throwable error) {
        throw new RuntimeException(error);
    }
}
