package io.cafeai.views.mustache;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ResponseFormatter;
import io.cafeai.core.ResponseFormatter.RenderException;
import io.cafeai.core.Setting;
import io.cafeai.core.spi.ViewEngineProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Mustache view engine: what it renders, what it refuses, and that it works through a running
 * app ({@code app.engine(...)}, {@code res.render(...)}), not only when called directly.
 */
@DisplayName("Mustache view engine")
class MustacheViewEngineTest {

    @TempDir Path views;

    private ResponseFormatter engine;

    @BeforeEach
    void engine() {
        engine = ResponseFormatter.mustache();
    }

    private Path template(String name, String body) throws IOException {
        Path file = views.resolve(name);
        Files.writeString(file, body);
        return file;
    }

    private String render(String name, Map<String, Object> locals) {
        return engine.format(views.resolve(name).toString(), locals);
    }

    // -- discovery ---------------------------------------------------------------------------------

    @Nested @DisplayName("discovery")
    class Discovery {
        @Test @DisplayName("the provider is found through ServiceLoader as 'mustache'")
        void serviceLoader() {
            List<String> ids = new ArrayList<>();
            ServiceLoader.load(ViewEngineProvider.class).forEach(p -> ids.add(p.engineId()));

            assertThat(ids).containsExactly("mustache");
        }

        @Test @DisplayName("ResponseFormatter.mustache() finds it, so the JAR on the classpath is the configuration")
        void factory() throws IOException {
            template("hello.mustache", "Hello {{name}}");

            assertThat(render("hello.mustache", Map.of("name", "Ada"))).isEqualTo("Hello Ada");
        }
    }

    // -- the Mustache language --------------------------------------------------------------------------

    @Nested @DisplayName("rendering")
    class Rendering {
        @Test @DisplayName("{{x}} is HTML-escaped and {{{x}}} is not")
        void escaping() throws IOException {
            template("t.mustache", "{{x}}|{{{x}}}");

            assertThat(render("t.mustache", Map.of("x", "<script>alert(\"hi\")</script>")))
                .isEqualTo("&lt;script&gt;alert(&quot;hi&quot;)&lt;/script&gt;|<script>alert(\"hi\")</script>");
        }

        @Test @DisplayName("a missing variable renders as nothing, and so does null")
        void missing() throws IOException {
            template("t.mustache", "[{{absent}}][{{nothing}}]");
            Map<String, Object> locals = new java.util.HashMap<>();
            locals.put("nothing", null);

            assertThat(render("t.mustache", locals)).isEqualTo("[][]");
        }

        @Test @DisplayName("sections iterate lists, and dotted names reach into nested maps")
        void sectionsAndNesting() throws IOException {
            template("t.mustache", "{{#items}}<{{name}}>{{/items}} {{user.profile.city}}");

            assertThat(render("t.mustache", Map.of(
                "items", List.of(Map.of("name", "a"), Map.of("name", "b")),
                "user", Map.of("profile", Map.of("city", "Nairobi")))))
                .isEqualTo("<a><b> Nairobi");
        }

        @Test @DisplayName("sections are conditional and inverted sections are their negation")
        void conditionals() throws IOException {
            template("t.mustache", "{{#admin}}A{{/admin}}{{^admin}}U{{/admin}}");

            assertThat(render("t.mustache", Map.of("admin", true))).isEqualTo("A");
            assertThat(render("t.mustache", Map.of("admin", false))).isEqualTo("U");
            assertThat(render("t.mustache", Map.of())).isEqualTo("U");
            assertThat(render("t.mustache", Map.of("admin", List.of()))).as("an empty list is falsy").isEqualTo("U");
        }

        @Test @DisplayName("comments are not rendered")
        void comments() throws IOException {
            template("t.mustache", "a{{! not shown }}b");

            assertThat(render("t.mustache", Map.of())).isEqualTo("ab");
        }

        @Test @DisplayName("a partial is resolved next to the template that includes it")
        void partials() throws IOException {
            template("header.mustache", "<h1>{{title}}</h1>");
            template("page.mustache", "{{>header}}<p>{{body}}</p>");

            assertThat(render("page.mustache", Map.of("title", "Hi", "body", "text")))
                .isEqualTo("<h1>Hi</h1><p>text</p>");
        }

