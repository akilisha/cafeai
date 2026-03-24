package io.cafeai.core.spi;

import io.cafeai.core.ResponseFormatter;

/**
 * SPI for optional view engine modules.
 *
 * <p>Implement this interface in a module (e.g. {@code cafeai-views-mustache})
 * to make a {@link ResponseFormatter} discoverable by CafeAI at runtime via
 * {@link java.util.ServiceLoader}. The implementation JAR declares itself in:
 * <pre>
 *   META-INF/services/io.cafeai.core.spi.ViewEngineProvider
 * </pre>
 *
 * <p>This is the same pattern CafeAI uses for {@link CafeAIModule} — adding
 * the JAR to the classpath IS the configuration. No code changes required.
 *
 * <p>Example implementation in {@code cafeai-views-mustache}:
 * <pre>{@code
 *   public class MustacheViewEngineProvider implements ViewEngineProvider {
 *       @Override public String engineId() { return "mustache"; }
 *       @Override public String[] extensions() { return new String[]{"mustache", "html"}; }
 *       @Override public ResponseFormatter create() {
 *           return (templatePath, locals) -> {
 *               MustacheFactory mf = new DefaultMustacheFactory();
 *               Mustache m = mf.compile(templatePath);
 *               StringWriter sw = new StringWriter();
 *               m.execute(sw, locals).flush();
 *               return sw.toString();
 *           };
 *       }
 *   }
 * }</pre>
 */
public interface ViewEngineProvider {

    /**
     * Canonical engine identifier — used to match against
     * {@code ResponseFormatter.mustache()}, {@code ResponseFormatter.markdown()}, etc.
     *
     * <p>Well-known IDs: {@code "mustache"}, {@code "markdown"}, {@code "pebble"},
     * {@code "thymeleaf"}, {@code "freemarker"}, {@code "handlebars"}.
     */
    String engineId();

    /**
     * File extensions this engine handles.
     * Used for automatic engine selection when no explicit engine is registered.
     * Example: {@code ["mustache", "html"]}
     */
    String[] extensions();

    /**
     * Creates a new {@link ResponseFormatter} instance for this engine.
     * Called once per application — the result is cached by CafeAI.
     */
    ResponseFormatter create();
}
