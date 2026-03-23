package io.cafeai.core;

import io.cafeai.core.internal.CafeAIApp;
import org.junit.jupiter.api.DisplayName;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-02: Application object.
 *
 * <p>Covers Phases 1, 2, 7, 8, 9 — the new methods added in this phase.
 * Phases 3–6 (HTTP verbs, use(), param(), route()) are tested in CafeAIAppTest
 * and were delivered in ROADMAP-01.
 */
class ApplicationTest {

    // ── Phase 1: app.locals() snapshot ────────────────────────────────────────

    @Test
    @DisplayName("app.locals() returns unmodifiable snapshot of user locals")
    void locals_returnsSnapshot() {
        var app = CafeAI.create();
        app.local("name", "CafeAI");
        app.local("version", "1.0");

        Map<String, Object> snapshot = app.locals();
        assertThat(snapshot).containsEntry("name", "CafeAI");
        assertThat(snapshot).containsEntry("version", "1.0");
    }

    @Test
    @DisplayName("app.locals() snapshot is unmodifiable")
    void locals_snapshotIsUnmodifiable() {
        var app = CafeAI.create();
        app.local("key", "value");

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> app.locals().put("injected", "hack"));
    }

    @Test
    @DisplayName("app.locals() excludes internal CafeAI keys")
    void locals_excludesInternalKeys() {
        var app = CafeAI.create();
        app.local(Locals.AI_PROVIDER, "some-provider");
        app.local("userKey", "visible");

        Map<String, Object> snapshot = app.locals();
        assertThat(snapshot).doesNotContainKey(Locals.AI_PROVIDER);
        assertThat(snapshot).containsKey("userKey");
    }

    @Test
    @DisplayName("app.locals() returns empty map when no user locals set")
    void locals_emptyWhenNoLocals() {
        var app = CafeAI.create();
        assertThat(app.locals()).isEmpty();
    }

    @Test
    @DisplayName("Locals.isInternal() correctly identifies internal keys")
    void locals_isInternal() {
        assertThat(Locals.isInternal(Locals.AI_PROVIDER)).isTrue();
        assertThat(Locals.isInternal(Locals.MEMORY_STRATEGY)).isTrue();
        assertThat(Locals.isInternal("myKey")).isFalse();
        assertThat(Locals.isInternal(null)).isFalse();
    }

    // ── Phase 2: mountpath, mountpaths, onMount, path ─────────────────────────

    @Test
    @DisplayName("Root app mountpath() returns empty string")
    void mountpath_rootApp_isEmpty() {
        var app = CafeAI.create();
        assertThat(app.mountpath()).isEmpty();
    }

    @Test
    @DisplayName("Root app mountpaths() returns empty list")
    void mountpaths_rootApp_isEmpty() {
        var app = CafeAI.create();
        assertThat(app.mountpaths()).isEmpty();
    }

    @Test
    @DisplayName("Root app path() returns empty string")
    void path_rootApp_isEmpty() {
        var app = CafeAI.create();
        assertThat(app.path()).isEmpty();
    }

    @Test
    @DisplayName("onMount callback fires when sub-app is mounted")
    void onMount_callbackFires() {
        var parent = CafeAI.create();
        var child  = CafeAI.create();
        var fired  = new AtomicReference<CafeAI>();

        child.onMount(p -> fired.set(p));
        ((CafeAIApp) child).notifyMount(parent, "/admin");

        assertThat(fired.get()).isSameAs(parent);
    }

    @Test
    @DisplayName("After mount: child.mountpath() returns mount path")
    void mountpath_afterMount_returnsPath() {
        var parent = CafeAI.create();
        var child  = CafeAI.create();

        ((CafeAIApp) child).notifyMount(parent, "/admin");

        assertThat(child.mountpath()).isEqualTo("/admin");
    }

    @Test
    @DisplayName("After mount: child.mountpaths() contains mount path")
    void mountpaths_afterMount_containsPath() {
        var parent = CafeAI.create();
        var child  = CafeAI.create();

        ((CafeAIApp) child).notifyMount(parent, "/admin");

        assertThat(child.mountpaths()).containsExactly("/admin");
    }

    @Test
    @DisplayName("child.path() returns full path including parent")
    void path_nested_returnsFullPath() {
        var root  = CafeAI.create();
        var admin = CafeAI.create();
        var users = CafeAI.create();

        ((CafeAIApp) admin).notifyMount(root,  "/admin");
        ((CafeAIApp) users).notifyMount(admin, "/users");

        assertThat(users.path()).isEqualTo("/admin/users");
    }

    @Test
    @DisplayName("onMount returns CafeAI for chaining")
    void onMount_returnsApp() {
        var app    = CafeAI.create();
        var result = app.onMount(p -> {});
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("onMount(null) throws NullPointerException")
    void onMount_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
            .isThrownBy(() -> app.onMount(null));
    }

    // ── Phase 7: Settings ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Settings have correct Express-matching defaults")
    void settings_expressDefaults() {
        var app = CafeAI.create();
        assertThat(app.setting(Setting.ENV,                 String.class))  .isEqualTo("development");
        assertThat(app.setting(Setting.X_POWERED_BY,        Boolean.class)) .isTrue();
        assertThat(app.setting(Setting.TRUST_PROXY,         Boolean.class)) .isFalse();
        assertThat(app.setting(Setting.CASE_SENSITIVE_ROUTING, Boolean.class)).isFalse();
        assertThat(app.setting(Setting.STRICT_ROUTING,      Boolean.class)) .isFalse();
        assertThat(app.setting(Setting.SUBDOMAIN_OFFSET,    Integer.class)) .isEqualTo(2);
        assertThat(app.setting(Setting.JSON_SPACES,         Integer.class)) .isEqualTo(0);
        assertThat(app.setting(Setting.ETAG,                String.class))  .isEqualTo("weak");
        assertThat(app.setting(Setting.VIEWS,               String.class))  .isEqualTo("views");
        assertThat(app.setting(Setting.VIEW_ENGINE,         String.class))  .isNull();
    }

    @Test
    @DisplayName("app.set() stores and app.setting() retrieves correctly")
    void set_and_setting_roundTrip() {
        var app = CafeAI.create();
        app.set(Setting.ENV, "production");
        app.set(Setting.JSON_SPACES, 4);

        assertThat(app.setting(Setting.ENV, String.class)).isEqualTo("production");
        assertThat(app.setting(Setting.JSON_SPACES, Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("app.set() returns CafeAI for chaining")
    void set_returnsApp() {
        var app = CafeAI.create();
        assertThat(app.set(Setting.ENV, "test")).isSameAs(app);
    }

    @Test
    @DisplayName("app.enable() sets boolean setting to true")
    void enable_setsBooleanTrue() {
        var app = CafeAI.create();
        app.disable(Setting.X_POWERED_BY);
        app.enable(Setting.X_POWERED_BY);

        assertThat(app.enabled(Setting.X_POWERED_BY)).isTrue();
    }

    @Test
    @DisplayName("app.disable() sets boolean setting to false")
    void disable_setsBooleanFalse() {
        var app = CafeAI.create();
        app.disable(Setting.X_POWERED_BY);

        assertThat(app.disabled(Setting.X_POWERED_BY)).isTrue();
        assertThat(app.enabled(Setting.X_POWERED_BY)).isFalse();
    }

    @Test
    @DisplayName("app.enable() on non-boolean setting throws IllegalArgumentException")
    void enable_nonBoolean_throws() {
        var app = CafeAI.create();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> app.enable(Setting.ENV));
    }

    @Test
    @DisplayName("app.disable() on non-boolean setting throws IllegalArgumentException")
    void disable_nonBoolean_throws() {
        var app = CafeAI.create();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> app.disable(Setting.JSON_SPACES));
    }

    @Test
    @DisplayName("app.enabled() returns true for truthy settings")
    void enabled_truthyValues() {
        var app = CafeAI.create();
        app.enable(Setting.TRUST_PROXY);
        assertThat(app.enabled(Setting.TRUST_PROXY)).isTrue();
    }

    @Test
    @DisplayName("app.disabled() returns true for falsy settings")
    void disabled_falsyValues() {
        var app = CafeAI.create();
        app.disable(Setting.TRUST_PROXY);
        assertThat(app.disabled(Setting.TRUST_PROXY)).isTrue();
    }

    @Test
    @DisplayName("Setting enum has isBoolean() working correctly")
    void setting_isBoolean() {
        assertThat(Setting.X_POWERED_BY.isBoolean()).isTrue();
        assertThat(Setting.TRUST_PROXY.isBoolean()).isFalse();   // Object type (bool OR hop count)
        assertThat(Setting.ENV.isBoolean()).isFalse();
        assertThat(Setting.CASE_SENSITIVE_ROUTING.isBoolean()).isTrue();
        assertThat(Setting.STRICT_ROUTING.isBoolean()).isTrue();
    }

    @Test
    @DisplayName("Setting enum has correct Express names")
    void setting_expressNames() {
        assertThat(Setting.ENV.expressName()).isEqualTo("env");
        assertThat(Setting.TRUST_PROXY.expressName()).isEqualTo("trust proxy");
        assertThat(Setting.CASE_SENSITIVE_ROUTING.expressName()).isEqualTo("case sensitive routing");
        assertThat(Setting.VIEW_ENGINE.expressName()).isEqualTo("view engine");
    }

    // ── Phase 8: Template engine registration ─────────────────────────────────

    @Test
    @DisplayName("app.engine() registers a formatter — returns CafeAI for chaining")
    void engine_registers_returnsApp() {
        var app = CafeAI.create();
        var result = app.engine("html", ResponseFormatter.template());
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.engine() accepts extension with or without leading dot")
    void engine_normalisesExtension() {
        var app = CafeAI.create();
        assertThatCode(() -> {
            app.engine("html",  ResponseFormatter.template());
            app.engine(".mustache", ResponseFormatter.mustache());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.engine(null ext) throws NullPointerException")
    void engine_nullExt_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
            .isThrownBy(() -> app.engine(null, ResponseFormatter.template()));
    }

    @Test
    @DisplayName("app.engine(ext, null formatter) throws NullPointerException")
    void engine_nullFormatter_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
            .isThrownBy(() -> app.engine("html", null));
    }

    @Test
    @DisplayName("app.render() with no engine throws RenderException via callback")
    void render_noEngine_callbackReceivesError() {
        var app = CafeAI.create();
        app.set(Setting.VIEW_ENGINE, "html");

        var errorHolder = new AtomicReference<Throwable>();
        app.render("missing", Map.of(), (err, html) -> errorHolder.set(err));

        assertThat(errorHolder.get())
            .isInstanceOf(ResponseFormatter.RenderException.class)
            .hasMessageContaining("html");
    }

    @Test
    @DisplayName("ResponseFormatter.template() performs {{variable}} substitution")
    void responseFormatter_template_substitutes() throws Exception {
        // Create a temp file with a template
        var tmpFile = Files.createTempFile("cafeai-test-", ".txt");
        Files.writeString(tmpFile, "Hello, {{name}}! You are {{age}} years old.");

        var formatter = ResponseFormatter.template();
        String result = formatter.format(tmpFile.toString(),
            Map.of("name", "Ada", "age", "30"));

        assertThat(result).isEqualTo("Hello, Ada! You are 30 years old.");
        Files.deleteIfExists(tmpFile);
    }

    @Test
    @DisplayName("ResponseFormatter.template() throws RenderException for missing file")
    void responseFormatter_template_missingFile_throws() {
        var formatter = ResponseFormatter.template();
        assertThatExceptionOfType(ResponseFormatter.RenderException.class)
            .isThrownBy(() -> formatter.format("/nonexistent/path/view.html", Map.of()));
    }

    @Test
    @DisplayName("app.render() CompletableFuture form completes with no engine → exception")
    void render_completableFuture_noEngine() {
        var app = CafeAI.create();
        app.set(Setting.VIEW_ENGINE, "html");

        var future = app.render("test", Map.of());
        assertThat(future).isCompletedExceptionally();
    }

    // ── Phase 7: Setting enum completeness ────────────────────────────────────

    @Test
    @DisplayName("All Setting enum values have non-null expressName")
    void setting_allHaveExpressName() {
        for (Setting s : Setting.values()) {
            assertThat(s.expressName())
                .as("Setting.%s should have an Express name", s.name())
                .isNotNull()
                .isNotBlank();
        }
    }

    @Test
    @DisplayName("All Setting enum values have a declared valueType")
    void setting_allHaveValueType() {
        for (Setting s : Setting.values()) {
            assertThat(s.valueType())
                .as("Setting.%s should have a value type", s.name())
                .isNotNull();
        }
    }
}
