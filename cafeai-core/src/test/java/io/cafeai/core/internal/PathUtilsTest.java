package io.cafeai.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive tests for Express → Helidon SE path parameter translation.
 *
 * <p>This is a load-bearing concern (ADR-007): every Express-style route
 * registered via the public API must produce the correct Helidon path.
 * A translation bug here silently breaks all parameterised routes.
 */
@DisplayName("PathUtils — Express to Helidon path translation")
class PathUtilsTest {

    // ── Basic translation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Single param: /users/:id → /users/{id}")
    void singleParam() {
        assertThat(PathUtils.toHelidonPath("/users/:id"))
            .isEqualTo("/users/{id}");
    }

    @Test
    @DisplayName("Two params: /blogs/:blogId/posts/:postId")
    void twoParams() {
        assertThat(PathUtils.toHelidonPath("/blogs/:blogId/posts/:postId"))
            .isEqualTo("/blogs/{blogId}/posts/{postId}");
    }

    @Test
    @DisplayName("Three params: /a/:x/b/:y/c/:z")
    void threeParams() {
        assertThat(PathUtils.toHelidonPath("/a/:x/b/:y/c/:z"))
            .isEqualTo("/a/{x}/b/{y}/c/{z}");
    }

    // ── No-op cases ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("No params: /health unchanged")
    void noParams_health() {
        assertThat(PathUtils.toHelidonPath("/health"))
            .isEqualTo("/health");
    }

    @Test
    @DisplayName("Root path / unchanged")
    void rootPath() {
        assertThat(PathUtils.toHelidonPath("/"))
            .isEqualTo("/");
    }

    @Test
    @DisplayName("Deep static path unchanged")
    void deepStaticPath() {
        assertThat(PathUtils.toHelidonPath("/api/v1/admin/settings"))
            .isEqualTo("/api/v1/admin/settings");
    }

    // ── Param name edge cases ─────────────────────────────────────────────────

    @Test
    @DisplayName("Underscore in param name: /items/:item_id")
    void underscoreInParamName() {
        assertThat(PathUtils.toHelidonPath("/items/:item_id"))
            .isEqualTo("/items/{item_id}");
    }

    @Test
    @DisplayName("CamelCase param name: /users/:userId/posts/:postId")
    void camelCaseParamNames() {
        assertThat(PathUtils.toHelidonPath("/users/:userId/posts/:postId"))
            .isEqualTo("/users/{userId}/posts/{postId}");
    }

    @Test
    @DisplayName("Single char param: /x/:a/y/:b")
    void singleCharParams() {
        assertThat(PathUtils.toHelidonPath("/x/:a/y/:b"))
            .isEqualTo("/x/{a}/y/{b}");
    }

    // ── Mixed paths ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mixed static and param segments: /api/v1/users/:id/profile")
    void mixedStaticAndParam() {
        assertThat(PathUtils.toHelidonPath("/api/v1/users/:id/profile"))
            .isEqualTo("/api/v1/users/{id}/profile");
    }

    @Test
    @DisplayName("Param at end: /search/:query")
    void paramAtEnd() {
        assertThat(PathUtils.toHelidonPath("/search/:query"))
            .isEqualTo("/search/{query}");
    }

    @Test
    @DisplayName("Param at start: /:version/health")
    void paramAtStart() {
        assertThat(PathUtils.toHelidonPath("/:version/health"))
            .isEqualTo("/{version}/health");
    }

    // ── Parameterised tests for common real-world patterns ────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("Common route patterns")
    @CsvSource({
        "/users/:id,                       /users/{id}",
        "/users/:id/posts,                 /users/{id}/posts",
        "/users/:userId/posts/:postId,     /users/{userId}/posts/{postId}",
        "/orgs/:orgId/repos/:repoId,       /orgs/{orgId}/repos/{repoId}",
        "/api/v1/orders/:orderId/items/:itemId, /api/v1/orders/{orderId}/items/{itemId}",
        "/,                                /",
        "/health,                          /health",
        "/api/v2/status,                   /api/v2/status"
    })
    void commonPatterns(String express, String helidon) {
        assertThat(PathUtils.toHelidonPath(express.trim()))
            .isEqualTo(helidon.trim());
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Translation is idempotent on static paths")
    void idempotent_staticPath() {
        String path = "/api/v1/health";
        assertThat(PathUtils.toHelidonPath(PathUtils.toHelidonPath(path)))
            .isEqualTo(PathUtils.toHelidonPath(path));
    }

    @Test
    @DisplayName("Helidon {param} syntax is not double-translated")
    void helidonSyntax_notDoubleTranslated() {
        // If someone accidentally passes a Helidon path, it should not be mangled
        // {id} contains no colon-prefix so it won't be touched
        assertThat(PathUtils.toHelidonPath("/users/{id}"))
            .isEqualTo("/users/{id}");
    }
}
