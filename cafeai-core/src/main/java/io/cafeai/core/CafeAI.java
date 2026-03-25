package io.cafeai.core;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.ModelRouter;
import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.Template;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.internal.BuiltInMiddleware;
import io.cafeai.core.internal.CafeAIApp;
import io.cafeai.core.internal.SubRouter;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Router;
import io.cafeai.core.spi.CafeAIConfigurer;
import java.util.Map;
import io.cafeai.core.StaticOptions;
import io.cafeai.core.UrlEncodedOptions;

/**
 * CafeAI — The main application interface.
 *
 * <p>The entry point of every CafeAI application. Mirrors Express.js
 * pound-for-pound for HTTP routing and middleware, while introducing
 * a rich vocabulary of AI-native primitives.
 *
 * <p>Follows the three-tier composition model (ADR-006):
 * <ol>
 *   <li>Tier 1 — Application wiring (CDI, Service Loaders, manual)</li>
 *   <li>Tier 2 — CafeAI bootstrap (this interface)</li>
 *   <li>Tier 3 — Request handling (Router, req, res, Middleware)</li>
 * </ol>
 *
 * <p>Quick start — zero DI:
 * <pre>{@code
 *   var app = CafeAI.create();
 *
 *   app.ai(OpenAI.gpt4o());
 *   app.memory(MemoryStrategy.mapped());
 *   app.system("You are a helpful assistant...");
 *
 *   app.filter(Middleware.json());
 *   app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));
 *   app.post("/chat", (req, res, next) -> res.send("Hello CafeAI"));
 *
 *   app.listen(8080);
 * }</pre>
 *
 * <p>Quick start — with CDI ({@code cafeai-cdi} on classpath):
 * <pre>{@code
 *   @ApplicationScoped
 *   public class AppConfig implements CafeAIConfigurer {
 *       @Inject UserService userService;
 *
 *       public void configure(CafeAI app) {
 *           app.ai(OpenAI.gpt4o());
 *           app.get("/users/:id", (req, res, next) ->
 *               res.json(userService.find(req.params("id"))));
 *           app.listen(8080);
 *       }
 *   }
 * }</pre>
 */
public interface CafeAI extends Router {

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a new CafeAI application instance.
     *
     * <p>On creation, CafeAI automatically:
     * <ol>
     *   <li>Discovers all {@link io.cafeai.core.spi.CafeAIModule} implementations
     *       via {@link java.util.ServiceLoader} and registers their capabilities</li>
     *   <li>Discovers all {@link CafeAIConfigurer} implementations via
     *       {@link java.util.ServiceLoader} and applies them in {@code order()} sequence</li>
     * </ol>
     *
     * <p>Mirrors: {@code const app = express()}
     */
    static CafeAI create() {
        return CafeAIApp.newInstance();
    }

    // ── Built-in Middleware Factories ─────────────────────────────────────────

    /**
     * JSON body parsing middleware. Parses {@code application/json} request
     * bodies and populates {@code req.body()}.
     *
     * <p>Bodies exceeding 100 KB are rejected with HTTP 413.
     * Missing or non-JSON Content-Type passes through without parsing.
     *
     * <p>Mirrors Express: {@code express.json()}
     *
     * <pre>{@code
     *   app.filter(CafeAI.json());
     *   app.post("/echo", (req, res, next) -> res.json(req.body()));
     * }</pre>
     */
    static Middleware json() {
        return BuiltInMiddleware.jsonBody(JsonOptions.defaults());
    }

    /**
     * JSON body parsing middleware with custom options.
     * Mirrors Express: {@code express.json(options)}
     */
    static Middleware json(JsonOptions options) {
        return BuiltInMiddleware.jsonBody(options);
    }

    /**
     * Raw byte body parsing middleware. Parses bodies into {@code byte[]}
     * accessible via {@code req.bodyBytes()}.
     *
     * <p>Mirrors Express: {@code express.raw()}
     */
    static Middleware raw() {
        return BuiltInMiddleware.rawBody(RawOptions.defaults());
    }

    /**
     * Raw body parsing middleware with custom options.
     * Mirrors Express: {@code express.raw(options)}
     */
    static Middleware raw(RawOptions options) {
        return BuiltInMiddleware.rawBody(options);
    }