        @Test @DisplayName("a partial uses the including template's extension: page.html includes header.html")
        void partialsFollowTheExtension() throws IOException {
            template("header.html", "<header>{{title}}</header>");
            template("page.html", "{{>header}}!");

            assertThat(render("page.html", Map.of("title", "T"))).isEqualTo("<header>T</header>!");
        }
    }

    // -- refusals ------------------------------------------------------------------------------------------

    @Nested @DisplayName("errors")
    class Errors {
        @Test @DisplayName("a missing template is a RenderException that names it")
        void missingTemplate() {
            String path = views.resolve("nope.mustache").toString();

            assertThatThrownBy(() -> engine.format(path, Map.of())).isInstanceOf(RenderException.class)
                .hasMessageContaining("Template not found").hasMessageContaining("nope.mustache");
        }

        @Test @DisplayName("a malformed template is a RenderException that names it and keeps the cause")
        void malformed() throws IOException {
            template("bad.mustache", "{{#open}} never closed");
            String path = views.resolve("bad.mustache").toString();

            assertThatThrownBy(() -> engine.format(path, Map.of())).isInstanceOf(RenderException.class)
                .hasMessageContaining("bad.mustache").hasCauseInstanceOf(Exception.class);
        }
    }

    // -- behaviour over time and under load ---------------------------------------------------------------------

    @Nested @DisplayName("reuse")
    class Reuse {
        @Test @DisplayName("a template edited on disk is rendered as edited, without a restart")
        void reloadsAnEditedTemplate() throws IOException, InterruptedException {
            Path file = template("t.mustache", "version one");
            assertThat(render("t.mustache", Map.of())).isEqualTo("version one");

            Files.writeString(file, "version two");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));

            assertThat(render("t.mustache", Map.of())).isEqualTo("version two");
        }

        @Test @DisplayName("one formatter renders the same template correctly from many threads at once")
        void threadSafe() throws Exception {
            template("t.mustache", "{{n}}:{{#xs}}{{.}},{{/xs}}");
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    int n = i;
                    results.add(pool.submit(() -> render("t.mustache", Map.of("n", n, "xs", List.of(n, n + 1)))));
                }
                for (int i = 0; i < 200; i++) {
                    assertThat(results.get(i).get(10, TimeUnit.SECONDS)).isEqualTo(i + ":" + i + "," + (i + 1) + ",");
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // -- through a running app -------------------------------------------------------------------------------------

    @Nested @DisplayName("through CafeAI")
    class ThroughTheApp {

        private CafeAI configured() {
            var app = CafeAI.create();
            app.set(Setting.VIEWS, views.toString());
            app.set(Setting.VIEW_ENGINE, "mustache");
            app.engine("mustache", ResponseFormatter.mustache());
            return app;
        }

        @Test @DisplayName("app.render() renders a view from the views directory")
        void appRender() throws Exception {
            template("welcome.mustache", "Welcome, {{name}}!");

            String html = configured().render("welcome", Map.of("name", "Ada")).get(5, TimeUnit.SECONDS);

            assertThat(html).isEqualTo("Welcome, Ada!");
        }

        @Test @DisplayName("app.locals() are available to every view, and per-render locals win")
        void locals() throws Exception {
            template("t.mustache", "{{site}}/{{page}}");
            var app = configured();
            app.local("site", "CafeAI");
            app.local("page", "default");

            assertThat(app.render("t", Map.of("page", "home")).get(5, TimeUnit.SECONDS)).isEqualTo("CafeAI/home");
        }

        @Test @DisplayName("a view with no engine registered for its extension fails with the fix in the message")
        void unregisteredExtension() {
            var app = CafeAI.create();
            app.set(Setting.VIEWS, views.toString());

            assertThatThrownBy(() -> app.render("welcome.mustache", Map.of()).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(RenderException.class)
                .hasMessageContaining("app.engine");
        }

        @Test @DisplayName("res.render() serves the rendered page as HTML over HTTP")
        void resRender() throws Exception {
            template("page.mustache", "<h1>{{title}}</h1>{{#items}}<li>{{.}}</li>{{/items}}");
            var app = configured();
            app.get("/page", (req, res, next) ->
                res.render("page", Map.of("title", "Books & Films", "items", List.of("one", "two"))));
            int port;
            try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
            var started = new CountDownLatch(1);
            app.listen(port, started::countDown);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/page")).build(),
                    HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
                assertThat(response.body()).isEqualTo("<h1>Books &amp; Films</h1><li>one</li><li>two</li>");
            } finally {
                app.stop();
            }
        }
    }
}
