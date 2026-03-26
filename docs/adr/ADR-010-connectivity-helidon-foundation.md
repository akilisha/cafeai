# ADR-008: Connectivity Protocols and the Helidon Foundation Layer

**Status:** Accepted  
**Date:** March 2026

---

## Context

CafeAI is built on Helidon SE — a framework that offers far more than HTTP
request/response handling. Helidon SE natively supports WebSockets, SSE,
gRPC, health checks, metrics, OpenAPI, fault tolerance, config sources, and
security providers. Express, by contrast, handles WebSockets and SSE via
third-party libraries and has no native gRPC, health, metrics, or operational
story at all.

This creates a real tension:

> *CafeAI's foundational philosophy is Express-parity at the API surface.
> But CafeAI sits on top of a runtime that is vastly more capable than
> anything Express was ever built on. How much of that capability should
> be surfaced? In what vocabulary? And where does the Express API end?*

This ADR answers that question permanently, with full precision, for every
category of Helidon capability.

---

## The Core Principle

> *Speak Express when there is an Express concept to translate.
> Speak Helidon when you are in Helidon territory.
> Never speak neither — every API surface must have a clear, justified home.*

The Express vocabulary has earned its place in CafeAI through deliberate
design (ADR-005). It must not be diluted by forcing Helidon idioms into it.
But Helidon's capabilities must not be hidden or hobbled by forcing Express
idioms onto concepts Express never touched. Both sides deserve respect.
The architecture's job is to give each side its proper territory.

---

## The Two-Layer Model

CafeAI's API surface is formally divided into two layers with a clear boundary
between them:

```
┌──────────────────────────────────────────────────────────────┐
│  THE CAFEAI SURFACE LAYER                                    │
│  Express vocabulary + AI-native extensions                   │
│                                                              │
│  HTTP:      app.get / app.post / app.use / req / res         │
│  AI:        app.ai / app.guard / app.rag / app.agent / etc.  │
│  WS:        app.ws()     ← Express-adjacent                  │
│  SSE:       app.sse()    ← Express-adjacent                  │
│  gRPC:      app.grpc()   ← gRPC-native via app registration  │
└──────────────────────────────┬───────────────────────────────┘
                               │
            app.helidon()  ←  the explicit, unapologetic escape hatch
                               │
┌──────────────────────────────▼───────────────────────────────┐
│  THE HELIDON FOUNDATION LAYER                                │
│  Helidon vocabulary — no Express pretence                    │
│                                                              │
│  app.helidon().health()          Health checks               │
│  app.helidon().metrics()         Metrics endpoints           │
│  app.helidon().openApi()         OpenAPI spec + Swagger UI   │
│  app.helidon().faultTolerance()  Circuit breakers, retries   │
│  app.helidon().config()          Config sources              │
│  app.helidon().security()        Security providers          │
└──────────────────────────────────────────────────────────────┘
```

`app.helidon()` is not a workaround. It is not a smell. It is a deliberate,
explicitly designed gateway that says: *"I am leaving the Express vocabulary
now and speaking Helidon directly."* A developer who reaches for it knows
exactly what they are doing and why.

---

## Category Analysis: Every Helidon Capability Formally Placed

### Category 1 — Express Has It, Helidon Has It: Translate Cleanly

**WebSockets and SSE** fall here. Express handles both via third-party
libraries (ws, socket.io, event-stream). Helidon SE has them natively.
Developer expectation exists on both sides. CafeAI exposes these with
Express-familiar registration syntax backed by Helidon's native implementation.

**Verdict:** Surface in the CafeAI surface layer with Express-adjacent syntax.

---

### Category 2 — Express Doesn't Have It, Helidon Has It, Gen AI Needs It

**gRPC** falls here. Express never supported gRPC. Helidon SE has a complete
gRPC implementation. And gRPC is genuinely relevant to AI workloads:

- Model serving infrastructure speaks gRPC (TensorFlow Serving, NVIDIA Triton,
  most MLOps platforms)
- Inter-agent communication in multi-agent architectures benefits from gRPC's
  typed, high-throughput, bidirectional streaming
- Embedding services, inference services, and vector store APIs increasingly
  expose gRPC endpoints

This is not surfacing Helidon capability for its own sake. There is a direct,
justified AI use case. CafeAI surfaces gRPC via `app.grpc()` — Express-adjacent
in registration *form*, gRPC-native in *content*.