    /**
     * Plain text body parsing middleware. Parses bodies into {@code String}
     * accessible via {@code req.bodyText()}.
     *
     * <p>Mirrors Express: {@code express.text()}
     */
    static Middleware text() {
        return BuiltInMiddleware.textBody(TextOptions.defaults());
    }

    /**
     * Text body parsing middleware with custom options.
     * Mirrors Express: {@code express.text(options)}
     */
    static Middleware text(TextOptions options) {
        return BuiltInMiddleware.textBody(options);
    }

    /**
     * Creates a new standalone {@link io.cafeai.core.routing.Router}.
     * Mount it on the application via {@code app.use(path, router)}.
     *
     * <p>Mirrors Express: {@code express.Router()}
     *
     * <pre>{@code
     *   var api = CafeAI.Router();
     *   api.get("/users", (req, res, next) -> res.json(users));
     *   app.use("/api/v1", api);
     * }</pre>
     */
    static Router Router() {
        return new SubRouter();
    }

    /**
     * URL-encoded form body parsing middleware. Parses
     * {@code application/x-www-form-urlencoded} bodies into {@code req.body()}.
     *
     * <p>Mirrors Express: {@code express.urlencoded()}
     *
     * <pre>{@code
     *   app.filter(CafeAI.urlencoded());
     *   app.post("/login", (req, res, next) -> {
     *       String user = req.body("username");
     *       String pass = req.body("password");
     *   });
     * }</pre>
     */
    static Middleware urlencoded() {
        return BuiltInMiddleware.urlEncodedBody(UrlEncodedOptions.defaults());
    }

    /**
     * URL-encoded form body parsing with custom options.
     * Mirrors Express: {@code express.urlencoded(options)}
     */
    static Middleware urlencoded(UrlEncodedOptions options) {
        return BuiltInMiddleware.urlEncodedBody(options);
    }

    /**
     * Multipart body parser. Parses {@code multipart/form-data} bodies.
     *
     * <p>Uploaded files are accessible via {@code req.file(fieldName)} and
     * {@code req.files(fieldName)}. Regular text fields in the multipart form
     * are accessible via {@code req.body(key)}.
     *
     * <p>Default maximum size per part: 10 MB.
     *
     * <pre>{@code
     *   app.filter(CafeAI.multipart());
     *
     *   app.post("/upload", (req, res, next) -> {
     *       UploadedFile doc  = req.file("document");
     *       String       note = req.body("note");
     *       doc.saveToDirectory(Path.of("/uploads"));
     *       res.status(201).json(Map.of("name", doc.originalName(), "note", note));
     *   });
     * }</pre>
     */
    static Middleware multipart() {
        return BuiltInMiddleware.multipart();
    }

    /**
     * Multipart body parser with a custom per-part size limit.
     *
     * @param maxPartBytes maximum bytes per part (file or field)
     */
    static Middleware multipart(long maxPartBytes) {
        return BuiltInMiddleware.multipartBody(maxPartBytes);
    }

    /**
     * Static file serving middleware. Serves files from the given root directory.
     *
     * <p>Named {@code serveStatic} because {@code static} is a reserved keyword in Java.
     * Mirrors Express: {@code express.static(root)}
     *
     * <pre>{@code
     *   app.use(CafeAI.serveStatic("public"));
     *   // GET /index.html  → serves public/index.html
     *   // GET /            → serves public/index.html (index fallback)
     *   // GET /.env        → 404 (dotfiles ignored by default)
     * }</pre>
     */
    static Middleware serveStatic(String root) {
        return BuiltInMiddleware.serveStatic(root, StaticOptions.defaults());
    }

    /**
     * Static file serving middleware with custom options.
     * Mirrors Express: {@code express.static(root, options)}
     */
    static Middleware serveStatic(String root, StaticOptions options) {
        return BuiltInMiddleware.serveStatic(root, options);
    }

    // ── Error Handling (ROADMAP-06 Phase 2) ──────────────────────────────────

