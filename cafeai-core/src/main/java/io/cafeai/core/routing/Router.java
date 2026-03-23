package io.cafeai.core.routing;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;

/**
 * Express-style HTTP router — the compositional routing primitive.
 *
 * <p>Every method mirrors its Express 4.x counterpart. Route methods accept
 * variadic {@link Middleware} handlers, forming an inline per-route pipeline
 * (ADR-009 §1):
 *
 * <pre>{@code
 *   // Single handler
 *   app.get("/users", (req, res, next) -> res.json(users.findAll()));
 *
 *   // Inline pipeline — authenticate runs first, then getUser
 *   app.get("/users/:id", authenticate, authorize("admin"),
 *       (req, res, next) -> res.json(users.find(req.params("id"))));
 * }</pre>
 *
 * <p>Each {@link Middleware} in the array calls {@code next.run()} to pass
 * control to the next middleware. The final handler terminates by not calling
 * {@code next.run()}. This is identical to Express handler arrays.
 *
 * <p>Path parameter syntax: {@code :name} (Express-style).
 * Translated to Helidon SE's {@code {name}} syntax internally (ADR-007).
 *
 * <p>Mirrors Express: {@code const router = express.Router()}
 */
public interface Router {

    // ── HTTP Verb Routes ──────────────────────────────────────────────────────

    /**
     * Registers GET route handlers.
     * Mirrors Express: {@code router.get(path, ...handlers)}
     */
    Router get(String path, Middleware... handlers);

    /**
     * Registers POST route handlers.
     * Mirrors Express: {@code router.post(path, ...handlers)}
     */
    Router post(String path, Middleware... handlers);

    /**
     * Registers PUT route handlers.
     * Mirrors Express: {@code router.put(path, ...handlers)}
     */
    Router put(String path, Middleware... handlers);

    /**
     * Registers PATCH route handlers.
     * Mirrors Express: {@code router.patch(path, ...handlers)}
     */
    Router patch(String path, Middleware... handlers);

    /**
     * Registers DELETE route handlers.
     * Mirrors Express: {@code router.delete(path, ...handlers)}
     */
    Router delete(String path, Middleware... handlers);

    /**
     * Registers HEAD route handlers.
     * Mirrors Express: {@code router.head(path, ...handlers)}
     */
    Router head(String path, Middleware... handlers);

    /**
     * Registers OPTIONS route handlers.
     * Mirrors Express: {@code router.options(path, ...handlers)}
     */
    Router options(String path, Middleware... handlers);

    /**
     * Registers handlers for ALL HTTP methods on the given path.
     * Mirrors Express: {@code router.all(path, ...handlers)}
     */
    Router all(String path, Middleware... handlers);

    // ── Middleware ────────────────────────────────────────────────────────────

    /**
     * Mounts inline middleware on the route pipeline.
     * For cross-cutting pre-processing, use {@code app.filter()} instead.
     * Mirrors Express: {@code router.use(middleware)}
     */
    Router use(Middleware... middlewares);

    /**
     * Mounts a sub-router at the given path prefix.
     * Internally maps to Helidon SE's {@code .register(path, service)} (ADR-007).
     * Mirrors Express: {@code app.use(path, router)}
     *
     * <pre>{@code
     *   var apiRouter = CafeAI.Router();
     *   apiRouter.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));
     *   app.use("/api/v1", apiRouter);
     * }</pre>
     */
    Router use(String path, Router subRouter);

    // ── Parameter Middleware ──────────────────────────────────────────────────

    /**
     * Registers a route parameter pre-processor.
     * Fires before any route handler when the named parameter is present.
     * Mirrors Express: {@code router.param(name, callback)}
     *
     * <pre>{@code
     *   app.param("userId", (req, res, next, id) -> {
     *       User user = userService.find(id);
     *       if (user == null) { res.sendStatus(404); return; }
     *       req.setAttribute("user", user);
     *       next.run();
     *   });
     * }</pre>
     */
    Router param(String name, ParamCallback callback);

    // ── Fluent Route Builder ──────────────────────────────────────────────────

    /**
     * Returns a fluent route builder for the given path.
     * Avoids repeating the path string when registering multiple HTTP methods.
     * Mirrors Express: {@code router.route(path)}
     *
     * <pre>{@code
     *   app.route("/users/:id")
     *      .get((req, res, next) -> res.json(users.find(req.params("id"))))
     *      .put((req, res, next) -> res.json(users.update(req.params("id"), req.body(UserDto.class))))
     *      .delete((req, res, next) -> { users.delete(req.params("id")); res.sendStatus(204); });
     * }</pre>
     */
    RouteBuilder route(String path);

    // ── Nested Types ──────────────────────────────────────────────────────────

    /**
     * Callback invoked by {@link #param(String, ParamCallback)} before route handlers.
     * Mirrors Express param callback: {@code (req, res, next, value)}
     */
    @FunctionalInterface
    interface ParamCallback {
        void handle(Request req, Response res, Next next, String value);
    }

    /**
     * Fluent route builder for a single path — returned by {@link #route(String)}.
     * All methods accept variadic {@link Middleware} handlers.
     */
    interface RouteBuilder {
        RouteBuilder get(Middleware... handlers);
        RouteBuilder post(Middleware... handlers);
        RouteBuilder put(Middleware... handlers);
        RouteBuilder patch(Middleware... handlers);
        RouteBuilder delete(Middleware... handlers);
        RouteBuilder head(Middleware... handlers);
        RouteBuilder options(Middleware... handlers);
        RouteBuilder all(Middleware... handlers);
    }
}
