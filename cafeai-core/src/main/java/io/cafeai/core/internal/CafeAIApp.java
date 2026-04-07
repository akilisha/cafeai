package io.cafeai.core.internal;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.ModelRouter;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.ai.Template;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.*;
import io.cafeai.core.spi.CafeAIConfigurer;
import io.cafeai.core.spi.CafeAIModule;
import io.cafeai.core.spi.CafeAIRegistry;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.Filter;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.websocket.WsRouting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import io.cafeai.core.Locals;
import io.cafeai.core.ResponseFormatter;
import io.cafeai.core.Setting;
import io.cafeai.core.middleware.ErrorMiddleware;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Concrete implementation of {@link CafeAI}.
 *
 * <p><strong>Internal -- do not reference directly. Always use {@link CafeAI#create()}.</strong>
 * Public only because Java package-private cannot cross package boundaries without JPMS.
 * JPMS module encapsulation (ROADMAP-08 Phase 3) will enforce this properly.
 *
 * <h2>Internal wiring model (ADR-009)</h2>
 * <ul>
 *   <li>{@code app.filter(mw)} -> Helidon {@code addFilter()} -- runs before route dispatch</li>
 *   <li>{@code app.get(path, mw...)} -> Helidon {@code builder.get()} with composed handler</li>
 *   <li>{@code app.use(path, router)} -> sub-router expanded inline at mount prefix</li>
 * </ul>
 * Helidon's {@code Filter} / {@code FilterChain} are private implementation details.
 * No public API surface references them.
 */
public final class CafeAIApp implements CafeAI {

    private static final Logger log = LoggerFactory.getLogger(CafeAIApp.class);

    // -- State -----------------------------------------------------------------

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final Map<String, Object>   locals        = new ConcurrentHashMap<>();
    private final List<FilterEntry>     filterEntries = new ArrayList<>();
    private final List<WsEndpoint>      wsEndpoints   = new ArrayList<>();
    private final List<RouteEntry>      routes        = new ArrayList<>();
    private final CafeAIRegistryImpl    registry      = new CafeAIRegistryImpl();

    // AI state
    private AiProvider      aiProvider;
    private ModelRouter     modelRouter;
    private String          systemPrompt;
    private MemoryStrategy  memoryStrategy;
    private final List<GuardRail>         guardRails   = new ArrayList<>();

    // RAG pipeline state (ROADMAP-07 Phase 4)
    private Object vectorStore;
    private Object embeddingModel;
    private Object retriever;

    // Tool registry bridge (ROADMAP-07 Phase 5) -- loaded via ServiceLoader
    private io.cafeai.core.spi.ToolBridge toolBridge;

    // Observability bridge (ROADMAP-07 Phase 9) -- loaded via ServiceLoader
    private io.cafeai.core.spi.ObserveBridge observeBridge;

    // Token budget and retry (ROADMAP-14 Phase 10)
    private TokenBudgetTracker budgetTracker;
    private io.cafeai.core.ai.RetryPolicy        retryPolicy;
    private Object evalHarness;
    private final Map<String, String>     templates  = new ConcurrentHashMap<>();

    // ROADMAP-02: Application settings
    private final Map<Setting, Object> settings = new ConcurrentHashMap<>();

    // ROADMAP-02: Template engines
    private final Map<String, ResponseFormatter> engines = new ConcurrentHashMap<>();

    // ROADMAP-02: Mount state
    private final List<String>                                      mountPaths     = new ArrayList<>();
    private final List<java.util.function.Consumer<CafeAI>>        mountCallbacks = new ArrayList<>();
    private CafeAI                                                   parent;

    // ROADMAP-06: Error-handling middleware
    private final List<ErrorMiddleware>   errorHandlers  = new ArrayList<>();

    // Helidon escape hatch — direct access to WebServer.Builder and HttpRouting.Builder
    private final List<java.util.function.Consumer<WebServerConfig.Builder>>         helidonServerConsumers  = new ArrayList<>();
    private final List<java.util.function.Consumer<HttpRouting.Builder>>            helidonRoutingConsumers = new ArrayList<>();

    private WebServer server;

    // -- Factory ---------------------------------------------------------------

    /** Internal -- call {@link CafeAI#create()} instead. */
    public static CafeAI newInstance() {
        var app = new CafeAIApp();
        app.discoverModules();
        app.discoverConfigurers();
        return app;
    }

    private CafeAIApp() {
        // Initialise settings with Express-matching defaults
        for (Setting s : io.cafeai.core.Setting.values()) {
            if (s.defaultValue() != null) {
                settings.put(s, s.defaultValue());
            }
        }
    }

    // -- Service Loader Discovery ----------------------------------------------

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

    // -- CafeAIConfigurer ------------------------------------------------------

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

    // -- AI Infrastructure -----------------------------------------------------

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
        locals.put(Locals.SYSTEM_PROMPT, systemPrompt);
        return this;
    }

    @Override
    public CafeAI template(String name, String body) {
        assertNotStarted("template()");
        Objects.requireNonNull(name, "Template name must not be null");
        Objects.requireNonNull(body, "Template body must not be null");
        templates.put(name, body);
        return this;
    }

    @Override
    public Template template(String name) {
        String body = templates.get(name);
        if (body == null) {
            throw new IllegalArgumentException(
                "No template registered with name '" + name + "'. " +
                "Register one at startup: app.template(\"" + name + "\", \"your {{template}} body\")");
        }
        return new Template(name, body);
    }

    @Override
    public PromptRequest prompt(String message) {
        Objects.requireNonNull(message, "Prompt message must not be null");
        return new PromptRequest(message, this::executePrompt);
    }

    @Override
    public PromptRequest prompt(String templateName, Map<String, Object> vars) {
        Objects.requireNonNull(templateName, "Template name must not be null");
        String rendered = template(templateName).render(vars != null ? vars : Map.of());
        return new PromptRequest(rendered, this::executePrompt);
    }

    /**
     * Executes a {@link PromptRequest} against the registered LLM provider.
     *
     * <p>Execution pipeline:
     * <ol>
     *   <li>Resolve the model -- {@code ModelRouter} or single {@code AiProvider}</li>
     *   <li>Build the message list -- system prompt + conversation history + user message</li>
     *   <li>Call Langchain4j {@code ChatModel.chat()}</li>
     *   <li>Store the exchange in memory if a session ID is present</li>
     *   <li>Return {@link PromptResponse} with text + token counts</li>
     * </ol>
     */
    private PromptResponse executePrompt(PromptRequest request) {
        // -- 1. Resolve provider -----------------------------------------------
        AiProvider provider = resolveProvider(request);

        // -- 2. Get the Langchain4j ChatModel --------------------------
        ChatModel model =
            LangchainBridge.INSTANCE.modelFor(provider);

        // -- 2b. Append schema hint for structured output -------------------
        // When returning(Class) / call(Class<T>) is used, a JSON schema hint
        // was injected into PromptRequest. Append it to the message so the
        // LLM knows the expected output format.
        String effectiveMessage = request.schemaHint() != null
            ? request.message() + request.schemaHint()
            : request.message();

        // -- 3. Build message list ---------------------------------------------
        List<ChatMessage> messages = new ArrayList<>();

        // System prompt -- override or application default
        String sysPrompt = request.systemOverride() != null
            ? request.systemOverride()
            : systemPrompt;
        if (sysPrompt != null && !sysPrompt.isBlank()) {
            messages.add(SystemMessage.from(sysPrompt));
        }

        // Conversation history from memory
        if (request.sessionId() != null && memoryStrategy != null) {
            ConversationContext ctx =
                memoryStrategy.retrieve(request.sessionId());
            if (ctx != null) {
                for (var msg : ctx.messages()) {
                    if ("user".equalsIgnoreCase(msg.role())) {
                        messages.add(UserMessage.from(msg.content()));
                    } else if ("assistant".equalsIgnoreCase(msg.role())) {
                        messages.add(AiMessage.from(msg.content()));
                    }
                }
            }
        }

        // The current user message
        messages.add(UserMessage.from(effectiveMessage));

        // -- 3b. RAG retrieval -- inject context before the LLM call -----------
        java.util.List<Object> retrievedDocs = java.util.List.of();
        if (retriever != null && vectorStore != null && embeddingModel != null) {
            try {
                var pipeline = java.util.ServiceLoader
                    .load(io.cafeai.core.spi.RagPipeline.class)
                    .findFirst()
                    .orElse(null);

                if (pipeline != null) {
                    retrievedDocs = pipeline.retrieve(
                        request.message(), retriever, vectorStore, embeddingModel);

                    if (!retrievedDocs.isEmpty()) {
                        // Build a context block from the retrieved documents.
                        // Injected as a UserMessage immediately before the actual question
                        // so the LLM sees: [system] -> [history] -> [context] -> [question]
                        var sb = new StringBuilder(
                            "Relevant context from the knowledge base:\n\n");
                        for (int i = 0; i < retrievedDocs.size(); i++) {
                            sb.append("[").append(i + 1).append("] ");
                            sb.append(retrievedDocs.get(i).toString());
                            sb.append("\n\n");
                        }
                        sb.append("Use the above context to answer the following question:");
                        // Insert context BEFORE the user question (swap last two)
                        messages.add(messages.size() - 1,
                            UserMessage.from(sb.toString()));
                    }
                }
            } catch (Exception e) {
                log.warn("RAG retrieval failed -- proceeding without context: {}", e.getMessage());
            }
        }

        // -- 4. Call the LLM (with observability intercept) -------------------
        String responseText = "";
        int promptTokens = 0;
        int outputTokens = 0;

        Object observeCtx = observeBridge != null
            ? observeBridge.beforePrompt(request) : null;

        // -- Token budget: wait if current window is exhausted ----------------
        if (budgetTracker != null) budgetTracker.waitIfNeeded();

        Throwable observeError = null;
        int attemptsLeft = retryPolicy != null ? retryPolicy.maxAttempts() : 1;
        Throwable lastRateLimitError = null;

        while (attemptsLeft > 0) {
          attemptsLeft--;
          lastRateLimitError = null;
          try {
            if (toolBridge != null && toolBridge.hasTools()) {
                responseText = toolBridge.executeWithTools(model, messages);
                // -- POST_LLM guardrail check on tool-calling response ----------
                // PRE_LLM guardrails already ran on the user's input. Now apply
                // POST_LLM and BOTH guardrails to the assembled tool-call output.
                // This ensures adversarial inputs that influence the tool-calling
                // loop cannot produce harmful final responses undetected.
                responseText = applyPostLlmGuardrails(responseText);
            } else {
                ChatResponse response = model.chat(messages);
                responseText = response.aiMessage().text();
                TokenUsage usage = response.tokenUsage();
                promptTokens  = usage != null ? usage.inputTokenCount()  : 0;
                outputTokens  = usage != null ? usage.outputTokenCount() : 0;
            }
            // -- Token budget: record actual usage after successful call -------
            if (budgetTracker != null) {
                budgetTracker.recordUsage(promptTokens + outputTokens);
            }
            break; // success — exit retry loop
          } catch (RuntimeException e) {
            // Retry on rate limit if policy is configured and attempts remain
            if (retryPolicy != null && retryPolicy.retriesOnRateLimit()
                    && isRateLimitException(e) && attemptsLeft > 0) {
                int attemptNum = retryPolicy.maxAttempts() - attemptsLeft;
                long waitMs = retryPolicy.backoff().toMillis() * attemptNum;
                log.warn("Rate limit hit — retrying in {}ms (attempt {}/{})",
                    waitMs, attemptNum, retryPolicy.maxAttempts());
                lastRateLimitError = e;
                try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during rate limit backoff", ie);
                }
            } else {
                observeError = e;
                throw e;
            }
          }
        }

        if (lastRateLimitError != null) {
            throw new io.cafeai.core.ai.RetryPolicy.RateLimitExceededException(
                "Rate limit exceeded after " + retryPolicy.maxAttempts() + " attempts",
                lastRateLimitError);
        }

        // -- Observability: fire afterPrompt with final response ---------------
        if (observeBridge != null) {
            PromptResponse partial = observeError == null
                ? PromptResponse.builder()
                    .text(responseText)
                    .promptTokens(promptTokens)
                    .outputTokens(outputTokens)
                    .modelId(provider.modelId())
                    .fromCache(false)
                    .ragDocuments(retrievedDocs)
                    .build()
                : null;
            observeBridge.afterPrompt(observeCtx, request, partial, observeError);
        }

        // -- 4b. Set LLM_RESPONSE_TEXT for POST_LLM HTTP middleware guardrails --
        // If this prompt was called within an HTTP request context, store the
        // response text so guardrails with POST_LLM or BOTH position can inspect
        // it after next.run() returns in their handle() method.
        if (request.httpRequest() != null) {
            request.httpRequest().setAttribute(
                io.cafeai.core.Attributes.LLM_RESPONSE_TEXT, responseText);
        }

        // -- 5. Persist to memory ----------------------------------------------
        if (request.sessionId() != null && memoryStrategy != null) {
            ConversationContext ctx =
                memoryStrategy.retrieve(request.sessionId());
            if (ctx == null) {
                ctx = new ConversationContext(request.sessionId());
            }
            ctx.addMessage("user",      request.message());
            ctx.addMessage("assistant", responseText);
            ctx.addTokens(promptTokens + outputTokens);
            memoryStrategy.store(request.sessionId(), ctx);
        }

        // -- 6. Return PromptResponse ------------------------------------------
        return PromptResponse.builder()
            .text(responseText)
            .promptTokens(promptTokens)
            .outputTokens(outputTokens)
            .modelId(provider.modelId())
            .fromCache(false)
            .ragDocuments(retrievedDocs)
            .build();
    }

    /**
     * Returns true if the exception looks like a rate limit response from the provider.
     * Checks exception class name and message for common rate-limit indicators since
     * LangChain4j doesn't expose a typed RateLimitException.
     */
    private static boolean isRateLimitException(Throwable t) {
        if (t == null) return false;
        String className = t.getClass().getSimpleName().toLowerCase();
        String message   = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return className.contains("ratelimit")
            || message.contains("rate limit")
            || message.contains("rate_limit")
            || message.contains("too many requests")
            || message.contains("429")
            || (t.getCause() != null && isRateLimitException(t.getCause()));
    }

    /**
     * Applies POST_LLM and BOTH guardrails to the assembled LLM response text.
     *
     * <p>Called after the LLM (or tool-calling loop) produces its final output.
     * Guardrails with {@code Position.POST_LLM} or {@code Position.BOTH} inspect
     * the response text and may redact or replace it if a violation is found.
     *
     * <p>This is the direct-call path for {@code app.prompt()} and
     * {@code app.vision()} — distinct from the HTTP middleware path where
     * guardrails run as Helidon filters. Both paths eventually call this method
     * for the POST_LLM check.
     *
     * @param responseText the assembled LLM response (may be from a tool-calling loop)
     * @return the (possibly modified) response text after POST_LLM checks
     */
    private String applyPostLlmGuardrails(String responseText) {
        if (guardRails.isEmpty() || responseText == null || responseText.isBlank()) {
            return responseText;
        }
        for (io.cafeai.core.guardrails.GuardRail rail : guardRails) {
            if (rail.position() == io.cafeai.core.guardrails.GuardRail.Position.POST_LLM
                    || rail.position() == io.cafeai.core.guardrails.GuardRail.Position.BOTH) {
                io.cafeai.core.guardrails.GuardRail.OutputCheckResult result =
                    rail.checkOutput(responseText);
                if (result != null && result.isViolation()) {
                    log.warn("POST_LLM guardrail '{}' triggered on tool-call response: {}",
                        rail.name(), result.reason());
                    // Replace with a safe refusal rather than propagating the violating content
                    return "[Response blocked by guardrail: " + rail.name() + "]";
                }
            }
        }
        return responseText;
    }

    /**
     * Resolves which {@link AiProvider} to use for this request.
     * If a {@link io.cafeai.core.ai.ModelRouter} is registered, delegates to it.
     * Falls back to the single registered provider.
     */
    private AiProvider resolveProvider(
            PromptRequest request) {
        if (aiProvider == null && modelRouter == null) {
            throw new IllegalStateException(
                "No AI provider registered. Call app.ai(OpenAI.gpt4o()) at startup.\n\n" +
                "For local models (no API key needed):\n" +
                "  app.ai(Ollama.llama3())");
        }
        if (modelRouter != null) {
            // Simple heuristic: messages over 500 chars go to the complex model
            boolean isComplex = request.message().length() > 500;
            return isComplex
                ? modelRouter.complexModel()
                : modelRouter.simpleModel();
        }
        return aiProvider;
    }

    @Override
    public CafeAI memory(MemoryStrategy strategy) {
        assertNotStarted("memory()");
        this.memoryStrategy = Objects.requireNonNull(strategy, "MemoryStrategy must not be null");
        locals.put(Locals.MEMORY_STRATEGY, strategy);
        log.info("Memory strategy registered: {}", strategy.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI observe(Object strategy) {
        assertNotStarted("observe()");
        Objects.requireNonNull(strategy, "ObserveStrategy must not be null");
        // Load the bridge from the strategy -- strategy carries its own bridge
        this.observeBridge = java.util.ServiceLoader
            .load(io.cafeai.core.spi.ObserveBridge.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "app.observe() requires cafeai-observability on the classpath. " +
                "Add: implementation 'io.cafeai:cafeai-observability'"));
        this.observeBridge.setStrategy(strategy);
        locals.put(Locals.OBSERVE_STRATEGY, strategy);
        log.info("Observability registered: {}", strategy.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI eval(Object harness) {
        assertNotStarted("eval()");
        Objects.requireNonNull(harness, "EvalHarness must not be null");
        this.evalHarness = harness;
        log.info("Eval harness registered: {}", harness.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI connect(Object connection) {
        assertNotStarted("connect()");
        Objects.requireNonNull(connection, "Connection must not be null");

        // Probe the service
        io.cafeai.core.spi.ConnectBridge bridge = loadConnectBridge();
        if (bridge != null) {
            bridge.connect(connection, this);
        } else {
            // No cafeai-connect -- store for later, log clearly
            log.warn("app.connect() called but cafeai-connect is not on the classpath. " +
                "Add: implementation 'io.cafeai:cafeai-connect'");
        }
        return this;
    }

    private io.cafeai.core.spi.ConnectBridge loadConnectBridge() {
        return java.util.ServiceLoader
            .load(io.cafeai.core.spi.ConnectBridge.class)
            .findFirst()
            .orElse(null);
    }

    @Override
    public CafeAI ws(String path, io.cafeai.core.routing.WsHandler handler) {
        assertNotStarted("ws()");
        Objects.requireNonNull(path,    "WebSocket path must not be null");
        Objects.requireNonNull(handler, "WsHandler must not be null");
        wsEndpoints.add(new WsEndpoint(path, handler));
        return this;
    }

    // -- Helidon Escape Hatch --------------------------------------------------

    @Override
    public CafeAI.HelidonConfig helidon() {
        assertNotStarted("helidon()");
        return new HelidonConfigImpl();
    }

    /**
     * Implementation of the Helidon escape hatch fluent configurator.
     * Consumers are stored on the outer {@link CafeAIApp} and applied
     * during {@link #listen(int)} after CafeAI's own routing is assembled.
     */
    private final class HelidonConfigImpl implements CafeAI.HelidonConfig {

        @Override
        public CafeAI.HelidonConfig server(
                java.util.function.Consumer<WebServerConfig.Builder> consumer) {
            Objects.requireNonNull(consumer, "server consumer must not be null");
            helidonServerConsumers.add(consumer);
            return this;
        }

        @Override
        public CafeAI.HelidonConfig routing(
                java.util.function.Consumer<HttpRouting.Builder> consumer) {
            Objects.requireNonNull(consumer, "routing consumer must not be null");
            helidonRoutingConsumers.add(consumer);
            return this;
        }
    }

    @Override
    public CafeAI vectordb(Object store) {
        assertNotStarted("vectordb()");
        this.vectorStore = Objects.requireNonNull(store, "VectorStore must not be null");
        locals.put(Locals.VECTOR_STORE, store);
        log.info("Vector store registered: {}", store.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI embed(Object model) {
        assertNotStarted("embed()");
        this.embeddingModel = Objects.requireNonNull(model, "EmbeddingModel must not be null");
        locals.put(Locals.EMBEDDING_MODEL, model);
        log.info("Embedding model registered: {}", model.getClass().getSimpleName());
        return this;
    }

    @Override
    public CafeAI ingest(Object source) {
        Objects.requireNonNull(source, "Source must not be null");
        if (vectorStore == null) {
            throw new IllegalStateException(
                "No vector store registered. Call app.vectordb(VectorStore.inMemory()) first.");
        }
        if (embeddingModel == null) {
            throw new IllegalStateException(
                "No embedding model registered. Call app.embed(EmbeddingModel.local()) first.");
        }
        // Ingestion is executed by cafeai-rag via the RagPipeline SPI.
        // The objects are stored here; actual chunking/embedding/upserting happens
        // in cafeai-rag where all the types are visible.
        java.util.ServiceLoader.load(io.cafeai.core.spi.RagPipeline.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "RAG ingestion requires the cafeai-rag module. " +
                "Add: implementation 'io.cafeai:cafeai-rag'"))
            .ingest(source, vectorStore, embeddingModel);
        return this;
    }

    @Override
    public CafeAI rag(Object retriever) {
        assertNotStarted("rag()");
        this.retriever = Objects.requireNonNull(retriever, "Retriever must not be null");
        log.info("RAG retriever registered: {}", retriever.getClass().getSimpleName());
        return this;
    }

    // -- Chains (ROADMAP-07 Phase 6) -------------------------------------------

    // -- Tools & MCP (ROADMAP-07 Phase 5) -------------------------------------

    @Override
    public CafeAI tool(Object toolInstance) {
        assertNotStarted("tool()");
        Objects.requireNonNull(toolInstance, "Tool instance must not be null");
        getOrCreateToolBridge().register(toolInstance);
        return this;
    }

    @Override
    public CafeAI tools(Object... toolInstances) {
        for (Object t : toolInstances) tool(t);
        return this;
    }

    @Override
    public CafeAI mcp(Object mcpServer) {
        assertNotStarted("mcp()");
        Objects.requireNonNull(mcpServer, "McpServer must not be null");
        getOrCreateToolBridge().registerMcp(mcpServer);
        return this;
    }

    private io.cafeai.core.spi.ToolBridge getOrCreateToolBridge() {
        if (toolBridge == null) {
            toolBridge = java.util.ServiceLoader
                .load(io.cafeai.core.spi.ToolBridge.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Tool support requires the cafeai-tools module. " +
                    "Add: implementation 'io.cafeai:cafeai-tools'"));
        }
        return toolBridge;
    }

    @Override
    public CafeAI guard(GuardRail guardRail) {        assertNotStarted("guard()");
        Objects.requireNonNull(guardRail, "GuardRail must not be null");
        guardRails.add(guardRail);
        log.info("GuardRail registered: {} ({})", guardRail.name(), guardRail.position());
        return this;
    }

    @Override
    public CafeAI budget(io.cafeai.core.ai.TokenBudget budget) {
        assertNotStarted("budget()");
        Objects.requireNonNull(budget, "TokenBudget must not be null");
        this.budgetTracker = new TokenBudgetTracker(budget);
        log.info("Token budget registered: {}", budget);
        return this;
    }

    @Override
    public CafeAI retry(io.cafeai.core.ai.RetryPolicy policy) {
        assertNotStarted("retry()");
        Objects.requireNonNull(policy, "RetryPolicy must not be null");
        this.retryPolicy = policy;
        log.info("Retry policy registered: {}", policy);
        return this;
    }

    // -- Application Locals ----------------------------------------------------

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

    @Override
    public java.util.Map<String, Object> locals() {
        // Return unmodifiable snapshot excluding internal CafeAI keys
        return locals.entrySet().stream()
            .filter(e -> !Locals.isInternal(e.getKey()))
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, Map.Entry::getValue));
    }

    // -- Application Settings (ROADMAP-02 Phase 7) -----------------------------

    @Override
    public CafeAI set(Setting setting, Object value) {
        Objects.requireNonNull(setting, "Setting must not be null");
        if (value == null) {
            settings.remove(setting);
        } else {
            settings.put(setting, value);
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T setting(Setting setting, Class<T> type) {
        Object value = settings.containsKey(setting)
            ? settings.get(setting)
            : setting.defaultValue();
        if (value == null) return null;
        return type.cast(value);
    }

    @Override
    public Object setting(Setting setting) {
        // getOrDefault would return null if the key maps to an explicitly stored null
        // (e.g. after app.set(Setting.VIEW_ENGINE, null)).
        // containsKey correctly distinguishes "key absent -> use default" from
        // "key present with null value -> return null".
        return settings.containsKey(setting)
            ? settings.get(setting)
            : setting.defaultValue();
    }

    @Override
    public CafeAI enable(Setting setting) {
        if (!isBooleanCapable(setting)) {
            throw new IllegalArgumentException(
                "Setting." + setting.name() + " does not support enable()/disable(). " +
                "Use app.set(setting, value) instead.");
        }
        settings.put(setting, true);
        return this;
    }

    @Override
    public CafeAI disable(Setting setting) {
        if (!isBooleanCapable(setting)) {
            throw new IllegalArgumentException(
                "Setting." + setting.name() + " does not support enable()/disable(). " +
                "Use app.set(setting, value) instead.");
        }
        settings.put(setting, false);
        return this;
    }

    /**
     * Returns true if the setting supports enable()/disable().
     * A setting is boolean-capable if its declared type is Boolean,
     * OR if it's an Object type whose default value is a Boolean
     * (e.g. TRUST_PROXY -- can be boolean or integer hop count,
     * but enable/disable make sense for the boolean case).
     */
    private static boolean isBooleanCapable(Setting setting) {
        return setting.isBoolean()
            || (setting.valueType() == Object.class
                && setting.defaultValue() instanceof Boolean);
    }

    @Override
    public boolean enabled(Setting setting) {
        Object value = setting(setting);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n)  return n.intValue() != 0;
        return value != null;
    }

    @Override
    public boolean disabled(Setting setting) {
        return !enabled(setting);
    }

    // -- Mount Path (ROADMAP-02 Phase 2 + 9) ----------------------------------

    @Override
    public String mountpath() {
        return mountPaths.isEmpty() ? "" : mountPaths.get(0);
    }

    @Override
    public java.util.List<String> mountpaths() {
        return Collections.unmodifiableList(mountPaths);
    }

    @Override
    public CafeAI onMount(java.util.function.Consumer<CafeAI> callback) {
        Objects.requireNonNull(callback, "Mount callback must not be null");
        mountCallbacks.add(callback);
        return this;
    }

    @Override
    public String path() {
        if (parent == null) return "";
        return parent.path() + mountpath();
    }

    /** Called by parent app when this sub-app is mounted. */
    public void notifyMount(CafeAI parentApp, String mountPath) {
        this.parent = parentApp;
        this.mountPaths.add(mountPath);
        for (var cb : mountCallbacks) {
            cb.accept(parentApp);
        }
    }

    // -- Template Engine (ROADMAP-02 Phase 8) ----------------------------------

    @Override
    public CafeAI engine(String ext, ResponseFormatter formatter) {
        Objects.requireNonNull(ext,       "Extension must not be null");
        Objects.requireNonNull(formatter, "ResponseFormatter must not be null");
        // Normalise: strip leading dot if present
        engines.put(ext.startsWith(".") ? ext.substring(1) : ext, formatter);
        return this;
    }

    @Override
    public void render(String view, java.util.Map<String, Object> locals,
                       java.util.function.BiConsumer<Throwable, String> callback) {
        try {
            String result = renderView(view, locals);
            callback.accept(null, result);
        } catch (Exception e) {
            callback.accept(e, null);
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> render(
            String view, java.util.Map<String, Object> locals) {
        // Validate configuration eagerly on the calling thread -- before going async.
        // A missing engine or missing VIEW_ENGINE setting is a programming error that
        // should be caught immediately, not buried in an async task that may not
        // complete before the caller checks isCompletedExceptionally().
        try {
            validateRenderConfig(view);
        } catch (ResponseFormatter.RenderException e) {
            return CompletableFuture.failedFuture(e);
        }
        // Config is valid -- do the actual file I/O and rendering asynchronously
        return CompletableFuture.supplyAsync(
            () -> renderView(view, locals));
    }

    /**
     * Validates that a render call is configured correctly -- engine exists,
     * VIEW_ENGINE is set if no extension given -- without doing any I/O.
     * Throws {@link ResponseFormatter.RenderException} immediately
     * if configuration is incomplete. Called eagerly on the calling thread
     * so {@code CompletableFuture.failedFuture()} can be returned synchronously.
     */
    private void validateRenderConfig(String view) {
        int dot = view.lastIndexOf('.');
        String ext;
        if (dot >= 0) {
            ext = view.substring(dot + 1);
        } else {
            Object engineSetting = setting(Setting.VIEW_ENGINE);
            if (engineSetting == null || engineSetting.toString().isBlank()) {
                throw new ResponseFormatter.RenderException(
                    "No view engine configured. Call app.set(Setting.VIEW_ENGINE, \"html\") " +
                    "or include the extension in the view name (e.g. \"welcome.html\").");
            }
            ext = engineSetting.toString();
        }
        if (!engines.containsKey(ext)) {
            throw new ResponseFormatter.RenderException(
                "No engine registered for extension \"" + ext + "\". " +
                "Call app.engine(\"" + ext + "\", ResponseFormatter.mustache()) " +
                "after adding io.cafeai:cafeai-views-mustache to your dependencies.");
        }
    }

    /**
     * Core render logic -- resolves view path, selects engine, merges locals, formats.
     */
    private String renderView(String view, java.util.Map<String, Object> viewLocals) {
        // Determine extension from view name, or fall back to VIEW_ENGINE setting
        String ext;
        int dot = view.lastIndexOf('.');
        if (dot >= 0) {
            ext = view.substring(dot + 1);
        } else {
            Object engineSetting = setting(Setting.VIEW_ENGINE);
            if (engineSetting == null || engineSetting.toString().isBlank()) {
                throw new ResponseFormatter.RenderException(
                    "No view engine set. Call app.set(Setting.VIEW_ENGINE, \"html\") " +
                    "or include the extension in the view name.");
            }
            ext = engineSetting.toString();
        }

        ResponseFormatter formatter = engines.get(ext);
        if (formatter == null) {
            throw new ResponseFormatter.RenderException(
                "No engine registered for extension \"" + ext + "\". " +
                "Call app.engine(\"" + ext + "\", ResponseFormatter.mustache()).");
        }

        // Resolve template path from VIEWS setting
        String viewsDir = (String) setting(Setting.VIEWS);
        String fileName = dot >= 0 ? view : view + "." + ext;
        String templatePath = java.nio.file.Paths.get(viewsDir, fileName)
            .toAbsolutePath().normalize().toString();

        // Merge locals: app.locals() < res/view locals (view locals win on conflict)
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(locals());
        if (viewLocals != null) merged.putAll(viewLocals);

        return formatter.format(templatePath, merged);
    }

    // -- Error Handling (ROADMAP-06 Phase 2) ----------------------------------

    @Override
    public CafeAI onError(ErrorMiddleware handler) {
        assertNotStarted("onError()");
        Objects.requireNonNull(handler, "ErrorMiddleware must not be null");
        errorHandlers.add(handler);
        return this;
    }

    /**
     * Dispatches an error to the registered error-handling middleware chain.
     * Chains multiple handlers left-to-right -- each can call {@code next.run()}
     * to pass to the next. If no handler handles it, logs and sends a 500.
     */
    private void dispatchError(Throwable error, Request req, Response res) {
        if (errorHandlers.isEmpty()) {
            defaultErrorHandler(error, res);
            return;
        }
        // Build a chain of error handlers, each able to call next.run()
        Runnable chain = () -> defaultErrorHandler(error, res);
        for (int i = errorHandlers.size() - 1; i >= 0; i--) {
            final ErrorMiddleware handler = errorHandlers.get(i);
            final Runnable next = chain;
            chain = () -> handler.handle(error, req, res, () -> next.run());
        }
        chain.run();
    }

    private void defaultErrorHandler(Throwable error, Response res) {
        if (!res.headersSent()) {
            log.error("Unhandled request error", error);
            try {
                res.status(500).json(Map.of("error", "Internal Server Error",
                    "message", error.getMessage() != null ? error.getMessage() : ""));
            } catch (Exception ignored) {
                // Response may already be committed -- swallow
            }
        }
    }

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

    // -- HTTP Routing ----------------------------------------------------------

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
        // Inline route-pipeline middleware -- registered as global filters for now.
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

    // -- Server Lifecycle ------------------------------------------------------

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

        log.info("[coffee] CafeAI starting on port {}...", port);

        var routingBuilder = buildRouting();

        // Apply any raw Helidon routing consumers registered via app.helidon().routing()
        for (var consumer : helidonRoutingConsumers) {
            consumer.accept(routingBuilder);
        }

        var serverBuilder = WebServer.builder()
            .port(port)
            .addRouting(routingBuilder);

        // Apply any raw Helidon server consumers registered via app.helidon().server()
        for (var consumer : helidonServerConsumers) {
            consumer.accept(serverBuilder);
        }

        // Register WebSocket endpoints alongside HTTP routing
        if (!wsEndpoints.isEmpty()) {
            WsRouting.Builder wsBuilder = WsRouting.builder();
            for (WsEndpoint endpoint : wsEndpoints) {
                wsBuilder.endpoint(endpoint.path(), toHelidonWsListener(endpoint.handler()));
            }
            serverBuilder.addRouting(wsBuilder);
            log.info("   WebSocket endpoints: {}", wsEndpoints.size());
        }

        server = serverBuilder.build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[coffee] CafeAI shutting down gracefully...");
            stop();
        }, "cafeai-shutdown"));

        server.start();
        running.set(true);

        log.info("[coffee] CafeAI running on http://localhost:{}", server.port());
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
                log.info("[coffee] CafeAI stopped.");
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // -- Helidon Routing Builder -----------------------------------------------

    /**
     * Builds the Helidon {@link HttpRouting.Builder} from registered filters and routes.
     * Returns the builder -- not the built instance -- so {@code WebServer.addRouting(Builder)}
     * can accept it directly without requiring a cast.
     *
     * <p>Mapping (all Helidon types are private implementation details):
     * <ul>
     *   <li>{@code app.filter(mw)} -> {@code builder.addFilter()} -- pre-dispatch,
     *       every request, {@code next.run()} maps to {@code chain.proceed()}</li>
     *   <li>{@code app.filter(path, mw)} -> {@code builder.addFilter()} with internal
     *       path-prefix guard -- skips to {@code chain.proceed()} when path doesn't match</li>
     *   <li>{@code app.get(path, mw...)} -> {@code builder.get(path, handler)} where
     *       handler wraps the pre-composed middleware chain</li>
     * </ul>
     */
    private HttpRouting.Builder buildRouting() {
        var builder = HttpRouting.builder();

        // Register filter-scope middleware first (pre-dispatch).
        // This includes CafeAI.json() which parses the request body —
        // guardrails must run after body parsing so they can read req.body("message").
        for (var entry : filterEntries) {
            if (entry.path() == null) {
                builder.addFilter(toHelidonFilter(entry.middleware()));
            } else {
                builder.addFilter(toPathScopedFilter(entry.path(), entry.middleware()));
            }
        }

        // Register guardrails after body parsing filters.
        // They inspect the parsed body and block before the LLM is called.
        for (GuardRail guardRail : guardRails) {
            builder.addFilter(toHelidonFilter(guardRail));
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

    // -- Helidon Adapter Helpers (private -- no Helidon types leak out) ---------

    /**
     * Per-request context -- the single {@link HelidonRequest}/{@link HelidonResponse}
     * pair that flows through all filters AND the route handler for one HTTP request.
     *
     * <p>Keyed by the Helidon {@link ServerRequest} instance, which is the same
     * object throughout a single request lifecycle in Helidon 4. WeakHashMap
     * ensures automatic cleanup when Helidon releases the ServerRequest after
     * the response is committed -- no memory leak.
     *
     * <p>Synchronised externally only at creation time; all subsequent reads
     * are key-equal lookups on the same reference, which is safe.
     */
    private final java.util.WeakHashMap<ServerRequest, RequestContext> requestContexts =
        new java.util.WeakHashMap<>();

    private RequestContext getOrCreateContext(ServerRequest helidonReq,
                                              ServerResponse helidonRes) {
        synchronized (requestContexts) {
            return requestContexts.computeIfAbsent(helidonReq, k -> {
                var req = new HelidonRequest(helidonReq, this);
                var res = new HelidonResponse(helidonRes);
                return new RequestContext(req, res);
            });
        }
    }

    private record RequestContext(HelidonRequest req, HelidonResponse res) {}

    /**
     * Wraps a {@link Middleware} as a Helidon {@link Filter}.
     *
     * <p>Retrieves or creates the shared {@link RequestContext} for this request,
     * ensuring all filters and the route handler operate on the same
     * {@link HelidonRequest}/{@link HelidonResponse} pair. This is what allows
     * body-parsing filters to populate {@code req.body()} and have that state
     * visible to downstream handlers, and what allows post-processing filters
     * to set response headers after {@code next.run()}.
     */
    private Filter toHelidonFilter(Middleware middleware) {
        return (chain, routingReq, routingRes) -> {
            var ctx = getOrCreateContext((ServerRequest) routingReq,
                                        (ServerResponse) routingRes);
            try {
                middleware.handle(ctx.req(), ctx.res(), () -> {
                    try {
                        chain.proceed();
                    } catch (Exception e) {
                        dispatchError(e, ctx.req(), ctx.res());
                    }
                });
            } catch (Exception e) {
                dispatchError(e, ctx.req(), ctx.res());
            }
        };
    }

    /**
     * Wraps a path-scoped {@link Middleware} as a Helidon {@link Filter}.
     * Skips to {@code chain.proceed()} when the request path doesn't match.
     */
    private Filter toPathScopedFilter(String pathPrefix, Middleware middleware) {
        return (chain, routingReq, routingRes) -> {
            if (routingReq.path().path().startsWith(pathPrefix)) {
                var ctx = getOrCreateContext((ServerRequest) routingReq,
                                            (ServerResponse) routingRes);
                try {
                    middleware.handle(ctx.req(), ctx.res(), () -> {
                        try {
                            chain.proceed();
                        } catch (Exception e) {
                            dispatchError(e, ctx.req(), ctx.res());
                        }
                    });
                } catch (Exception e) {
                    dispatchError(e, ctx.req(), ctx.res());
                }
            } else {
                chain.proceed();
            }
        };
    }

    /**
     * Wraps a {@link Middleware} as a Helidon route {@link Handler}.
     *
     * <p>Retrieves the same {@link RequestContext} that was created when the
     * first filter ran -- the body-parsing middleware will already have populated
     * {@code req.body()}, {@code req.bodyBytes()}, etc. on this instance.
     */
    private Handler toHelidonHandler(Middleware middleware) {
        return (helidonReq, helidonRes) -> {
            var ctx = getOrCreateContext(helidonReq, helidonRes);
            try {
                middleware.handle(ctx.req(), ctx.res(), () -> {});
            } catch (Exception e) {
                dispatchError(e, ctx.req(), ctx.res());
            }
        };
    }

    // -- Path Translation ------------------------------------------------------

    /** Delegates to {@link PathUtils#toHelidonPath(String)}. */
    static String toHelidonPath(String expressPath) {
        return PathUtils.toHelidonPath(expressPath);
    }

    // -- Middleware Composition ------------------------------------------------

    /**
     * Composes a varargs array of {@link Middleware} left-to-right into a single
     * {@link Middleware} using {@link Middleware#then(Middleware)}.
     *
     * <p>{@code compose(mw1, mw2, mw3)} produces {@code mw1.then(mw2.then(mw3))}.
     * {@code mw1} receives the composed {@code mw2 -> mw3} as its {@code next}.
     * Calling {@code next.run()} in {@code mw1} executes {@code mw2}, and so on.
     *
     * <ul>
     *   <li>Single-element array -> returns that element unchanged</li>
     *   <li>Empty array -> returns {@link Middleware#NOOP}</li>
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

    // -- Lifecycle Guards ------------------------------------------------------

    private void assertNotStarted(String method) {
        if (started.get()) {
            throw new IllegalStateException(
                method + " must be called before app.listen(). " +
                "Application configuration is locked once the server starts.");
        }
    }

    // -- Internal Record Types -------------------------------------------------

    /** A registered filter -- path is null for global scope. */
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

    /** A registered WebSocket endpoint. */
    private record WsEndpoint(String path, io.cafeai.core.routing.WsHandler handler) {}

    // -- WebSocket Adapter -----------------------------------------------------

    /**
     * Adapts a CafeAI {@link io.cafeai.core.routing.WsHandler} to Helidon's
     * {@code WsListener} interface.
     *
     * <p>Each {@code WsListener} instance is created once per registered path
     * and shared across all connections to that path. The {@code WsHandler}
     * must be thread-safe (all default implementations are stateless, so this
     * holds out of the box).
     */
    private static io.helidon.websocket.WsListener toHelidonWsListener(
            io.cafeai.core.routing.WsHandler handler) {
        return new io.helidon.websocket.WsListener() {

            @Override
            public void onOpen(io.helidon.websocket.WsSession helidonSession) {
                handler.onOpen(new HelidonWsSession(helidonSession));
            }

            @Override
            public void onMessage(io.helidon.websocket.WsSession helidonSession,
                                  String text, boolean last) {
                handler.onMessage(new HelidonWsSession(helidonSession), text);
            }

            @Override
            public void onMessage(io.helidon.websocket.WsSession helidonSession,
                                  io.helidon.common.buffers.BufferData buffer, boolean last) {
                handler.onBinaryMessage(new HelidonWsSession(helidonSession),
                    buffer.readBytes());
            }

            @Override
            public void onClose(io.helidon.websocket.WsSession helidonSession,
                                int status, String reason) {
                handler.onClose(
                    new HelidonWsSession(helidonSession),
                    status,
                    reason);
            }

            @Override
            public void onError(io.helidon.websocket.WsSession helidonSession,
                                Throwable error) {
                handler.onError(new HelidonWsSession(helidonSession), error);
            }
        };
    }

    private static final class HelidonWsSession
            implements io.cafeai.core.routing.WsSession {

        private final io.helidon.websocket.WsSession delegate;
        private final String id;

        HelidonWsSession(io.helidon.websocket.WsSession delegate) {
            this.delegate = delegate;
            this.id = Integer.toHexString(System.identityHashCode(delegate));
        }

        @Override
        public void send(String message) {
            delegate.send(message, true);
        }

        @Override
        public void send(byte[] data) {
            delegate.send(io.helidon.common.buffers.BufferData.create(data), true);
        }

        @Override
        public void close(int code, String reason) {
            delegate.close(code, reason);
        }

        @Override
        public String id() { return id; }

        @Override
        public boolean isOpen() { return true; }
    }
}
