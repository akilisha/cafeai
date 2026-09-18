package io.cafeai.views.mustache;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import io.cafeai.core.ResponseFormatter;
import io.cafeai.core.spi.ViewEngineProvider;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mustache view engine provider for CafeAI.
 *
 * <p>Self-registers via {@link java.util.ServiceLoader}: adding
 * {@code com.akilisha.oss:cafeai-views-mustache} to the classpath makes
 * {@code ResponseFormatter.mustache()} available, which you register with {@code app.engine(...)}.
 *
 * <p>Compiled templates are cached, and a template whose file has changed is recompiled on its next
 * render (edit a partial, then touch the template that includes it). Templates are read from the
 * views directory only: {@code {{>partial}}} resolves next to the including template.
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
    public ResponseFormatter create() {
        return new MustacheResponseFormatter();
    }

    // ── Formatter implementation ──────────────────────────────────────────────

    static final class MustacheResponseFormatter implements ResponseFormatter {

        /** A compiled template and the file modification time it was compiled from. */
        private record Compiled(Mustache mustache, long modified) {}

        // One factory per template directory. Mustache.java resolves a name against a directory root,
        // never against an absolute path (an absolute Windows path such as D:iews.html is not even
        // a valid name to it), so each template is compiled by file name against its own directory. That
        // is also what makes {{>partial}} resolve next to the template that includes it.
        private final Map<Path, DefaultMustacheFactory> factories = new ConcurrentHashMap<>();
        private final Map<Path, Compiled> compiled = new ConcurrentHashMap<>();

        @Override
        public String format(String templatePath, Map<String, Object> locals)
                throws RenderException {
            Path template = Path.of(templatePath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(template)) {
                throw new RenderException("Template not found: " + templatePath);
            }
            try {
                StringWriter writer = new StringWriter();
                mustache(template).execute(writer, locals).flush();
                return writer.toString();
            } catch (RenderException e) {
                throw e;
            } catch (Exception e) {
                throw new RenderException(
                    "Mustache rendering failed for template: " + templatePath, e);
            }
        }

        private DefaultMustacheFactory replace(Path dir) {
            DefaultMustacheFactory fresh = new DefaultMustacheFactory(dir.toFile());
            factories.put(dir, fresh);
            return fresh;
        }

        /**
         * The compiled template, reused across requests. A template whose file has changed since it was
         * compiled is compiled again, so an edit shows up without a restart. Only the template file
         * itself is watched: after editing a partial, touch the template that includes it.
         */
        private Mustache mustache(Path template) throws IOException {
            long modified = Files.getLastModifiedTime(template).toMillis();
            Compiled current = compiled.get(template);
            if (current != null && current.modified() == modified) {
                return current.mustache();
            }
            synchronized (this) {
                current = compiled.get(template);
                if (current != null && current.modified() == modified) {
                    return current.mustache();
                }
                Path dir = template.getParent();
                // A changed template gets a fresh factory: its caches hold the stale compiled template
                // and the partials it included. Other templates already compiled keep working.
                DefaultMustacheFactory factory = (current == null)
                    ? factories.computeIfAbsent(dir, d -> new DefaultMustacheFactory(d.toFile()))
                    : replace(dir);
                Mustache mustache = factory.compile(template.getFileName().toString());
                compiled.put(template, new Compiled(mustache, modified));
                return mustache;
            }
        }
    }
}