**Verdict:** Surface in the CafeAI surface layer with gRPC-native vocabulary
wrapped in the `app.grpc()` registration pattern.

---

### Category 3 — Express Doesn't Have It, Helidon Has It, It's Infrastructure

**Health checks, metrics, OpenAPI, fault tolerance, config sources, security
providers** all fall here. Express applications historically delegated these
to surrounding infrastructure (nginx, Kubernetes, Datadog, API gateways).
Java enterprise applications have always owned these concerns themselves.

Forcing Express vocabulary onto these concerns would be dishonest — there is
no Express concept to translate. Hiding them entirely would waste one of
Helidon SE's greatest strengths. The right answer is `app.helidon()` — an
explicit, capable, fully expressive gateway to Helidon's operational layer.

**Verdict:** Surface exclusively via `app.helidon()`. Helidon vocabulary.
No Express pretence.

---

## The Connectivity Protocols in Detail

### WebSockets — `app.ws()`

Express pattern (via `ws` library):
```javascript
const wss = new WebSocketServer({ server })
wss.on('connection', (ws) => {
    ws.on('message', (data) => ws.send(`echo: ${data}`))
    ws.on('close', () => console.log('disconnected'))
})
```

CafeAI — Express-adjacent form, Helidon-native implementation:
```java
app.ws("/chat", (socket) -> {
    socket.onMessage(msg -> socket.send("echo: " + msg));
    socket.onClose(() -> log.info("Client disconnected"));
    socket.onError(err -> log.error("Socket error", err));
});
```

`app.ws(path, handler)` mirrors `app.get(path, handler)` in form. The handler
receives a `WebSocket` object instead of `req/res`. Helidon SE's WebSocket
support (JSR-356) powers it underneath. The developer never sees
`@ServerEndpoint` or `TyrusServerEndpoint`.

**AI extension — first-class WebSocket AI methods:**

WebSockets are the correct protocol for persistent AI conversations:
bidirectional, low latency, stateful across multiple turns. CafeAI's
`WebSocket` object carries AI-native methods:

```java
app.ws("/agent", (socket) -> {
    socket.onMessage(msg -> {
        // Stream LLM tokens back over the persistent WebSocket connection
        socket.streamResponse(app.prompt(msg));
    });
    socket.onClose(() -> memoryService.persist(socket.sessionId()));
});
```

---

### SSE — Two Patterns, Two Distinct Purposes

This is one of the most important distinctions in CafeAI's connectivity model.
There are two SSE patterns that serve fundamentally different purposes and must
not be conflated:

#### Pattern 1: `res.stream()` — Response-Level Streaming

Defined in ROADMAP-04 Phase 7. For single-turn AI responses over HTTP POST.
The client sends a request, receives a token stream, the connection closes
when the stream ends. This is the standard LLM chat completion pattern.

```java
app.post("/chat", (req, res) ->
    res.stream(app.prompt(req.body("message"))));
```

Lifecycle: request → stream tokens → connection closes.

#### Pattern 2: `app.sse()` — Connection-Level SSE Endpoint

For persistent, server-initiated event streams where the server pushes
events independently of client requests. A fundamentally different use case.

```java
app.sse("/events", (emitter) -> {
    // Server pushes events to client whenever they occur
    eventBus.subscribe(event -> emitter.emit(event.type(), event.data()));
    emitter.onClose(() -> eventBus.unsubscribe());
});

// AI use case: real-time agent progress updates
app.sse("/agent/:jobId/progress", (emitter) -> {
    String jobId = emitter.params("jobId");
    agentRuntime.onProgress(jobId, update ->
        emitter.emit("progress", update.toJson()));
    agentRuntime.onComplete(jobId, result -> {
        emitter.emit("complete", result.toJson());
        emitter.close();
    });
});
```

Lifecycle: connection opens → server pushes events indefinitely → connection
closes when server calls `emitter.close()` or client disconnects.

**The distinction matters architecturally.** `res.stream()` is within a request
lifecycle. `app.sse()` is an independent connection lifecycle. Neither replaces
the other. Both are needed. Both are first-class citizens in CafeAI.

---

### gRPC — `app.grpc()`

Express has no gRPC story. CafeAI embraces gRPC on its own terms — not by
forcing Express syntax onto a fundamentally different protocol, but by providing
a clean `app.grpc()` registration point that honours gRPC's own vocabulary
while fitting naturally into the CafeAI bootstrap pattern.

