package io.cafeai.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view name is joined onto the views directory. A name that climbs out of it, or is absolute, must be
 * refused: otherwise {@code res.render(name)} with a name that came from a request reads any file the
 * process can.
 */
@DisplayName("view path resolution")
class ViewPathTest {

    @TempDir Path root;

    private Path views;
    private CafeAI app;

    @BeforeEach
    void setUp() throws IOException {
        views = Files.createDirectories(root.resolve("views"));
        Files.writeString(views.resolve("page.html"), "page {{who}}");
        Files.createDirectories(views.resolve("sub"));
        Files.writeString(views.resolve("sub").resolve("nested.html"), "nested {{who}}");
        Files.writeString(root.resolve("secret.html"), "TOP SECRET {{who}}");

        app = CafeAI.create();
        app.set(Setting.VIEWS, views.toString());
        app.set(Setting.VIEW_ENGINE, "html");
        app.engine("html", ResponseFormatter.template());
    }

    /** Renders synchronously; returns the error, or the html when there was none. */
    private Object render(String view) {
        var result = new AtomicReference<Object>();
        app.render(view, Map.of("who", "Ada"), (err, html) -> result.set(err != null ? err : html));
        return result.get();
    }

    @Test @DisplayName("a view in the views directory renders")
    void inside() {
        assertThat(render("page")).isEqualTo("page Ada");
        assertThat(render("page.html")).isEqualTo("page Ada");
    }

    @Test @DisplayName("a view in a subdirectory renders, and a path that goes down and back up but stays inside is fine")
    void nested() {
        assertThat(render("sub/nested.html")).isEqualTo("nested Ada");
        assertThat(render("sub/../page.html")).isEqualTo("page Ada");
    }

    @Test @DisplayName("a view that climbs out of the views directory is refused, and the file is not read")
    void traversal() {
        Object result = render("../secret.html");

        assertThat(result).isInstanceOf(ResponseFormatter.RenderException.class);
        assertThat(((Throwable) result).getMessage()).contains("outside the views directory");
        assertThat(result.toString()).doesNotContain("TOP SECRET");
    }

    @Test @DisplayName("climbing out through a subdirectory is refused too")
    void traversalViaSubdirectory() {
        assertThat(render("sub/../../secret.html")).isInstanceOf(ResponseFormatter.RenderException.class);
    }

    @Test @DisplayName("an absolute view name is refused")
    void absolute() {
        Object result = render(root.resolve("secret.html").toString());

        assertThat(result).isInstanceOf(ResponseFormatter.RenderException.class);
        assertThat(result.toString()).doesNotContain("TOP SECRET");
    }
}
