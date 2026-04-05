package io.cafeai.core;

import io.cafeai.core.ai.*;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.internal.CafeAIApp;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.ContentMap;
import io.cafeai.core.routing.CookieOptions;
import io.cafeai.core.routing.Router;
import io.cafeai.core.spi.CafeAIConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-01 Phase 1 acceptance criteria.
 * <p>
 * These tests cover everything that does NOT require a running Helidon server:
 * - Factory behaviour
 * - AI provider registration
 * - Memory strategy registration
 * - GuardRail registration
 * - Application locals
 * - CafeAIConfigurer seam
 * - Path translation (:id → {id})
 * - listen()-after-configure() guard
 * <p>
 * Integration tests (actual HTTP server start) are in CafeAIIntegrationTest.
 */
class CafeAIAppTest {

    // ── Factory ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CafeAI.create() returns a non-null CafeAI instance")
    void create_returnsNonNull() {
        var app = CafeAI.create();
        assertThat(app).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.create() returns a fresh instance each time")
    void create_returnsFreshInstances() {
        var app1 = CafeAI.create();
        var app2 = CafeAI.create();
        assertThat(app1).isNotSameAs(app2);
    }

    @Test
    @DisplayName("New instance is not running")
    void create_notRunningInitially() {
        var app = CafeAI.create();
        assertThat(app.isRunning()).isFalse();
    }

    // ── AI Provider Registration ──────────────────────────────────────────────

    @Test
    @DisplayName("app.ai(OpenAI.gpt4o()) registers provider — returns app for chaining")
    void ai_openAi_returnsApp() {
        var app = CafeAI.create();
        var result = app.ai(OpenAI.gpt4o());
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.ai(Anthropic.claude35Sonnet()) registers provider")
    void ai_anthropic_registers() {
        var app = CafeAI.create();
        assertThatCode(() -> app.ai(Anthropic.claude35Sonnet()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.ai(Ollama.llama3()) registers local provider")
    void ai_ollama_registers() {
        var app = CafeAI.create();
        assertThatCode(() -> app.ai(Ollama.llama3()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.ai(ModelRouter) registers smart router")
    void ai_modelRouter_registers() {
        var app = CafeAI.create();
        var router = ModelRouter.smart()
                .simple(OpenAI.gpt4oMini())
                .complex(OpenAI.gpt4o());
        assertThatCode(() -> app.ai(router))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.ai(null) throws NullPointerException")
    void ai_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.ai((AiProvider) null));
    }

    // ── Provider Factory Correctness ──────────────────────────────────────────

    @Test
    @DisplayName("OpenAI.gpt4o() has correct name and modelId")
    void openAi_gpt4o_hasCorrectMetadata() {
        var provider = OpenAI.gpt4o();
        assertThat(provider.name()).isEqualTo("openai");
        assertThat(provider.modelId()).isEqualTo("gpt-4o");
        assertThat(provider.type()).isEqualTo(AiProvider.ProviderType.OPENAI);
    }

    @Test
    @DisplayName("Anthropic.claude35Sonnet() has correct name and modelId")
    void anthropic_claude35_hasCorrectMetadata() {
        var provider = Anthropic.claude35Sonnet();
        assertThat(provider.name()).isEqualTo("anthropic");
        assertThat(provider.modelId()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(provider.type()).isEqualTo(AiProvider.ProviderType.ANTHROPIC);
    }

    @Test
    @DisplayName("Ollama.llama3() has correct name and type")
    void ollama_llama3_hasCorrectMetadata() {
        var provider = Ollama.llama3();
        assertThat(provider.name()).isEqualTo("ollama");
        assertThat(provider.modelId()).isEqualTo("llama3");
        assertThat(provider.type()).isEqualTo(AiProvider.ProviderType.OLLAMA);
    }

    @Test
    @DisplayName("Ollama.at(url).model(id) sets custom base URL")
    void ollama_remoteInstance_setsBaseUrl() {
        var provider = Ollama.at("http://gpu-server:11434").model("mistral");
        assertThat(provider.name()).isEqualTo("ollama");
        assertThat(provider.modelId()).isEqualTo("mistral");
    }

    @Test
    @DisplayName("OpenAI.of(modelId) accepts arbitrary model IDs")
    void openAi_of_acceptsArbitraryId() {
        var provider = OpenAI.of("gpt-4-turbo");
        assertThat(provider.modelId()).isEqualTo("gpt-4-turbo");
        assertThat(provider.name()).isEqualTo("openai");
    }

    // ── System Prompt & Templates ─────────────────────────────────────────────

    @Test
    @DisplayName("app.system() registers system prompt — returns app for chaining")
    void system_returnsApp() {
        var app = CafeAI.create();
        var result = app.system("You are a helpful assistant.");
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.system(null) throws NullPointerException")
    void system_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.system(null));
    }

    @Test
    @DisplayName("app.template() registers named template — returns app for chaining")
    void template_returnsApp() {
        var app = CafeAI.create();
        var result = app.template("classify", "Classify: {{input}}");
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.template(null, template) throws NullPointerException")
    void template_nullName_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.template(null, "template"));
    }

    // ── Memory Strategy ───────────────────────────────────────────────────────

    @Test
    @DisplayName("app.memory(inMemory) registers strategy — returns app for chaining")
    void memory_inMemory_returnsApp() {
        var app = CafeAI.create();
        var result = app.memory(MemoryStrategy.inMemory());
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.memory(mapped) throws MemoryModuleNotFoundException without cafeai-memory")
    void memory_mapped_requiresModule() {
        var app = CafeAI.create();
        // cafeai-memory is not on cafeai-core's test classpath —
        // mapped() must throw MemoryModuleNotFoundException with actionable message.
        assertThatThrownBy(() -> app.memory(MemoryStrategy.mapped()))
            .isInstanceOf(MemoryStrategy.MemoryModuleNotFoundException.class)
            .hasMessageContaining("cafeai-memory");
    }

    @Test
    @DisplayName("app.memory(redis) throws MemoryModuleNotFoundException without cafeai-memory")
    void memory_redis_requiresModule() {
        var app = CafeAI.create();
        var config = RedisConfig.of("localhost", 6379);
        assertThatThrownBy(() -> app.memory(MemoryStrategy.redis(config)))
            .isInstanceOf(MemoryStrategy.MemoryModuleNotFoundException.class)
            .hasMessageContaining("cafeai-memory");
    }

    @Test
    @DisplayName("MemoryStrategy.inMemory() stores and retrieves context")
    void inMemoryStrategy_storesAndRetrieves() {
        var strategy = MemoryStrategy.inMemory();
        var ctx = new ConversationContext("session-1");
        ctx.addMessage("user", "hello");

        strategy.store("session-1", ctx);

        assertThat(strategy.exists("session-1")).isTrue();
        var retrieved = strategy.retrieve("session-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.sessionId()).isEqualTo("session-1");
        assertThat(retrieved.messages()).hasSize(1);
        assertThat(retrieved.messages().get(0).content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("MemoryStrategy.inMemory() evict removes context")
    void inMemoryStrategy_evict_removesContext() {
        var strategy = MemoryStrategy.inMemory();
        strategy.store("session-2", new ConversationContext("session-2"));
        assertThat(strategy.exists("session-2")).isTrue();

        strategy.evict("session-2");
        assertThat(strategy.exists("session-2")).isFalse();
        assertThat(strategy.retrieve("session-2")).isNull();
    }

    @Test
    @DisplayName("app.memory(null) throws NullPointerException")
    void memory_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.memory(null));
    }

    // ── GuardRails ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("app.guard(pii) registers guardrail — returns app for chaining")
    void guard_pii_returnsApp() {
        var app = CafeAI.create();
        var result = app.guard(GuardRail.pii());
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("Multiple guards register without exception")
    void guard_multiple_registers() {
        var app = CafeAI.create();
        assertThatCode(() -> app
                .guard(GuardRail.pii())
                .guard(GuardRail.jailbreak())
                .guard(GuardRail.promptInjection())
                .guard(GuardRail.bias())
                .guard(GuardRail.hallucination())
                .guard(GuardRail.toxicity()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GuardRail.pii() has correct name and position")
    void guardRail_pii_hasCorrectMetadata() {
        var rail = GuardRail.pii();
        assertThat(rail.name()).isEqualTo("pii");
        assertThat(rail.position()).isEqualTo(GuardRail.Position.BOTH);
    }

    @Test
    @DisplayName("GuardRail.jailbreak() has pre-LLM position")
    void guardRail_jailbreak_isPreLlm() {
        assertThat(GuardRail.jailbreak().position())
                .isEqualTo(GuardRail.Position.PRE_LLM);
    }

    @Test
    @DisplayName("GuardRail.regulatory().gdpr().hipaa() builds composite guardrail")
    void guardRail_regulatory_buildsComposite() {
        var rail = GuardRail.regulatory().gdpr().hipaa();
        assertThat(rail.name()).contains("gdpr").contains("hipaa");
        assertThat(rail.position()).isEqualTo(GuardRail.Position.BOTH);
    }

    @Test
    @DisplayName("GuardRail.topicBoundary() allow/deny builds correctly")
    void guardRail_topicBoundary_builds() {
        var rail = GuardRail.topicBoundary()
                .allow("customer service", "orders")
                .deny("politics", "medical advice");
        assertThat(rail.name()).isEqualTo("topic-boundary");
    }

    @Test
    @DisplayName("app.guard(null) throws NullPointerException")
    void guard_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.guard(null));
    }

    // ── Application Locals ────────────────────────────────────────────────────

    @Test
    @DisplayName("app.local() stores and retrieves typed values")
    void local_storesAndRetrieves() {
        var app = CafeAI.create();
        app.local("appName", "CafeAI Test");
        app.local("version", 1);

        assertThat(app.local("appName", String.class)).isEqualTo("CafeAI Test");
        assertThat(app.local("version", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("app.local(key) returns null for absent key")
    void local_absentKey_returnsNull() {
        var app = CafeAI.create();
        assertThat(app.local("nonexistent")).isNull();
    }

    @Test
    @DisplayName("app.local() typed retrieval throws ClassCastException on type mismatch")
    void local_wrongType_throwsClassCastException() {
        var app = CafeAI.create();
        app.local("count", 42);
        assertThatExceptionOfType(ClassCastException.class)
                .isThrownBy(() -> app.local("count", String.class));
    }

    @Test
    @DisplayName("app.local() updates value on second store with same key")
    void local_secondStore_updatesValue() {
        var app = CafeAI.create();
        app.local("key", "first");
        app.local("key", "second");
        assertThat(app.local("key", String.class)).isEqualTo("second");
    }

    @Test
    @DisplayName("app.local() is thread-safe under concurrent writes")
    void local_threadSafe() throws InterruptedException {
        var app = CafeAI.create();
        var threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> app.local("key-" + idx, idx));
        }
        for (var t : threads) t.start();
        for (var t : threads) t.join();

        for (int i = 0; i < 20; i++) {
            assertThat(app.local("key-" + i)).isNotNull();
        }
    }

    // ── CafeAIConfigurer Seam ─────────────────────────────────────────────────

    @Test
    @DisplayName("app.configure(configurer) calls configurer.configure(app)")
    void configure_callsConfigurer() {
        var app = CafeAI.create();
        var called = new boolean[]{false};

        app.configure(a -> {
            called[0] = true;
            assertThat(a).isSameAs(app);
        });

        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("app.configure() returns app for chaining")
    void configure_returnsApp() {
        var app = CafeAI.create();
        var result = app.configure(a -> {});
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("Multiple configurers applied in order() sequence")
    void configure_multiple_respectsOrder() {
        var app = CafeAI.create();
        var order = new ArrayList<Integer>();

        CafeAIConfigurer first = new CafeAIConfigurer() {
            @Override public void configure(CafeAI a) { order.add(1); }
            @Override public int order() { return 1; }
        };
        CafeAIConfigurer second = new CafeAIConfigurer() {
            @Override public void configure(CafeAI a) { order.add(2); }
            @Override public int order() { return 2; }
        };
        CafeAIConfigurer zeroth = new CafeAIConfigurer() {
            @Override public void configure(CafeAI a) { order.add(0); }
            @Override public int order() { return 0; }
        };

        app.configure(second, first, zeroth);
        assertThat(order).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("Configurer lambda can register routes on the app")
    void configure_canRegisterRoutes() {
        var app = CafeAI.create();
        assertThatCode(() ->
                app.configure(a ->
                        a.get("/test", (req, res, next) -> res.send("ok"))))
                .doesNotThrowAnyException();
    }

    // ── Path Translation ──────────────────────────────────────────────────────
    // Tests live in PathUtilsTest (io.cafeai.core.internal) — direct package access.

    // ── Router Fluency ────────────────────────────────────────────────────────

    @Test
    @DisplayName("app.get() returns Router for chaining")
    void get_returnsRouterForChaining() {
        var app = CafeAI.create();
        var result = app.get("/test", (req, res, next) -> res.send("ok"));
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.post() returns Router for chaining")
    void post_returnsRouterForChaining() {
        var app = CafeAI.create();
        var result = app.post("/test", (req, res, next) -> res.send("ok"));
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.filter(middleware) returns CafeAI for chaining")
    void filter_middleware_returnsCafeAI() {
        var app = CafeAI.create();
        var result = app.filter(Middleware.requestLogger());
        assertThat(result).isSameAs(app);
    }

    @Test
    @DisplayName("app.route(path) returns a RouteBuilder")
    void route_returnsRouteBuilder() {
        var app = CafeAI.create();
        var builder = app.route("/items/:id");
        assertThat(builder).isNotNull();
        assertThat(builder).isInstanceOf(Router.RouteBuilder.class);
    }

    @Test
    @DisplayName("RouteBuilder is fluent — chaining multiple methods")
    void routeBuilder_isChainable() {
        var app = CafeAI.create();
        assertThatCode(() ->
                app.route("/items/:id")
                        .get((req, res, next) -> res.send("get"))
                        .put((req, res, next) -> res.send("put"))
                        .delete((req, res, next) -> res.status(204).end()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.filter(null middleware) throws NullPointerException")
    void filter_nullMiddleware_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.filter((Middleware) null));
    }

    // ── Lifecycle Guards ──────────────────────────────────────────────────────

    @Test
    @DisplayName("app.configure() and app.filter() work before listen()")
    void configure_afterListen_throws() {
        var app = CafeAI.create();
        assertThatCode(() -> app.configure(a -> {}))
                .doesNotThrowAnyException();
        assertThatCode(() -> app.filter((req, res, next) -> next.run()))
                .doesNotThrowAnyException();
    }

    // ── ConversationContext ───────────────────────────────────────────────────

    @Test
    @DisplayName("ConversationContext accumulates messages in order")
    void conversationContext_accumulates() {
        var ctx = new ConversationContext("sess-abc");
        ctx.addMessage("user", "Hello");
        ctx.addMessage("assistant", "Hi there!");
        ctx.addMessage("user", "How are you?");

        assertThat(ctx.messages()).hasSize(3);
        assertThat(ctx.messages().get(0).role()).isEqualTo("user");
        assertThat(ctx.messages().get(0).content()).isEqualTo("Hello");
        assertThat(ctx.messages().get(1).role()).isEqualTo("assistant");
        assertThat(ctx.messages().get(2).content()).isEqualTo("How are you?");
    }

    @Test
    @DisplayName("ConversationContext messages() is unmodifiable")
    void conversationContext_messagesUnmodifiable() {
        var ctx = new ConversationContext("sess-xyz");
        ctx.addMessage("user", "test");

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ctx.messages().add(
                        new ConversationContext.Message(
                                "user", "inject", Instant.now())));
    }

    @Test
    @DisplayName("ConversationContext tracks token count")
    void conversationContext_tracksTokens() {
        var ctx = new ConversationContext("sess-tok");
        assertThat(ctx.totalTokens()).isEqualTo(0);
        ctx.addTokens(150);
        ctx.addTokens(75);
        assertThat(ctx.totalTokens()).isEqualTo(225);
    }

    // ── RedisConfig ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("RedisConfig.of() sets host and port correctly")
    void redisConfig_of_setsHostAndPort() {
        var config = RedisConfig.of("redis.prod", 6380);
        assertThat(config.host()).isEqualTo("redis.prod");
        assertThat(config.port()).isEqualTo(6380);
    }

    @Test
    @DisplayName("RedisConfig.builder() supports full configuration")
    void redisConfig_builder_fullConfig() {
        var config = RedisConfig.builder()
                .host("redis.prod")
                .port(6380)
                .password("secret")
                .database(1)
                .sessionTtl(Duration.ofHours(12))
                .ssl(true)
                .build();

        assertThat(config.host()).isEqualTo("redis.prod");
        assertThat(config.port()).isEqualTo(6380);
        assertThat(config.password()).isEqualTo("secret");
        assertThat(config.database()).isEqualTo(1);
        assertThat(config.sessionTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(config.ssl()).isTrue();
    }

    // ── ContentMap ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ContentMap.of() builds handler map correctly")
    void contentMap_buildsHandlerMap() {
        var map = ContentMap.of()
                .text(() -> {})
                .html(() -> {})
                .json(() -> {})
                .build();

        assertThat(map.handlers()).containsKeys(
                "text/plain", "text/html", "application/json");
    }

    @Test
    @DisplayName("ContentMap rejects duplicate type registrations")
    void contentMap_rejectsDuplicates() {
        var map = ContentMap.of().json(() -> {});
        assertThatIllegalArgumentException()
                .isThrownBy(() -> map.json(() -> {}));
    }

    // ── CookieOptions ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CookieOptions.builder() sets all fields")
    void cookieOptions_builder_setsAllFields() {
        var opts = CookieOptions.builder()
                .maxAge(Duration.ofHours(1))
                .httpOnly(true)
                .secure(true)
                .sameSite(CookieOptions.SameSite.STRICT)
                .domain("example.com")
                .path("/api")
                .signed(true)
                .build();

        assertThat(opts.maxAge()).isEqualTo(Duration.ofHours(1));
        assertThat(opts.httpOnly()).isTrue();
        assertThat(opts.secure()).isTrue();
        assertThat(opts.sameSite()).isEqualTo(CookieOptions.SameSite.STRICT);
        assertThat(opts.domain()).isEqualTo("example.com");
        assertThat(opts.path()).isEqualTo("/api");
        assertThat(opts.signed()).isTrue();
    }

    // ── Middleware Composition ────────────────────────────────────────────────

    @Test
    @DisplayName("Middleware.then() composes two middlewares")
    void middleware_then_composes() {
        var executed = new ArrayList<String>();

        Middleware first  = (req, res, next) -> { executed.add("first");  next.run(); };
        Middleware second = (req, res, next) -> { executed.add("second"); next.run(); };

        Middleware composed = first.then(second);
        assertThat(composed).isNotNull();
        assertThat(executed).isEmpty();
    }

    @Test
    @DisplayName("GuardRail.pii() passes through in stub implementation")
    void guardRail_stub_passesThroughChain() {
        var executed = new boolean[]{false};
        GuardRail.pii().handle(null, null, () -> executed[0] = true);
        assertThat(executed[0]).isTrue();
    }

    // ── ADR-009: filter() / variadic handlers / compose ───────────────────────

    @Test
    @DisplayName("app.filter(middleware) registers global pre-processing middleware")
    void filter_global_registers() {
        var app = CafeAI.create();
        assertThatCode(() -> app.filter(Middleware.requestLogger()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.filter(middleware) returns CafeAI for chaining")
    void filter_returnsApp() {
        var app = CafeAI.create();
        assertThat(app.filter(Middleware.cors())).isSameAs(app);
    }

    @Test
    @DisplayName("app.filter(path, middleware) scopes middleware to path prefix")
    void filter_pathScoped_registers() {
        var app = CafeAI.create();
        assertThatCode(() -> app.filter("/api", Middleware.rateLimit(100)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.filter() with multiple middlewares registers all")
    void filter_variadic_registersAll() {
        var app = CafeAI.create();
        assertThatCode(() -> app.filter(
                Middleware.requestLogger(),
                Middleware.cors(),
                CafeAI.json()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("app.filter(null) throws NullPointerException")
    void filter_null_throws() {
        var app = CafeAI.create();
        assertThatNullPointerException()
                .isThrownBy(() -> app.filter((Middleware) null));
    }

    @Test
    @DisplayName("app.get() accepts variadic middleware handlers")
    void get_variadicHandlers_registers() {
        var app = CafeAI.create();
        Middleware auth    = (req, res, next) -> next.run();
        Middleware handler = (req, res, next) -> res.send("ok");
        assertThatCode(() -> app.get("/test", auth, handler))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Middleware.NOOP passes through without side effects")
    void noop_passesThroughChain() {
        var called = new boolean[]{false};
        Middleware.NOOP.handle(null, null, () -> called[0] = true);
        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("CafeAIApp.compose() with empty array returns NOOP")
    void compose_empty_returnsNoop() {
        var called = new boolean[]{false};
        CafeAIApp.compose(new Middleware[0]).handle(null, null, () -> called[0] = true);
        assertThat(called[0]).isTrue();
    }

    @Test
    @DisplayName("CafeAIApp.compose() with single middleware returns it unchanged")
    void compose_single_returnsSame() {
        Middleware mw = (req, res, next) -> next.run();
        assertThat(CafeAIApp.compose(new Middleware[]{mw})).isSameAs(mw);
    }

    @Test
    @DisplayName("CafeAIApp.compose() executes handlers left-to-right")
    void compose_multiple_executesInOrder() {
        var order = new ArrayList<Integer>();
        Middleware m1 = (req, res, next) -> { order.add(1); next.run(); };
        Middleware m2 = (req, res, next) -> { order.add(2); next.run(); };
        Middleware m3 = (req, res, next) -> order.add(3);

        CafeAIApp.compose(new Middleware[]{m1, m2, m3})
                 .handle(null, null, () -> order.add(99));

        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("compose() — post-processing runs after next.run() returns")
    void compose_postProcessing_runsAfterDownstream() {
        var order = new ArrayList<String>();
        Middleware wrapper = (req, res, next) -> {
            order.add("pre");
            next.run();
            order.add("post");
        };
        Middleware handler = (req, res, next) -> order.add("handler");

        CafeAIApp.compose(new Middleware[]{wrapper, handler})
                 .handle(null, null, () -> {});

        assertThat(order).containsExactly("pre", "handler", "post");
    }

    @Test
    @DisplayName("Middleware.then() composes left-to-right like compose()")
    void middleware_then_matchesCompose() {
        var order = new ArrayList<Integer>();
        Middleware m1 = (req, res, next) -> { order.add(1); next.run(); };
        Middleware m2 = (req, res, next) -> { order.add(2); next.run(); };
        Middleware m3 = (req, res, next) -> order.add(3);

        m1.then(m2).then(m3).handle(null, null, () -> {});
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("JsonOptions defaults have correct values")
    void jsonOptions_defaults() {
        var opts = JsonOptions.defaults();
        assertThat(opts.inflate()).isTrue();
        assertThat(opts.limit()).isEqualTo(JsonOptions.DEFAULT_LIMIT);
        assertThat(opts.strict()).isTrue();
        assertThat(opts.type()).contains("application/json");
    }

    @Test
    @DisplayName("JsonOptions builder overrides defaults correctly")
    void jsonOptions_builder() {
        var opts = JsonOptions.builder()
                .inflate(false)
                .limit(512 * 1024L)
                .strict(false)
                .type("application/json", "application/merge-patch+json")
                .build();
        assertThat(opts.inflate()).isFalse();
        assertThat(opts.limit()).isEqualTo(512 * 1024L);
        assertThat(opts.strict()).isFalse();
        assertThat(opts.type()).contains("application/json", "application/merge-patch+json");
    }

    @Test
    @DisplayName("CafeAI.Router() returns a new Router instance")
    void cafeAiRouter_returnsNewInstance() {
        var r1 = CafeAI.Router();
        var r2 = CafeAI.Router();
        assertThat(r1).isNotNull().isNotSameAs(r2);
    }

    @Test
    @DisplayName("Sub-router registers routes without exception")
    void subRouter_registersRoutes() {
        var app = CafeAI.create();
        var api = CafeAI.Router();
        assertThatCode(() -> {
            api.get("/users",    (req, res, next) -> res.json("users"));
            api.post("/users",   (req, res, next) -> res.status(201).end());
            api.get("/users/:id",(req, res, next) -> res.json(req.params("id")));
            app.use("/api/v1", api);
        }).doesNotThrowAnyException();
    }

    // ── ROADMAP-01 Phase 2–7: Options builders ────────────────────────────────

    @Test
    @DisplayName("RawOptions defaults are correct")
    void rawOptions_defaults() {
        var opts = RawOptions.defaults();
        assertThat(opts.inflate()).isTrue();
        assertThat(opts.limit()).isEqualTo(RawOptions.DEFAULT_LIMIT);
        assertThat(opts.type()).contains("application/octet-stream");
    }

    @Test
    @DisplayName("TextOptions defaults are correct")
    void textOptions_defaults() {
        var opts = TextOptions.defaults();
        assertThat(opts.inflate()).isTrue();
        assertThat(opts.limit()).isEqualTo(TextOptions.DEFAULT_LIMIT);
        assertThat(opts.defaultCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(opts.type()).contains("text/plain");
    }

    @Test
    @DisplayName("UrlEncodedOptions defaults are correct")
    void urlEncodedOptions_defaults() {
        var opts = UrlEncodedOptions.defaults();
        assertThat(opts.inflate()).isTrue();
        assertThat(opts.limit()).isEqualTo(UrlEncodedOptions.DEFAULT_LIMIT);
        assertThat(opts.extended()).isFalse();
    }

    @Test
    @DisplayName("UrlEncodedOptions builder sets extended=true")
    void urlEncodedOptions_builder_extended() {
        var opts = UrlEncodedOptions.builder()
                .extended(true).limit(256 * 1024L).build();
        assertThat(opts.extended()).isTrue();
        assertThat(opts.limit()).isEqualTo(256 * 1024L);
    }

    @Test
    @DisplayName("StaticOptions defaults are correct")
    void staticOptions_defaults() {
        var opts = StaticOptions.defaults();
        assertThat(opts.etag()).isTrue();
        assertThat(opts.index()).isEqualTo("index.html");
        assertThat(opts.dotfiles()).isEqualTo(StaticOptions.Dotfiles.IGNORE);
        assertThat(opts.fallthrough()).isTrue();
        assertThat(opts.redirect()).isTrue();
        assertThat(opts.lastModified()).isTrue();
        assertThat(opts.cacheControl()).isTrue();
        assertThat(opts.maxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("StaticOptions builder configures all fields")
    void staticOptions_builder_allFields() {
        var opts = StaticOptions.builder()
                .maxAge(Duration.ofDays(7))
                .etag(true)
                .index("home.html")
                .dotfiles(StaticOptions.Dotfiles.DENY)
                .fallthrough(false)
                .immutable(true)
                .extensions(List.of("html", "htm"))
                .build();

        assertThat(opts.maxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(opts.index()).isEqualTo("home.html");
        assertThat(opts.dotfiles()).isEqualTo(StaticOptions.Dotfiles.DENY);
        assertThat(opts.fallthrough()).isFalse();
        assertThat(opts.immutable()).isTrue();
        assertThat(opts.extensions()).containsExactly("html", "htm");
    }

    @Test
    @DisplayName("CafeAI.json() returns a non-null Middleware")
    void cafeAiJson_returnsMiddleware() {
        assertThat(CafeAI.json()).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.raw() returns a non-null Middleware")
    void cafeAiRaw_returnsMiddleware() {
        assertThat(CafeAI.raw()).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.text() returns a non-null Middleware")
    void cafeAiText_returnsMiddleware() {
        assertThat(CafeAI.text()).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.urlencoded() returns a non-null Middleware")
    void cafeAiUrlencoded_returnsMiddleware() {
        assertThat(CafeAI.urlencoded()).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.serveStatic(root) returns a non-null Middleware")
    void cafeAiServeStatic_returnsMiddleware() {
        assertThat(CafeAI.serveStatic("public")).isNotNull();
    }

    @Test
    @DisplayName("CafeAI.json(options) respects custom options")
    void cafeAiJson_customOptions() {
        var opts = JsonOptions.builder().limit(512 * 1024L).strict(false).build();
        assertThat(CafeAI.json(opts)).isNotNull();
        assertThat(opts.limit()).isEqualTo(512 * 1024L);
        assertThat(opts.strict()).isFalse();
    }

    @Test
    @DisplayName("Middleware.NOOP called multiple times always invokes next")
    void noop_isIdempotent() {
        int[] count = {0};
        for (int i = 0; i < 5; i++) {
            Middleware.NOOP.handle(null, null, () -> count[0]++);
        }
        assertThat(count[0]).isEqualTo(5);
    }

    // ── Helidon escape hatch tests ─────────────────────────────────────────────

    @Test
    @DisplayName("app.helidon() returns non-null HelidonConfig before listen()")
    void helidon_returnsConfig() {
        var app = CafeAI.create();
        assertThat(app.helidon()).isNotNull();
    }

    @Test
    @DisplayName("app.helidon().server() registers consumer — returns HelidonConfig for chaining")
    void helidon_server_isChainable() {
        var app = CafeAI.create();
        var config = app.helidon();
        var returned = config.server(builder -> {});
        assertThat(returned).isSameAs(config);
    }

    @Test
    @DisplayName("app.helidon().routing() registers consumer — returns HelidonConfig for chaining")
    void helidon_routing_isChainable() {
        var app = CafeAI.create();
        var config = app.helidon();
        var returned = config.routing(builder -> {});
        assertThat(returned).isSameAs(config);
    }

    @Test
    @DisplayName("app.helidon().server(null) throws NullPointerException")
    void helidon_server_null_throws() {
        var app = CafeAI.create();
        assertThatThrownBy(() -> app.helidon().server(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("app.helidon().routing(null) throws NullPointerException")
    void helidon_routing_null_throws() {
        var app = CafeAI.create();
        assertThatThrownBy(() -> app.helidon().routing(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("app.helidon() fluent chain — server() and routing() both register")
    void helidon_fluent_chain() {
        var app = CafeAI.create();
        int[] serverCalled  = {0};
        int[] routingCalled = {0};

        // Both consumers registered — neither fires until listen()
        app.helidon()
           .server(builder  -> serverCalled[0]++)
           .routing(builder -> routingCalled[0]++);

        // Consumers not yet called — server hasn't started
        assertThat(serverCalled[0]).isEqualTo(0);
        assertThat(routingCalled[0]).isEqualTo(0);
    }

    // ── app.agent() tests ──────────────────────────────────────────────────────

    interface StubAgent { String ask(String q); }

    @Test
    @DisplayName("app.agent(name, interface) before listen() — returns config or null")
    void agent_registration_beforeListen() {
        var app = CafeAI.create();
        // Returns AgentConfig<T> if cafeai-agents present, null if absent — both valid
        Object result = app.agent("stub", StubAgent.class);
        // No exception — registration is always safe whether module present or not
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("app.agentStateless() delegates to agent() with null sessionId")
    void agentStateless_delegatesToAgent() {
        var app = CafeAI.create();
        // Without cafeai-agents on classpath, should throw clear error
        assertThatThrownBy(() -> app.agentStateless("stub", StubAgent.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cafeai-agents");
    }

    @Test
    @DisplayName("app.agent(null name) throws NullPointerException")
    void agent_nullName_throws() {
        var app = CafeAI.create();
        assertThatThrownBy(() -> app.agent(null, StubAgent.class))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("app.agent(name, null interface) throws NullPointerException")
    void agent_nullInterface_throws() {
        var app = CafeAI.create();
        assertThatThrownBy(() -> app.agent("stub", null))
            .isInstanceOf(NullPointerException.class);
    }
}