package io.cafeai.core.routing;

/**
 * Metadata about the matched route for the current request.
 * Mirrors Express {@code req.route}.
 *
 * <pre>{@code
 *   app.get("/users/:id", (req, res, next) -> {
 *       Route route = req.route();
 *       // route.path()   → "/users/:id"
 *       // route.method() → "GET"
 *   });
 * }</pre>
 */
public interface Route {

    /** The path pattern this route was registered with. Example: {@code "/users/:id"} */
    String path();

    /** The HTTP method this route handles. Example: {@code "GET"} */
    String method();
}
