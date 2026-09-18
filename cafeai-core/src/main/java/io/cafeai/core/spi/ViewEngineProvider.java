package io.cafeai.core.spi;

import io.cafeai.core.ResponseFormatter;

/**
 * SPI for optional view engine modules.
 *
 * <p>Implement this interface in a module (e.g. {@code cafeai-views-mustache})
 * to make a {@link ResponseFormatter} discoverable at runtime via
 * {@link java.util.ServiceLoader}. The implementation JAR declares itself in:
 * <pre>
 *   META-INF/services/io.cafeai.core.spi.ViewEngineProvider
 * </pre>
 *
 * <p>This is the same pattern CafeAI uses for {@link CafeAIModule}: adding the JAR to the classpath
 * makes the engine's factory (such as {@code ResponseFormatter.mustache()}) available. The
 * application still registers the result with {@code app.engine(ext, formatter)}.
 *
 * <p>Example implementation in {@code cafeai-views-mustache}:
 * <pre>{@code
 *   public class MustacheViewEngineProvider implements ViewEngineProvider {
 *       @Override public String engineId() { return "mustache"; }
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
     * Canonical engine identifier, matched (case-insensitively) by the factory method that loads
     * it: {@code "mustache"} for {@code ResponseFormatter.mustache()}.
     */
    String engineId();

    /**
     * Creates a new {@link ResponseFormatter} instance for this engine. Called each time the
     * factory method is invoked; register the result once with {@code app.engine(...)}.
     */
    ResponseFormatter create();
}