    /**
     * Registers an error-handling middleware.
     * Invoked when any middleware or handler calls {@link io.cafeai.core.middleware.Next#fail(Throwable)},
     * or when an uncaught exception propagates up the middleware chain.
     *
     * <p>Mirrors Express: {@code app.use(function(err, req, res, next))}
     * but uses an explicit {@link io.cafeai.core.middleware.ErrorMiddleware} type
     * instead of relying on function arity.
     *
     * <p>Multiple error handlers are chained in registration order.
     * The first registered handler has first opportunity to respond.
     * Call {@code next.run()} to pass to the next registered error handler.
     *
     * <pre>{@code
     *   app.onError((err, req, res, next) -> {
     *       log.error("Request error: {}", err.getMessage(), err);
     *       if (!res.headersSent()) {
     *           res.status(500).json(Map.of("error", "Internal server error"));
     *       }
     *   });
     * }</pre>
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI onError(io.cafeai.core.middleware.ErrorMiddleware handler);

    // ── Filter Registration (Cross-Cutting Pre-Processing) ────────────────────

    /**
     * Registers global cross-cutting middleware that runs before all route dispatch.
     *
     * <p>Use {@code filter()} for concerns that must execute unconditionally for every
     * request before any route handler sees it: body parsing, authentication, rate
     * limiting, logging, CORS, PII scrubbing, guardrails.
     *
     * <p>Filter middleware executes in its own call frame, separate from route
     * dispatch. Calling {@code next.run()} inside a filter advances the filter chain;
     * not calling it short-circuits all further processing.
     *
     * <p>Post-processing works naturally — any code after {@code next.run()} executes
     * after the entire downstream chain completes, because {@code next.run()} blocks
     * on the virtual thread until the response is committed (ADR-009 §3).
     *
     * <pre>{@code
     *   app.filter(Middleware.requestLogger());  // runs for every request
     *   app.filter(CafeAI.json());              // parses body before routes read it
     *   app.filter(GuardRail.pii());            // scrubs PII before LLM sees prompts
     *
     *   // Post-processing — code after next.run() runs after response is committed
     *   app.filter((req, res, next) -> {
     *       long start = System.nanoTime();
     *       next.run();  // blocks — entire chain executes here
     *       log.info("{}ms", (System.nanoTime() - start) / 1_000_000);
     *   });
     * }</pre>
     *
     * <p>For cross-cutting concerns that should only apply to a path prefix,
     * use {@link #filter(String, io.cafeai.core.middleware.Middleware...)}.
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI filter(Middleware... middlewares);

    /**
     * Registers path-scoped cross-cutting middleware.
     * Runs before route dispatch, but only for requests whose path starts with
     * the given prefix.
     *
     * <pre>{@code
     *   app.filter("/api", Middleware.auth());         // auth every /api/** request
     *   app.filter("/api", Middleware.rateLimit(100)); // rate-limit /api/** only
     * }</pre>
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI filter(String path, Middleware... middlewares);

    // ── Configurer Registration ───────────────────────────────────────────────

    /**
     * Explicitly registers a {@link CafeAIConfigurer} — the DI integration seam.
     *
     * <p>Service Loader-discovered configurers are applied automatically in
     * {@link #create()}. Use this method for direct manual wiring.
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI configure(CafeAIConfigurer configurer);

    /**
     * Registers multiple configurers, applied in the order provided.
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI configure(CafeAIConfigurer... configurers);

    // ── AI Infrastructure ─────────────────────────────────────────────────────

    /**
     * Registers the LLM provider. Declares: "this application is AI-powered."
     *
     * <pre>{@code
     *   app.ai(OpenAI.gpt4o());
     *   app.ai(Anthropic.claude35Sonnet());
     *   app.ai(Ollama.llama3());
     * }</pre>
     */
    CafeAI ai(AiProvider provider);

    /**
     * Registers a smart model router — dispatches to cheap vs expensive models
     * based on query complexity. Reduces cost without sacrificing quality.
     *
     * <pre>{@code
     *   app.ai(ModelRouter.smart()
     *       .simple(OpenAI.gpt4oMini())
     *       .complex(OpenAI.gpt4o()));
     * }</pre>
     */
    CafeAI ai(ModelRouter router);

    // ── Prompt Primitives ─────────────────────────────────────────────────────

    /**
     * Sets the system prompt — the AI's persona, rules, and constraints.
     * Automatically injected as the first message in every LLM call.
     * Calling this multiple times: last call wins.
     */
    CafeAI system(String systemPrompt);

    /**
     * Registers a named, reusable prompt template with {@code {{variable}}} interpolation.
     *
     * <pre>{@code
     *   app.template("classify", "Classify the following: {{input}}");
     * }</pre>
     */
    // ── LLM Invocation (ROADMAP-07 Phase 1) ──────────────────────────────────

