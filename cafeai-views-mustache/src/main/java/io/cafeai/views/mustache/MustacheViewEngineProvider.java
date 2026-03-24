package io.cafeai.views.mustache;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import io.cafeai.core.ResponseFormatter;
import io.cafeai.core.spi.ViewEngineProvider;

import java.io.File;
import java.io.StringWriter;
import java.util.Map;

/**
 * Mustache view engine provider for CafeAI.
 *
 * <p>Self-registers via {@link java.util.ServiceLoader} — no code changes needed.
 * Adding {@code io.cafeai:cafeai-views-mustache} to the classpath activates this provider.
 *
 * <p>Mustache is chosen as the reference CafeAI view engine because:
 * <ul>
 *   <li><strong>Logic-less</strong> — templates cannot contain business logic,
 *       enforcing clean separation between view and controller</li>
 *   <li><strong>Express ecosystem familiarity</strong> — Mustache is ubiquitous
 *       in Node.js and was Handlebars' predecessor; Express developers know it</li>
 *   <li><strong>Multi-language</strong> — same template syntax works in Java,
 *       JavaScript, Python, Ruby. Portability without rewriting templates</li>
 *   <li><strong>Zero magic</strong> — the full spec fits in one page. No surprises</li>
 * </ul>
 *
 * <p>Supports the complete Mustache spec:
 * <ul>
 *   <li>{@code {{variable}}} — HTML-escaped variable interpolation</li>
 *   <li>{@code {{{variable}}}} — unescaped (triple-stache) interpolation</li>
 *   <li>{@code {{#section}}...{{/section}}} — sections (truthy/falsy, lists)</li>
 *   <li>{@code {{^section}}...{{/section}}} — inverted sections</li>
 *   <li>{@code {{>partial}}} — partials (resolved relative to template directory)</li>
 *   <li>{@code {{!comment}}} — comments (not rendered)</li>
 * </ul>
 */
public final class MustacheViewEngineProvider implements ViewEngineProvider {

    @Override
    public String engineId() {
        return "mustache";
    }

    @Override
    public String[] extensions() {
        return new String[]{"mustache", "html", "htm"};
    }

    @Override
    public ResponseFormatter create() {
        return new MustacheResponseFormatter();
    }

    // ── Formatter implementation ──────────────────────────────────────────────

    static final class MustacheResponseFormatter implements ResponseFormatter {

        // MustacheFactory is thread-safe and caches compiled templates internally.
        // One factory per formatter instance — shared across all requests.
        private final MustacheFactory factory = new DefaultMustacheFactory();

        @Override
        public String format(String templatePath, Map<String, Object> locals)
                throws RenderException {
            try {
                File templateFile = new File(templatePath);
                if (!templateFile.exists()) {
                    throw new RenderException(
                        "Template not found: " + templatePath);
                }

                // MustacheFactory.compile() uses the template path as cache key.
                // Compiled templates are reused across requests — zero re-parse overhead.
                Mustache mustache = factory.compile(templatePath);

                StringWriter writer = new StringWriter();
                mustache.execute(writer, locals).flush();
                return writer.toString();

            } catch (RenderException e) {
                throw e;
            } catch (Exception e) {
                throw new RenderException(
                    "Mustache rendering failed for template: " + templatePath, e);
            }
        }
    }
}
