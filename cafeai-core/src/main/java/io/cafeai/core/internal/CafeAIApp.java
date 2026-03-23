package io.cafeai.core.internal;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.ModelRouter;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.*;
import io.cafeai.core.spi.CafeAIConfigurer;
import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete implementation of {@link CafeAI}.
 *
 * <p><strong>Internal — do not reference directly. Always use {@link CafeAI#create()}.</strong>
 * Public only because Java package-private cannot cross package boundaries without JPMS.
 * JPMS module encapsulation (ROADMAP-08 Phase 3) will enforce this properly.
 *
 * <h2>Internal wiring model (ADR-009)</h2>
 * <ul>
 *   <li>{@code app.filter(mw)} → Helidon {@code addFilter()} — runs before route dispatch</li>
 *   <li>{@code app.get(path, mw...)} → Helidon {@code builder.get()} with composed handler</li>
 *   <li>{@code app.use(path, router)} → sub-router expanded inline at mount prefix</li>
 * </ul>
 * Helidon's {@code Filter} / {@code FilterChain} are private implementation details.
 * No public API surface references them.
 */
public final class CafeAIApp implements CafeAI {

    private static final Logger log = LoggerFactory.getLogger(CafeAIApp.class);

    // ── State ─────────────────────────────────────────────────────────────────

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final Map<String, Object>   locals       = new ConcurrentHashMap<>();
    private final List<FilterEntry>     filterEntries = new ArrayList<>();
    private final List<RouteEntry>      routes        = new ArrayList<>();
    private final CafeAIRegistryImpl    registry      = new CafeAIRegistryImpl();

    // AI state
    private AiProvider      aiProvider;
    private ModelRouter     modelRouter;
    private String          systemPrompt;
    private MemoryStrategy  memoryStrategy;
    private final List<GuardRail>         guardRails = new ArrayList<>();
    private final Map<String, String>     templates  = new ConcurrentHashMap<>();

    private WebServer server;

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Internal — call {@link CafeAI#create()} instead. */
    public static CafeAI newInstance() {
        var app = new CafeAIApp();
        app.discoverModules();
        app.discoverConfigurers();
        return app;
    }

    private CafeAIApp() {}

    // ── Service Loader Discovery ──────────────────────────────────────────────

    private void discoverModules() {
        ServiceLoader.load(CafeAIModule.class).forEach(module -> {
            log.info("CafeAI module loaded: {} v{}", module.name(), module.version());
            module.register(registry);
        });
    }

    private void discoverConfigurers() {
        ServiceLoader.load(CafeAIConfigurer.class)
            .stream()
            .map(ServiceLoader.Provider::get)
            .sorted(Comparator.comparingInt(CafeAIConfigurer::order))
            .forEach(c -> c.configure(this));
    }

    // ── CafeAIConfigurer ──────────────────────────────────────────────────────

    @Override
    public CafeAI configure(CafeAIConfigurer configurer) {
        assertNotStarted("configure()");
        configurer.configure(this);
        return this;
    }

    @Override
    public CafeAI configure(CafeAIConfigurer... configurers) {
        assertNotStarted("configure()");
        Arrays.stream(configurers)
              .sorted(Comparator.comparingInt(CafeAIConfigurer::order))
              .forEach(c -> c.configure(this));
        return this;
    }

    // ── AI Infrastructure ─────────────────────────────────────────────────────

    @Override
    public CafeAI ai(AiProvider provider) {
        assertNotStarted("ai()");
        this.aiProvider = Objects.requireNonNull(provider, "AiProvider must not be null");
        log.info("AI provider registered: {} ({})", provider.name(), provider.modelId());
        return this;
    }

    @Override
    public CafeAI ai(ModelRouter router) {
        assertNotStarted("ai()");
        this.modelRouter = Objects.requireNonNull(router, "ModelRouter must not be null");
        log.info("Model router registered: {}", router.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI system(String systemPrompt) {
        assertNotStarted("system()");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "System prompt must not be null");
        return this;
    }

    @Override
    public CafeAI template(String name, String template) {
        assertNotStarted("template()");
        Objects.requireNonNull(name,     "Template name must not be null");
        Objects.requireNonNull(template, "Template body must not be null");
        templates.put(name, template);
        return this;
    }

    @Override
    public CafeAI memory(MemoryStrategy strategy) {
        assertNotStarted("memory()");
        this.memoryStrategy = Objects.requireNonNull(strategy, "MemoryStrategy must not be null");
        log.info("Memory strategy registered: {}", strategy.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI guard(GuardRail guardRail) {
        assertNotStarted("guard()");
        Objects.requireNonNull(guardRail, "GuardRail must not be null");
        guardRails.add(guardRail);
        log.debug("GuardRail registered: {}", guardRail.name());
        return this;
    }

    // ── Application Locals ────────────────────────────────────────────────────

    @Override
    public CafeAI local(String key, Object value) {
        Objects.requireNonNull(key, "Local key must not be null");
        locals.put(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T local(String key, Class<T> type) {
        Object value = locals.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    @Override
    public Object local(String key) {
        return locals.get(key);
    }

    // ── Filter Registration (Cross-Cutting Pre-Processing) ────────────────────

    @Override
    public CafeAI filter(Middleware... middlewares) {
        assertNotStarted("filter()");
        for (Middleware mw : middlewares) {
            Objects.requireNonNull(mw, "Filter middleware must not be null");
            filterEntries.add(new FilterEntry(null, mw));
        }
        return this;
    }

    @Override
    public CafeAI filter(String path, Middleware... middlewares) {
        assertNotStarted("filter()");
        Objects.requireNonNull(path, "Filter path must not be null");
        for (Middleware mw : middlewares) {
            Objects.requireNonNull(mw, "Filter middleware must not be null");
            filterEntries.add(new FilterEntry(path, mw));
        }
        return this;
    }

    // ── HTTP Routing ──────────────────────────────────────────────────────────

    @Override
    public Router get(String path, Middleware... handlers) {
        routes.add(new RouteEntry("GET", path, compose(handlers)));
        return this;
    }

    @Override
    public Router post(String path, Middleware... handlers) {
        routes.add(new RouteEntry("POST", path, compose(handlers)));
        return this;
    }

    @Override
    public Router put(String path, Middleware... handlers) {
        routes.add(new RouteEntry("PUT", path, compose(handlers)));
        return this;
    }

    @Override
    public Router patch(String path, Middleware... handlers) {
        routes.add(new RouteEntry("PATCH", path, compose(handlers)));
        return this;
    }

    @Override
    public Router delete(String path, Middleware... handlers) {
        routes.add(new RouteEntry("DELETE", path, compose(handlers)));
        return this;
    }

    @Override
    public Router head(String path, Middleware... handlers) {
        routes.add(new RouteEntry("HEAD", path, compose(handlers)));
        return this;
    }

    @Override
    public Router options(String path, Middleware... handlers) {
        routes.add(new RouteEntry("OPTIONS", path, compose(handlers)));
        return this;
    }

    @Override
    public Router all(String path, Middleware... handlers) {
        Middleware composed = compose(handlers);
        for (String method : List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS")) {
            routes.add(new RouteEntry(method, path, composed));
        }
        return this;
    }

    @Override
    public Router use(Middleware... middlewares) {
        // Inline route-pipeline middleware — registered as global filters for now.
        // Full inline-route scoping in ROADMAP-05.
        for (Middleware mw : middlewares) {
            Objects.requireNonNull(mw, "Middleware must not be null");
            filterEntries.add(new FilterEntry(null, mw));
        }
        return this;
    }

    @Override
    public Router use(String path, Router subRouter) {
        Objects.requireNonNull(path,      "Path must not be null");
        Objects.requireNonNull(subRouter, "Sub-router must not be null");
        routes.add(new RouteEntry("_SUBROUTER_", path, Middleware.NOOP, subRouter));
        return this;
    }

    @Override
    public Router param(String name, ParamCallback callback) {
        routes.add(new RouteEntry("_PARAM_", name,
            (req, res, next) -> callback.handle(req, res, next, req.params(name))));
        return this;
    }

    @Override
    public RouteBuilder route(String path) {
        return new RouteBuilderImpl(path, this);
    }

    // ── Server Lifecycle ──────────────────────────────────────────────────────

    @Override
    public void listen(int port) {
        listen(port, null);
    }

    @Override
    public void listen(int port, Runnable onStart) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(
                "CafeAI server is already running. Create a new instance with CafeAI.create().");
        }

        log.info("☕ CafeAI starting on port {}...", port);

        var routing = buildRouting();

        var routingBuilder = buildRouting();

        server = WebServer.builder()
            .port(port)
            .addRouting(routingBuilder)
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("☕ CafeAI shutting down gracefully...");
            stop();
        }, "cafeai-shutdown"));

        server.start();
        running.set(true);

        log.info("☕ CafeAI running on http://localhost:{}", server.port());
        log.info("   Virtual threads:    active (Helidon SE default executor)");
        if (!filterEntries.isEmpty())
            log.info("   Filters:            {}", filterEntries.size());
        if (aiProvider != null)
            log.info("   AI provider:        {} ({})", aiProvider.name(), aiProvider.modelId());
        if (memoryStrategy != null)
            log.info("   Memory strategy:    {}", memoryStrategy.getClass().getSimpleName());
        if (!guardRails.isEmpty())
            log.info("   Guardrails:         {}", guardRails.size());

        if (onStart != null) onStart.run();
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (server != null) {
                server.stop();
                log.info("☕ CafeAI stopped.");
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ── Helidon Routing Builder ───────────────────────────────────────────────

    /**
     * Builds the Helidon {@link HttpRouting.Builder} from registered filters and routes.
     * Returns the builder — not the built instance — so {@code WebServer.addRouting(Builder)}
     * can accept it directly without requiring a cast.
     *
     * <p>Mapping (all Helidon types are private implementation details):
     * <ul>
     *   <li>{@code app.filter(mw)} → {@code builder.addFilter()} — pre-dispatch,
     *       every request, {@code next.run()} maps to {@code chain.proceed()}</li>
     *   <li>{@code app.filter(path, mw)} → {@code builder.addFilter()} with internal
     *       path-prefix guard — skips to {@code chain.proceed()} when path doesn't match</li>
     *   <li>{@code app.get(path, mw...)} → {@code builder.get(path, handler)} where
     *       handler wraps the pre-composed middleware chain</li>
     * </ul>
     */
    private HttpRouting.Builder buildRouting() {
        var builder = HttpRouting.builder();

        // Register filter-scope middleware (pre-dispatch, own call frame)
        for (var entry : filterEntries) {
            if (entry.path() == null) {
                builder.addFilter(toHelidonFilter(entry.middleware()));
            } else {
                builder.addFilter(toPathScopedFilter(entry.path(), entry.middleware()));
            }
        }

        // Register route handlers (post-dispatch, route-matched)
        registerRoutes(builder, routes, "");

        return builder;
    }

    /**
     * Recursively registers routes, expanding sub-routers at their mount prefix.
     */
    private void registerRoutes(HttpRouting.Builder builder,
                                 List<RouteEntry> routeList,
                                 String mountPrefix) {
        for (var entry : routeList) {
            if (entry.method().equals("_PARAM_")) continue;

            if (entry.method().equals("_SUBROUTER_")
                    && entry.subRouter() instanceof SubRouter sub) {
                String prefix = mountPrefix + entry.path();
                // Sub-router filter-scope middleware at the combined prefix
                for (var mw : sub.middlewares) {
                    String p = mw.path() == null ? prefix : prefix + mw.path();
                    builder.addFilter(toPathScopedFilter(p, mw.middleware()));
                }
                // Expand sub-router routes recursively
                List<RouteEntry> subRoutes = sub.routes.stream()
                    .map(r -> new RouteEntry(r.method(), r.path(), r.handler(), r.nestedRouter()))
                    .toList();
                registerRoutes(builder, subRoutes, prefix);
                continue;
            }

            if (entry.method().equals("_SUBROUTER_")) continue;

            String fullPath = toHelidonPath(mountPrefix + entry.path());
            Handler handler = toHelidonHandler(entry.handler());

            switch (entry.method()) {
                case "GET"     -> builder.get(fullPath,     handler);
                case "POST"    -> builder.post(fullPath,    handler);
                case "PUT"     -> builder.put(fullPath,     handler);
                case "PATCH"   -> builder.patch(fullPath,   handler);
                case "DELETE"  -> builder.delete(fullPath,  handler);
                case "HEAD"    -> builder.head(fullPath,    handler);
                case "OPTIONS" -> builder.options(fullPath, handler);
            }
        }
    }

    // ── Helidon Adapter Helpers (private — no Helidon types leak out) ─────────

    /**
     * Wraps a {@link Middleware} as a Helidon {@link Filter}.
     *
     * <p>{@code next.run()} in the middleware maps to {@code chain.proceed()} in Helidon,
     * advancing to the next filter or to route dispatch. Code after {@code next.run()}
     * executes after the entire downstream chain completes — natural post-processing on
     * virtual threads (ADR-009 §3).
     */
    private Filter toHelidonFilter(Middleware middleware) {
        return (chain, routingReq, routingRes) -> {
            var req = new HelidonRequest((ServerRequest) routingReq, this);
            var res = new HelidonResponse((ServerResponse) routingRes);
            middleware.handle(req, res, chain::proceed);
        };
    }

    /**
     * Wraps a path-scoped {@link Middleware} as a Helidon {@link Filter}.
     * Skips to {@code chain.proceed()} when the request path doesn't start with the prefix.
     */
    private Filter toPathScopedFilter(String pathPrefix, Middleware middleware) {
        return (chain, routingReq, routingRes) -> {
            if (routingReq.path().path().startsWith(pathPrefix)) {
                var req = new HelidonRequest((ServerRequest) routingReq, this);
                var res = new HelidonResponse((ServerResponse) routingRes);
                middleware.handle(req, res, chain::proceed);
            } else {
                chain.proceed();
            }
        };
    }

    /**
     * Wraps a {@link Middleware} as a Helidon route {@link Handler}.
     * Route handlers are already composed by {@link #compose(Middleware[])} before
     * reaching here — this is a straight adaption of the single composed middleware.
     */
    private Handler toHelidonHandler(Middleware middleware) {
        return (helidonReq, helidonRes) -> {
            var req = new HelidonRequest(helidonReq, this);
            var res = new HelidonResponse(helidonRes);
            // Route handlers don't need a meaningful next — end of the chain.
            middleware.handle(req, res, () -> {});
        };
    }

    // ── Path Translation ──────────────────────────────────────────────────────

    /** Delegates to {@link PathUtils#toHelidonPath(String)}. */
    static String toHelidonPath(String expressPath) {
        return PathUtils.toHelidonPath(expressPath);
    }

    // ── Middleware Composition ────────────────────────────────────────────────

    /**
     * Composes a varargs array of {@link Middleware} left-to-right into a single
     * {@link Middleware} using {@link Middleware#then(Middleware)}.
     *
     * <p>{@code compose(mw1, mw2, mw3)} produces {@code mw1.then(mw2.then(mw3))}.
     * {@code mw1} receives the composed {@code mw2 → mw3} as its {@code next}.
     * Calling {@code next.run()} in {@code mw1} executes {@code mw2}, and so on.
     *
     * <ul>
     *   <li>Single-element array → returns that element unchanged</li>
     *   <li>Empty array → returns {@link Middleware#NOOP}</li>
     * </ul>
     */
    public static Middleware compose(Middleware[] handlers) {
        if (handlers == null || handlers.length == 0) return Middleware.NOOP;
        if (handlers.length == 1) return handlers[0];
        Middleware composed = handlers[handlers.length - 1];
        for (int i = handlers.length - 2; i >= 0; i--) {
            composed = handlers[i].then(composed);
        }
        return composed;
    }

    // ── Lifecycle Guards ──────────────────────────────────────────────────────

    private void assertNotStarted(String method) {
        if (started.get()) {
            throw new IllegalStateException(
                method + " must be called before app.listen(). " +
                "Application configuration is locked once the server starts.");
        }
    }

    // ── Internal Record Types ─────────────────────────────────────────────────

    /** A registered filter — path is null for global scope. */
    private record FilterEntry(String path, Middleware middleware) {}

    /** A registered route. handler is always a pre-composed Middleware. */
    private record RouteEntry(
        String method,
        String path,
        Middleware handler,
        Router subRouter
    ) {
        RouteEntry(String method, String path, Middleware handler) {
            this(method, path, handler, null);
        }
    }
}