    /**
     * Creates a prompt request for the registered LLM provider.
     *
     * <p>The returned {@link io.cafeai.core.ai.PromptRequest} is a fluent builder.
     * Nothing is sent to the LLM until {@code .call()} is invoked.
     *
     * <pre>{@code
     *   // Simple synchronous call
     *   PromptResponse response = app.prompt("What is the capital of France?").call();
     *   res.json(Map.of("answer", response.text()));
     *
     *   // Session-aware — includes conversation history
     *   PromptResponse response = app.prompt("Continue our conversation")
     *       .session(req.header("X-Session-Id"))
     *       .call();
     *
     *   // System prompt override for this call only
     *   PromptResponse response = app.prompt("Translate: " + text)
     *       .system("You are a professional French translator.")
     *       .call();
     * }</pre>
     *
     * @throws IllegalStateException if no AI provider has been registered via {@link #ai(io.cafeai.core.ai.AiProvider)}
     */
    PromptRequest prompt(String message);

    /**
     * Creates a prompt request by rendering a named template with the given variables.
     *
     * <pre>{@code
     *   app.template("classify",
     *       "Classify into one of: {{categories}}.\nMessage: {{message}}");
     *
     *   // In handler:
     *   PromptResponse result = app.prompt("classify",
     *       Map.of("categories", "billing, shipping", "message", userInput))
     *       .call();
     * }</pre>
     *
     * @throws IllegalArgumentException if no template with the given name is registered
     * @throws Template.TemplateException if a required variable is missing
     */
    PromptRequest prompt(String templateName, Map<String, Object> vars);

    /**
     * Retrieves a registered prompt template by name.
     *
     * <pre>{@code
     *   Template t = app.template("classify");
     *   String rendered = t.render(Map.of("categories", "billing", "message", input));
     *   PromptResponse response = app.prompt(rendered).call();
     * }</pre>
     *
     * @throws IllegalArgumentException if no template with the given name is registered
     */
    Template template(String name);

    /**
     * Registers a named prompt template.
     * Mirrors the existing {@link #template(String, String)} registration method.
     * Use {@link #template(String)} to retrieve a registered template.
     */
    CafeAI template(String name, String body);

    // ── Memory ────────────────────────────────────────────────────────────────

    /**
     * Configures tiered context memory for multi-turn conversation state.
     *
     * <pre>{@code
     *   app.memory(MemoryStrategy.inMemory());   // Rung 1 — prototype
     *   app.memory(MemoryStrategy.mapped());     // Rung 2 — SSD-backed via Java FFM
     *   app.memory(MemoryStrategy.redis(cfg));   // Rung 4 — distributed escape valve
     * }</pre>
     */
    CafeAI memory(MemoryStrategy strategy);

    // ── RAG Pipeline (ROADMAP-07 Phase 4) ─────────────────────────────────────

    /**
     * Registers the vector store for the RAG pipeline.
     *
     * <pre>{@code
     *   app.vectordb(VectorStore.inMemory());                          // zero infra
     *   app.vectordb(PgVector.connect(PgVectorConfig.of("jdbc:...")));  // production
     * }</pre>
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI vectordb(Object store);

    /**
     * Registers the embedding model used to embed documents during ingestion
     * and queries during retrieval.
     *
     * <pre>{@code
     *   app.embed(EmbeddingModel.local());       // ONNX — no API key, no latency
     *   app.embed(EmbeddingModel.openAi());      // OpenAI ada-002
     * }</pre>
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI embed(Object model);

    /**
     * Ingests a knowledge source into the vector store.
     *
     * <p>The source is parsed, split into overlapping chunks, each chunk is
     * embedded using the registered {@link io.cafeai.rag.EmbeddingModel}, and
     * the chunks are upserted into the registered {@link io.cafeai.rag.VectorStore}.
     * Re-ingesting the same source updates existing chunks without duplication.
     *
     * <pre>{@code
     *   app.ingest(Source.pdf("docs/handbook.pdf"));
     *   app.ingest(Source.directory("knowledge/"));
     *   app.ingest(Source.text("CafeAI is a Gen AI framework.", "cafeai-intro"));
     * }</pre>
     *
     * @throws io.cafeai.rag.Source.SourceException if the source cannot be loaded
     * @throws IllegalStateException if no vectordb or embedding model is registered
     */
    CafeAI ingest(Object source);

