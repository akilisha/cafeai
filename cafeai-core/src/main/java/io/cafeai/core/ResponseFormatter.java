package io.cafeai.core;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;

/**
 * A response formatter — CafeAI's abstraction over template engines and
 * structured AI output formatters.
 *
 * <p>Register formatters via {@code app.engine(ext, formatter)}.
 * Use them via {@code res.render(view, locals)}.
 *
 * <p>A formatter takes a template name, a locals map, and produces a String.
 * The String is sent directly as the response body.
 *
 * <pre>{@code
 *   // Register engines
 *   app.engine("html",     ResponseFormatter.mustache());
 *   app.engine("md",       ResponseFormatter.markdown());
 *   app.engine("json",     ResponseFormatter.jsonSchema(schema));
 *
 *   // Set defaults
 *   app.set(Setting.VIEWS,       "templates");
 *   app.set(Setting.VIEW_ENGINE, "html");
 *
 *   // Render in a route handler
 *   app.get("/welcome", (req, res, next) ->
 *       res.render("welcome", Map.of("name", "Ada")));
 * }</pre>
 */
@FunctionalInterface
public interface ResponseFormatter {

    /**
     * Formats a template into a String response.
     *
     * @param templatePath the resolved path to the template file
     * @param locals       merged locals from {@code res.locals()}, {@code app.locals()},
     *                     and the locals passed to {@code res.render()}
     * @return the rendered String to be sent as the response body
     * @throws RenderException if rendering fails (file missing, syntax error, etc.)
     */
    String format(String templatePath, Map<String, Object> locals) throws RenderException;

    // ── Built-in Formatters ───────────────────────────────────────────────────

    /**
     * Mustache template formatter.
     *
     * <p>Renders {@code .mustache} or {@code .html} Mustache templates using the
     * Mustache.java library. {@code {{variable}}} syntax, partials, and sections
     * all supported.
     *
     * <p>Requires {@code com.github.spullara.mustache.java:compiler} on the classpath.
     * Delivered as a stub in ROADMAP-02 Phase 8 — full impl in {@code cafeai-core}.
     */
    static ResponseFormatter mustache() {
        return MustacheFormatter.INSTANCE;
    }

    /**
     * Markdown formatter.
     *
     * <p>Renders Markdown templates to HTML. Variables in the template are
     * first interpolated using {@code {{variable}}} syntax, then the result is
     * rendered as HTML via CommonMark.
     *
     * <p>Requires {@code org.commonmark:commonmark} on the classpath.
     * Delivered as a stub in ROADMAP-02 Phase 8 — full impl in {@code cafeai-core}.
     */
    static ResponseFormatter markdown() {
        return MarkdownFormatter.INSTANCE;
    }

    /**
     * Raw template formatter — interpolates {@code {{variable}}} placeholders
     * in a template string with no additional rendering.
     * Zero dependencies. Useful for plain text and HTML templates without a
     * full template engine.
     *
     * <pre>{@code
     *   app.engine("txt", ResponseFormatter.template());
     *   // Template: "Hello, {{name}}!"
     *   // Locals:   {"name": "Ada"}
     *   // Output:   "Hello, Ada!"
     * }</pre>
     */
    static ResponseFormatter template() {
        return (templatePath, locals) -> {
            try {
                String content = Files.readString(
                    Path.of(templatePath));
                for (var entry : locals.entrySet()) {
                    if (entry.getValue() != null) {
                        content = content.replace(
                            "{{" + entry.getKey() + "}}", entry.getValue().toString());
                    }
                }
                return content;
            } catch (IOException e) {
                throw new RenderException("Cannot read template: " + templatePath, e);
            }
        };
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /**
     * Thrown when template rendering fails.
     */
    class RenderException extends RuntimeException {
        public RenderException(String message) {
            super(message);
        }
        public RenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Stub Implementations (Phase 8 stubs — replaced with real impls) ───────

    /** Mustache stub — logs warning and returns empty string until fully implemented. */
    enum MustacheFormatter implements ResponseFormatter {
        INSTANCE;

        private static final System.Logger LOG =
            System.getLogger(MustacheFormatter.class.getName());

        @Override
        public String format(String templatePath, Map<String, Object> locals) {
            LOG.log(System.Logger.Level.WARNING,
                "Mustache renderer is a stub — full implementation in ROADMAP-02 Phase 8. " +
                "Add mustache.java to classpath to enable.");
            // Fallback: use simple {{variable}} substitution
            try {
                return ResponseFormatter.template().format(templatePath, locals);
            } catch (RenderException e) {
                throw e;
            }
        }
    }

    /** Markdown stub — falls back to raw template rendering until fully implemented. */
    enum MarkdownFormatter implements ResponseFormatter {
        INSTANCE;

        private static final System.Logger LOG =
            System.getLogger(MarkdownFormatter.class.getName());

        @Override
        public String format(String templatePath, Map<String, Object> locals) {
            LOG.log(System.Logger.Level.WARNING,
                "Markdown renderer is a stub — full implementation in ROADMAP-02 Phase 8. " +
                "Add commonmark to classpath to enable.");
            try {
                return ResponseFormatter.template().format(templatePath, locals);
            } catch (RenderException e) {
                throw e;
            }
        }
    }
}