```java
// gRPC speaks its own vocabulary — and that is correct
app.grpc(ChatServiceGrpc.bindService(new ChatServiceImpl(aiService)));
app.grpc(EmbeddingServiceGrpc.bindService(new EmbeddingServiceImpl(ragService)));
app.grpc(AgentServiceGrpc.bindService(new AgentServiceImpl(orchestrator)));
```

The developer brings their proto-generated service stub. CafeAI binds it to
Helidon's gRPC server. No pretence. No forced Express syntax. `app.grpc()`
is Express-adjacent in *form* (registered on the app object, follows the
registration pattern) but gRPC-native in *content*.

**AI relevance of gRPC in CafeAI:**

| Use Case | Why gRPC |
|---|---|
| Model serving (Triton, TF Serving) | Standard gRPC protocol — direct integration |
| Inter-agent communication | Typed, high-throughput, bidirectional streaming |
| Embedding service | gRPC streaming for batch embedding pipelines |
| Internal CafeAI microservices | Efficient typed contracts between services |

---

## The Helidon Foundation Layer — `app.helidon()`

`app.helidon()` returns a `HelidonConfigurator` — a fluent builder that provides
full, idiomatic access to Helidon SE's operational capabilities. Every method on
`HelidonConfigurator` speaks Helidon. No Express idioms. No AI idioms. Pure
Helidon, exactly as Helidon intended it.

```java
app.helidon()
   // Health checks — Kubernetes liveness/readiness/startup probes
   .health()
       .liveness("/health/live")
       .readiness("/health/ready")
       .startup("/health/start")
       .check("database", () -> dbPool.isHealthy())
       .check("vectordb", () -> vectorStore.isConnected())

   // Metrics — Prometheus-compatible, Micrometer-backed
   .metrics()
       .endpoint("/metrics")
       .vendor(true)
       .appName("cafeai-service")

   // OpenAPI spec + Swagger UI
   .openApi()
       .endpoint("/openapi")
       .ui("/swagger-ui")
       .info("CafeAI Service", "1.0.0", "AI-powered API")

   // Fault tolerance — circuit breakers, retries, bulkheads, timeouts
   .faultTolerance()
       .circuitBreaker(CircuitBreaker.builder()
           .successThreshold(2)
           .requestVolumeThreshold(10)
           .failureRatio(0.5)
           .delay(Duration.ofSeconds(5))
           .build())
       .retry(Retry.builder()
           .retries(3)
           .delay(Duration.ofMillis(200))
           .build())

   // Config sources — ordered, mergeable
   .config()
       .source(ConfigSources.environmentVariables())
       .source(ConfigSources.file("application.yaml").optional())
       .source(ConfigSources.file("application-local.yaml").optional())

   // Security — authentication and authorisation providers
   .security()
       .provider(JwtProvider.builder()
           .jwksUri("https://auth.example.com/.well-known/jwks.json")
           .build())
       .provider(HttpBasicAuthProvider.builder()
           .userStore(userStore)
           .build());
```

This is Helidon speaking. It does not pretend to be Express. It does not need
to. A developer reaching for `app.helidon()` has consciously stepped into
Helidon's operational layer. The API honours that decision by being fully
expressive about what Helidon offers.

---

## The Boundary: Precisely Stated

| Concern | API location | Vocabulary |
|---|---|---|
| HTTP route registration | `app.get/post/put/patch/delete/all` | Express |
| HTTP middleware | `app.use()` | Express |
| Request properties | `req.*` | Express (translated per ADR-005) |
| Response methods | `res.*` | Express (translated per ADR-005) |
| AI infrastructure | `app.ai/guard/rag/memory/agent/etc.` | CafeAI-native |
| WebSocket endpoints | `app.ws()` | Express-adjacent |
| SSE response streaming | `res.stream()` | CafeAI-native (ROADMAP-04) |
| SSE persistent connections | `app.sse()` | Express-adjacent |
| gRPC service registration | `app.grpc()` | gRPC-native via app |
| Health checks | `app.helidon().health()` | Helidon-native |
| Metrics | `app.helidon().metrics()` | Helidon-native |
| OpenAPI | `app.helidon().openApi()` | Helidon-native |
| Fault tolerance | `app.helidon().faultTolerance()` | Helidon-native |
| Config sources | `app.helidon().config()` | Helidon-native |
| Security providers | `app.helidon().security()` | Helidon-native |
| CDI / DI | `cafeai-cdi` module | Java/CDI-native (ADR-006) |