    /**
     * Attaches a retrieval strategy to the application.
     *
     * <p>Once registered, every {@code app.prompt().call()} automatically
     * retrieves the top-K most relevant chunks and injects them into the
     * LLM context before the user's message. Retrieved documents are also
     * stored in {@code req.attribute(Attributes.RAG_DOCUMENTS)}.
     *
     * <pre>{@code
     *   app.rag(Retriever.semantic(5));   // top 5 by cosine similarity
     *   app.rag(Retriever.hybrid(5));     // dense + BM25 combined
     * }</pre>
     *
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI rag(Object retriever);

    // ── Guardrails ────────────────────────────────────────────────────────────

    /**
     * Attaches a guardrail middleware — composable, pre-LLM and/or post-LLM.
     *
     * <pre>{@code
     *   app.guard(GuardRail.pii());
     *   app.guard(GuardRail.jailbreak());
     *   app.guard(GuardRail.regulatory().gdpr().hipaa());
     * }</pre>
     */
    CafeAI guard(GuardRail guardRail);

    // ── Application-Scoped Locals ─────────────────────────────────────────────

    /**
     * Stores an application-lifetime local value — accessible from all
     * middleware and handlers via {@code req.app().local(key, type)}.
     *
     * <p>Mirrors Express {@code app.locals.key = value}.
     * Thread-safe. Backed by a {@link java.util.concurrent.ConcurrentHashMap}.
     */
    CafeAI local(String key, Object value);

    /**
     * Retrieves a typed application-scoped local.
     *
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    <T> T local(String key, Class<T> type);

    /**
     * Retrieves an untyped application-scoped local. Returns {@code null} if absent.
     */
    Object local(String key);

    /**
     * Returns an unmodifiable snapshot of all application-scoped locals,
     * excluding CafeAI-internal keys (prefixed with {@code __cafeai.}).
     *
     * <p>Mirrors Express {@code app.locals} (read access).
     */
    java.util.Map<String, Object> locals();

    // ── Application Settings (ROADMAP-02 Phase 7) ─────────────────────────────

    /**
     * Sets an application setting value.
     * Mirrors Express: {@code app.set(name, value)}
     *
     * <pre>{@code
     *   app.set(Setting.ENV, "production");
     *   app.set(Setting.JSON_SPACES, 2);
     * }</pre>
     */
    CafeAI set(Setting setting, Object value);

    /**
     * Returns the value of an application setting.
     *
     * <p>ADR-005 note: Express uses {@code app.get(name)} for settings retrieval,
     * which conflicts with the HTTP GET route method. CafeAI uses {@code app.setting()}
     * for unambiguous settings access.
     *
     * <pre>{@code
     *   String env = app.setting(Setting.ENV, String.class);
     * }</pre>
     */
    <T> T setting(Setting setting, Class<T> type);

    /**
     * Returns the raw (untyped) value of an application setting.
     */
    Object setting(Setting setting);

    /**
     * Sets a boolean setting to {@code true}.
     * Throws {@link IllegalArgumentException} if the setting is not a boolean type.
     * Mirrors Express: {@code app.enable(name)}
     */
    CafeAI enable(Setting setting);

    /**
     * Sets a boolean setting to {@code false}.
     * Throws {@link IllegalArgumentException} if the setting is not a boolean type.
     * Mirrors Express: {@code app.disable(name)}
     */
    CafeAI disable(Setting setting);

    /**
     * Returns {@code true} if the setting is truthy (non-null, non-false, non-zero).
     * Mirrors Express: {@code app.enabled(name)}
     */
    boolean enabled(Setting setting);

    /**
     * Returns {@code true} if the setting is falsy (null, false, or zero).
     * Mirrors Express: {@code app.disabled(name)}
     */
    boolean disabled(Setting setting);

    // ── Mount Path (ROADMAP-02 Phase 2) ───────────────────────────────────────

    /**
     * Returns the path at which this application is mounted.
     * Returns an empty string for the root application.
     * Mirrors Express: {@code app.mountpath}
     *
     * <pre>{@code
     *   var admin = CafeAI.create();
     *   app.use("/admin", admin);
     *   admin.mountpath(); // → "/admin"
     * }</pre>
     */
    String mountpath();

