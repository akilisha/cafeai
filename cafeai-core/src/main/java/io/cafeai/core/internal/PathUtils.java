package io.cafeai.core.internal;

/**
 * Path translation utilities for CafeAI routing.
 *
 * <p>Translates Express-style path syntax to Helidon SE syntax
 * so the public API speaks Express while Helidon SE handles routing
 * natively (ADR-007).
 *
 * <p>Package-accessible for unit testing via the test package
 * {@code io.cafeai.core.internal}.
 */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Translates Express {@code :paramName} path syntax to
     * Helidon SE {@code {paramName}} syntax.
     *
     * <p>Examples:
     * <pre>
     *   "/users/:id"                    -> "/users/{id}"
     *   "/blogs/:blogId/posts/:postId"  -> "/blogs/{blogId}/posts/{postId}"
     *   "/health"                       -> "/health"   (unchanged)
     *   "/"                             -> "/"         (unchanged)
     *   "/items/:item_id"               -> "/items/{item_id}"
     * </pre>
     *
     * @param expressPath an Express-style route path
     * @return the equivalent Helidon SE path
     */
    public static String toHelidonPath(String expressPath) {
        return expressPath.replaceAll(":([a-zA-Z][a-zA-Z0-9_]*)", "{$1}");
    }
}
