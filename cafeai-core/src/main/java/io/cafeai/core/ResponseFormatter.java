package io.cafeai.core;

import io.cafeai.core.spi.ViewEngineProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * A response formatter — CafeAI's abstraction over template engines and
 * structured AI output formatters.
 *
 * <p>Register formatters via {@code app.engine(ext, formatter)}.
 * Use them via {@code res.render(view, locals)}.
 *
 * <h2>Built-in formatters</h2>
 * <ul>
 *   <li>{@link #template()} — simple {@code {{variable}}} substitution.
 *       Zero dependencies. <strong>Development only</strong> — no loops,
 *       no conditionals, no HTML escaping. Not suitable for production.</li>
 * </ul>
 *
 * <h2>Optional engine modules</h2>
 * <p>Real template engines are provided by optional modules on the classpath.
 * Adding the JAR is the only configuration needed — no code changes required.
 * Each module self-registers via {@link java.util.ServiceLoader}.
 *
 * <table>
 *   <tr><th>Method</th><th>Module</th><th>Dependency</th></tr>
 *   <tr><td>{@link #mustache()}</td><td>{@code cafeai-views-mustache}</td>
 *       <td>{@code io.cafeai:cafeai-views-mustache}</td></tr>
 *   <tr><td>{@link #markdown()}</td><td>{@code cafeai-views-markdown}</td>
 *       <td>{@code io.cafeai:cafeai-views-markdown}</td></tr>
 * </table>
 *
 * <pre>{@code
 *   // Register engines
 *   app.engine("html", ResponseFormatter.mustache());   // requires cafeai-views-mustache
 *   app.engine("md",   ResponseFormatter.markdown());   // requires cafeai-views-markdown
 *
 *   // Development only — no loops or escaping
 *   app.engine("txt",  ResponseFormatter.template());
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
     * @param templatePath the resolved absolute path to the template file
     * @param locals       merged locals: {@code app.locals()} ∪ {@code res.locals()}
     *                     ∪ locals passed directly to {@code res.render()}
     * @return the rendered String to send as the response body
     * @throws RenderException if rendering fails (file not found, syntax error, etc.)
     */
    String format(String templatePath, Map<String, Object> locals) throws RenderException;

    // ── Optional engine discovery ─────────────────────────────────────────────

    /**
     * Returns a Mustache template formatter.
     *
     * <p>Requires {@code io.cafeai:cafeai-views-mustache} on the classpath.
     * That module self-registers via {@link ServiceLoader} — no code changes needed.
     * Supports {@code {{variable}}}, {@code {{#section}}}, {@code {{>partial}}} syntax.
     *
     * <p>If the module is not present, throws {@link RenderException} with a clear
     * message indicating which dependency to add.
     *
     * @throws RenderException if {@code cafeai-views-mustache} is not on the classpath
     */
    static ResponseFormatter mustache() {
        return loadEngine("mustache", "io.cafeai:cafeai-views-mustache");
    }

    /**
     * Returns a Markdown-to-HTML formatter.
     *
     * <p>Requires {@code io.cafeai:cafeai-views-markdown} on the classpath.
     * Variables are interpolated via {@code {{variable}}} before Markdown rendering.
     * Output is wrapped in a configurable HTML shell.
     *
     * @throws RenderException if {@code cafeai-views-markdown} is not on the classpath
     */
    static ResponseFormatter markdown() {
        return loadEngine("markdown", "io.cafeai:cafeai-views-markdown");
    }

    /**
     * Simple {@code {{variable}}} substitution formatter.
     *
     * <p><strong>Development and simple use cases only.</strong>
     * This formatter replaces {@code {{key}}} placeholders with values from
     * the locals map. It intentionally does nothing else:
     * <ul>
     *   <li>No loops ({@code {{#items}}})</li>
     *   <li>No conditionals ({@code {{#if condition}}})</li>
     *   <li>No partials ({@code {{>partial}}})</li>
     *   <li>No HTML escaping — values are inserted verbatim</li>
     *   <li>No inheritance or blocks</li>
     * </ul>
     *
     * <p>For production rendering, add {@code cafeai-views-mustache} and use
     * {@link #mustache()} instead.
     *
     * <pre>{@code
     *   app.engine("txt", ResponseFormatter.template());
     *   // Template: "Hello, {{name}}! Environment: {{env}}"
     *   // Locals:   {"name": "Ada", "env": "production"}
     *   // Output:   "Hello, Ada! Environment: production"
     * }</pre>
     */
    static ResponseFormatter template() {
        return (templatePath, locals) -> {
            String content;
            try {
                content = Files.readString(Path.of(templatePath));
            } catch (IOException e) {
                throw new RenderException(
                    "Template not found: " + templatePath, e);
            }
            for (var entry : locals.entrySet()) {
                if (entry.getValue() != null) {
                    content = content.replace(
                        "{{" + entry.getKey() + "}}", entry.getValue().toString());
                }
            }
            return content;
        };
    }

    // ── ServiceLoader discovery ───────────────────────────────────────────────

    /**
     * Discovers a {@link ViewEngineProvider} with the given {@code engineId}
     * via {@link ServiceLoader}.
     *
     * <p>This is the mechanism that makes optional engine modules self-registering:
     * the module JAR contains a
     * {@code META-INF/services/io.cafeai.core.spi.ViewEngineProvider} file
     * that lists its provider class. Adding the JAR to the classpath is the
     * only configuration needed.
     *
     * @param engineId    the engine identifier to look up (e.g. {@code "mustache"})
     * @param moduleCoord the Gradle/Maven coordinate to display in the error message
     * @throws RenderException if no provider for {@code engineId} is found
     */
    private static ResponseFormatter loadEngine(String engineId, String moduleCoord) {
        return ServiceLoader.load(ViewEngineProvider.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .filter(p -> p.engineId().equalsIgnoreCase(engineId))
            .findFirst()
            .map(ViewEngineProvider::create)
            .orElseThrow(() -> new RenderException(
                "No view engine found for '" + engineId + "'. " +
                "Add the following dependency to your build:\n\n" +
                "  Gradle: implementation '" + moduleCoord + "'\n" +
                "  Maven:  <dependency>\n" +
                "              <groupId>" + moduleCoord.split(":")[0] + "</groupId>\n" +
                "              <artifactId>" + moduleCoord.split(":")[1] + "</artifactId>\n" +
                "          </dependency>"));
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /**
     * Thrown when template rendering fails for any reason:
     * missing file, missing engine module, syntax error, or I/O failure.
     */
    class RenderException extends RuntimeException {

        public RenderException(String message) {
            super(message);
        }

        public RenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