    /**
     * Returns all mount paths for this application (supports multi-path mounting).
     * Returns an empty list for the root application.
     */
    java.util.List<String> mountpaths();

    /**
     * Registers a callback to be invoked when this sub-application is mounted
     * on a parent application via {@code parent.use(path, thisApp)}.
     * Mirrors Express: {@code app.on('mount', callback)}
     *
     * <pre>{@code
     *   admin.onMount(parent ->
     *       System.out.println("Admin mounted at: " + admin.mountpath()));
     *   app.use("/admin", admin);
     * }</pre>
     */
    CafeAI onMount(java.util.function.Consumer<CafeAI> callback);

    /**
     * Returns the canonical path of this application — the full path including
     * any parent mount paths.
     * Returns empty string for the root application.
     * Mirrors Express: {@code app.path()}
     *
     * <pre>{@code
     *   // admin mounted at /admin, users mounted at /users inside admin
     *   users.path(); // → "/admin/users"
     * }</pre>
     */
    String path();

    // ── Template Engine (ROADMAP-02 Phase 8) ──────────────────────────────────

    /**
     * Registers a {@link ResponseFormatter} for the given file extension.
     * Mirrors Express: {@code app.engine(ext, callback)}
     *
     * <pre>{@code
     *   app.engine("html", ResponseFormatter.mustache());
     *   app.engine("md",   ResponseFormatter.markdown());
     * }</pre>
     */
    CafeAI engine(String ext, ResponseFormatter formatter);

    /**
     * Renders a named view using the registered template engine.
     * The view is resolved relative to {@code Setting.VIEWS}.
     * Mirrors Express: {@code app.render(view, locals, callback)}
     *
     * <pre>{@code
     *   app.render("welcome", Map.of("name", "Ada"),
     *       (err, html) -> { if (err == null) System.out.println(html); });
     * }</pre>
     */
    void render(String view, java.util.Map<String, Object> locals,
                java.util.function.BiConsumer<Throwable, String> callback);

    /**
     * Renders a named view and returns the result as a {@link java.util.concurrent.CompletableFuture}.
     * Mirrors Express: {@code app.render(view, locals)} — Java async idiom.
     */
    java.util.concurrent.CompletableFuture<String> render(String view,
                                                          java.util.Map<String, Object> locals);

    // ── WebSocket ─────────────────────────────────────────────────────────────

    /**
     * Registers a WebSocket endpoint at the given path.
     *
     * <p>The server handles both HTTP and WebSocket connections on the same
     * port — HTTP routes and WebSocket endpoints coexist without conflict.
     * The Helidon runtime upgrades connections that send a WebSocket
     * handshake request; all other requests continue through the HTTP
     * pipeline normally.
     *
     * <pre>{@code
     *   // Full handler — implement only the events you need
     *   app.ws("/chat", new WsHandler() {
     *       public void onOpen(WsSession session) {
     *           session.send("Connected! Session: " + session.id());
     *       }
     *       public void onMessage(WsSession session, String message) {
     *           session.send("Echo: " + message);
     *       }
     *       public void onClose(WsSession session, int code, String reason) {
     *           System.out.println("Closed: " + reason);
     *       }
     *   });
     *
     *   // Lambda shorthand for message-only handling
     *   app.ws("/echo", WsHandler.onMessage((session, msg) -> session.send(msg)));
     * }</pre>
     *
     * @param path    the WebSocket endpoint path (same syntax as HTTP routes)
     * @param handler the lifecycle event handler
     * @throws IllegalStateException if called after {@link #listen(int)}
     */
    CafeAI ws(String path, io.cafeai.core.routing.WsHandler handler);

    // ── Server Lifecycle ──────────────────────────────────────────────────────

    /**
     * Starts the Helidon SE server on the given port.
     * Mirrors: {@code app.listen(3000)}
     *
     * @throws IllegalStateException if the server is already running
     */
    void listen(int port);

    /**
     * Starts the server, invoking {@code onStart} once ready to accept connections.
     * Mirrors: {@code app.listen(3000, () => console.log('Running on :3000'))}
     */
    void listen(int port, Runnable onStart);

    /**
     * Stops the server gracefully, waiting for in-flight requests to complete.
     * Invoked automatically by the JVM shutdown hook.
     */
    void stop();

    /**
     * Returns {@code true} if the server is currently running and accepting connections.
     */
    boolean isRunning();
}