---

## Why This Line Is Drawn Here and Not Elsewhere

The line is drawn at the **request handling boundary**.

Everything that directly touches how a developer defines routes, handles
requests, and sends responses belongs in the CafeAI surface layer and
speaks Express. This is where the developer's mental model lives, where
the Express familiarity delivers its value, and where the AI-native
extensions earn their place alongside HTTP primitives.

Everything that lives *beneath* or *beside* the request handling boundary —
connectivity protocols, operational concerns, infrastructure — belongs in
its own vocabulary. Forcing Express idioms onto health checks would be
absurd. Hiding Helidon's circuit breaker behind a fake `app.use()` wrapper
would be dishonest. Neither serves the developer.

`app.helidon()` is the explicit, clearly-labelled door between the two worlds.
It is not hidden. It is not apologetic. It is not a smell. It is a permanent
architectural feature — the acknowledgement that CafeAI sits on top of a
mature, capable runtime and developers deserve full access to it on its own
terms when they need it.

---

## The Full CafeAI Bootstrap — All Three Layers Composed

```java
var app = CafeAI.create();

// ── Helidon Foundation Layer ────────────────────────────────
app.helidon()
   .health().liveness("/health/live").readiness("/health/ready")
   .metrics().endpoint("/metrics")
   .faultTolerance().circuitBreaker(CircuitBreaker.builder().build())
   .config().source(ConfigSources.environmentVariables());

// ── CafeAI AI Layer ─────────────────────────────────────────
app.ai(OpenAI.gpt4o());
app.memory(MemoryStrategy.mapped());
app.vectordb(PgVector.connect(config));
app.embed(EmbeddingModel.local());
app.observe(ObserveStrategy.otel());
app.guard(GuardRail.pii());
app.guard(GuardRail.jailbreak());
app.system("You are a helpful customer service agent...");

// ── CafeAI Surface Layer — HTTP ─────────────────────────────
app.use(Middleware.requestLogger());
app.use(Middleware.json());
app.use(Middleware.rateLimit(60));

app.get("/health", (req, res) -> res.json(Map.of("status", "ok")));

// ── CafeAI Surface Layer — WebSocket ────────────────────────
app.ws("/agent", (socket) -> {
    socket.onMessage(msg -> socket.streamResponse(app.prompt(msg)));
    socket.onClose(() -> log.info("Agent session closed"));
});

// ── CafeAI Surface Layer — SSE persistent ───────────────────
app.sse("/agent/:jobId/progress", (emitter) -> {
    agentRuntime.onProgress(emitter.params("jobId"),
        update -> emitter.emit("progress", update.toJson()));
});

// ── CafeAI Surface Layer — HTTP POST with streaming ─────────
app.post("/chat", (req, res) ->
    res.stream(app.prompt(req.body("message"))));

// ── CafeAI Surface Layer — gRPC ─────────────────────────────
app.grpc(EmbeddingServiceGrpc.bindService(new EmbeddingServiceImpl(ragService)));

// ── Start ────────────────────────────────────────────────────
app.listen(8080, () -> System.out.println("☕ CafeAI brewing on :8080"));
```

Read that top to bottom. Every section is clearly labelled. Every line
is in its correct vocabulary. Nothing is forced. Nothing is hidden.
The developer can read any section and know exactly what world they are in.

---

## Consequences

- `app.ws()`, `app.sse()`, `app.grpc()` are new surface-layer methods
  documented in ROADMAP-09
- `app.helidon()` returns a `HelidonConfigurator` — a new class in
  `cafeai-core` that provides typed access to Helidon's operational layer
- The two SSE patterns (`res.stream()` and `app.sse()`) are explicitly
  distinguished in all documentation
- gRPC is a first-class CafeAI citizen with AI-specific justification
- The `app.helidon()` escape hatch is permanent and must never be
  deprecated or hidden
- ROADMAP-09 covers full implementation of this ADR

---

## Final Statement

> *CafeAI's Express API is the front door.
> Helidon SE is the entire building.
> The front door should look familiar.
> What's inside doesn't have to.*
>
> *`app.helidon()` is not the back door.
> It is the door to the rest of the building —
> clearly labelled, always open, never apologetic.*

---

*ADR-008 — CafeAI v0.1.0-SNAPSHOT*
