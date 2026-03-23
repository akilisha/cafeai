package io.cafeai.core.routing;

import io.cafeai.core.middleware.Middleware;

import java.util.function.BiConsumer;

/**
 * Express-style HTTP routing interface.
 *
 * <p>Every method mirrors its Express.js counterpart exactly.
 * Java developers with Express experience need zero ramp-up time.
 *
 * <pre>{@code
 *   // Express (JS)          →   CafeAI (Java)
 *   app.get('/path', fn)     →   app.get("/path", (req, res) -> { })
 *   app.post('/path', fn)    →   app.post("/path", (req, res) -> { })
 *   app.use(middleware)      →   app.use(middleware)
 *   app.use('/path', mw)     →   app.use("/path", middleware)
 * }</pre>
 */
public interface Router {

    // ── HTTP Verbs ───────────────────────────────────────────────────────────

    /** Registers a GET handler. Mirrors: {@code app.get(path, handler)} */
    Router get(String path, BiConsumer<Request, Response> handler);

    /** Registers a POST handler. Mirrors: {@code app.post(path, handler)} */
    Router post(String path, BiConsumer<Request, Response> handler);

    /** Registers a PUT handler. Mirrors: {@code app.put(path, handler)} */
    Router put(String path, BiConsumer<Request, Response> handler);

    /** Registers a PATCH handler. Mirrors: {@code app.patch(path, handler)} */
    Router patch(String path, BiConsumer<Request, Response> handler);

    /** Registers a DELETE handler. Mirrors: {@code app.delete(path, handler)} */
    Router delete(String path, BiConsumer<Request, Response> handler);

    // ── Middleware ───────────────────────────────────────────────────────────

    /**
     * Mounts middleware globally — applies to every request.
     * Mirrors: {@code app.use(middleware)}
     *
     * <pre>{@code
     *   app.use(Middleware.cors());
     *   app.use(Middleware.rateLimit(100));
     *   app.use(Middleware.requestLogger());
     * }</pre>
     */
    Router use(Middleware middleware);

    /**
     * Mounts middleware scoped to a path prefix.
     * Mirrors: {@code app.use('/api', middleware)}
     */
    Router use(String path, Middleware middleware);

    /**
     * Mounts a sub-router at a path prefix.
     * Enables modular route organization.
     * Mirrors: {@code app.use('/api', router)}
     *
     * <pre>{@code
     *   var apiRouter = Router.create();
     *   apiRouter.get("/health", (req, res) -> res.json(Map.of("status", "ok")));
     *   app.use("/api/v1", apiRouter);
     * }</pre>
     */
    Router use(String path, Router router);

    // ── Router Factory ───────────────────────────────────────────────────────

    /** Creates a standalone router for modular route grouping. */
    static Router create() {
        return new SubRouter();
    }
}
