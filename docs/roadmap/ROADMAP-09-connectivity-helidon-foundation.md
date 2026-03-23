# ROADMAP-09: Connectivity Protocols & Helidon Foundation Layer

**Covers:** `app.ws()`, `app.sse()`, `app.grpc()`, `app.helidon()`,  
`HelidonConfigurator`, `WebSocket`, `SseEmitter`, gRPC binding  
**Modules:** `cafeai-core`, `cafeai-streaming` (extended)  
**ADR Reference:** ADR-008  
**Depends On:** ROADMAP-01 Phase 1, ROADMAP-04 Phase 7 (`res.stream()`)  
**Status:** 🔴 Not Started

---

## Objective

Implement the full connectivity layer defined in ADR-008. This covers three
connectivity protocols (WebSockets, SSE persistent connections, gRPC) and the
`app.helidon()` foundation gateway that exposes Helidon SE's operational
capabilities without Express pretence.

The guiding principle throughout: *speak Express when translating Express,
speak Helidon when in Helidon territory, never speak neither.*

---

## Phases

---

### Phase 1 — `app.ws()` — WebSocket Endpoints

**Goal:** Express-adjacent WebSocket registration backed by Helidon SE's
native JSR-356 WebSocket support.

**Module:** `cafeai-core` + `cafeai-streaming`

#### Input
- Helidon SE WebSocket server support
- `CafeAIApp` routing integration

#### Tasks

- [ ] Define `WebSocketHandler` functional interface:
  ```java
  @FunctionalInterface
  public interface WebSocketHandler {
      void handle(WebSocket socket);
  }
  ```
- [ ] Define `WebSocket` interface — the object passed to every WS handler:
  ```java
  public interface WebSocket {
      // Inbound
      void onMessage(Consumer<String> handler);
      void onBinaryMessage(Consumer<byte[]> handler);
      void onClose(Runnable handler);
      void onError(Consumer<Throwable> handler);
      void onPing(Consumer<byte[]> handler);

      // Outbound
      void send(String message);
      void sendBinary(byte[] data);
      void ping(byte[] data);
      void close();
      void close(int code, String reason);

      // Metadata
      String sessionId();
      String path();
      String params(String name);        // path params from ws route
      String query(String name);         // query params
      String header(String name);        // upgrade request headers
      boolean isOpen();

      // AI extension
      void streamResponse(Publisher<String> tokens); // stream LLM tokens over WS
  }
  ```
- [ ] Implement `app.ws(String path, WebSocketHandler handler)`
- [ ] Path parameter syntax: `:id` → `{id}` (same translation as HTTP routes)
- [ ] `sessionId()` — unique per-connection ID, generated on upgrade
- [ ] `streamResponse()` — streams tokens as individual WS text messages,
  sends `[DONE]` sentinel on completion
- [ ] Internally maps to Helidon SE `WebSocketRouting.builder().endpoint(path, endpoint)`
- [ ] On client disconnect: `onClose` fires, `isOpen()` returns false
- [ ] On server-side `socket.close()`: sends WS close frame, `onClose` fires

#### Output
```java
// Echo server
app.ws("/echo", (socket) -> {
    socket.onMessage(msg -> socket.send("echo: " + msg));
    socket.onClose(() -> log.info("Connection closed: {}", socket.sessionId()));
});

// AI conversation over persistent WebSocket
app.ws("/conversation/:sessionId", (socket) -> {
    socket.onMessage(msg -> {
        // Tokens stream back over the same persistent connection
        socket.streamResponse(app.prompt(msg));
    });
    socket.onClose(() ->
        memory.persist(socket.params("sessionId")));
});
```

#### Acceptance Criteria
- [ ] WebSocket connection established and maintained
- [ ] `onMessage` fires for each text message received
- [ ] `socket.send()` delivers message to client
- [ ] `onClose` fires on client disconnect
- [ ] `onClose` fires on server `socket.close()`
- [ ] Path parameters accessible via `socket.params()`
- [ ] `socket.streamResponse()` delivers tokens as individual WS messages
- [ ] `[DONE]` sentinel sent on stream completion
- [ ] `sessionId()` unique per connection
- [ ] 1000 concurrent WebSocket connections maintained without resource exhaustion
- [ ] Unit tests with mock WebSocket client
- [ ] Integration test: full conversation round-trip over WebSocket
- [ ] Load test: 1000 concurrent connections

---

### Phase 2 — `app.sse()` — Persistent SSE Connection Endpoints

**Goal:** Server-initiated persistent event stream endpoints — distinct from
`res.stream()` which is response-level streaming within a request lifecycle.

**Module:** `cafeai-core` + `cafeai-streaming`

#### Input
- Helidon SE SSE server support
- `res.stream()` already implemented (ROADMAP-04 Phase 7)

#### Critical Distinction (must be preserved in all documentation):

| | `res.stream()` | `app.sse()` |
|---|---|---|
| Pattern | Response-level | Connection-level |
| Trigger | Client HTTP POST | Client GET + SSE subscribe |
| Lifecycle | Request → stream → close | Open → server pushes → close when done |
| Use case | LLM chat completion | Agent progress, live updates, event feeds |
| Initiation | Client sends message | Server decides when to push |

#### Tasks

- [ ] Define `SseEmitter` interface:
  ```java
  public interface SseEmitter {
      // Emit events
      void emit(String data);                        // unnamed event
      void emit(String event, String data);          // named event
      void emit(String event, String data, String id); // with event ID
      void emit(SseEvent event);                     // full control

      // Lifecycle
      void close();
      void onClose(Runnable handler);
      boolean isOpen();

      // Request context (from the SSE upgrade request)
      String params(String name);
      String query(String name);
      String header(String name);
      String sessionId();
  }
  ```
- [ ] Define `SseEvent` record:
  ```java
  public record SseEvent(
      String event,    // optional event type
      String data,     // required data payload
      String id,       // optional event ID (for reconnection)
      Duration retry   // optional client retry interval
  ) {}
  ```
- [ ] Implement `app.sse(String path, Consumer<SseEmitter> handler)`
- [ ] Set response headers automatically:
  - `Content-Type: text/event-stream`
  - `Cache-Control: no-cache`
  - `Connection: keep-alive`
  - `X-Accel-Buffering: no` (prevents nginx buffering)
- [ ] Wire SSE format: `event: <type>\ndata: <payload>\nid: <id>\n\n`
- [ ] Heartbeat: emit comment `:\n\n` every 30 seconds to keep connection alive
- [ ] On client disconnect: `onClose` fires, `isOpen()` returns false
- [ ] Path parameters: `:id` → `{id}` translation (same as HTTP routes)

#### Output
```java
// Live event feed
app.sse("/events/live", (emitter) -> {
    Subscription sub = eventBus.subscribe(event ->
        emitter.emit(event.type(), event.payload()));
    emitter.onClose(sub::cancel);
});

// Agent job progress updates
app.sse("/agent/:jobId/progress", (emitter) -> {
    String jobId = emitter.params("jobId");

    agentRuntime.onProgress(jobId, update ->
        emitter.emit("progress", update.toJson()));

    agentRuntime.onComplete(jobId, result -> {
        emitter.emit("complete", result.toJson());
        emitter.close();
    });

    agentRuntime.onError(jobId, err -> {
        emitter.emit("error", err.getMessage());
        emitter.close();
    });

    emitter.onClose(() -> agentRuntime.cancelCallbacks(jobId));
});
```

#### Wire format example:
```
event: progress\n
data: {"step": 2, "total": 5, "message": "Retrieving context"}\n
id: 001\n
\n
event: progress\n
data: {"step": 3, "total": 5, "message": "Generating response"}\n
id: 002\n
\n
event: complete\n
data: {"result": "Here is your answer..."}\n
id: 003\n
\n
```

#### Acceptance Criteria
- [ ] SSE connection established with correct headers
- [ ] Named events delivered in correct SSE format
- [ ] Event ID included when provided
- [ ] Heartbeat comment sent every 30 seconds on idle connections
- [ ] `onClose` fires on client disconnect
- [ ] `onClose` fires on server `emitter.close()`
- [ ] `nginx` buffering header (`X-Accel-Buffering: no`) set automatically
- [ ] Path parameters accessible via `emitter.params()`
- [ ] 500 concurrent SSE connections maintained without resource exhaustion
- [ ] Integration test: client subscribes, server emits sequence of events, client receives all
- [ ] Integration test: client disconnect triggers `onClose` cleanup
- [ ] Load test: 500 concurrent SSE connections

---

### Phase 3 — `app.grpc()` — gRPC Service Registration

**Goal:** First-class gRPC support — gRPC-native vocabulary via `app.grpc()`
registration. No Express pretence. Full Helidon gRPC capability.

**Module:** `cafeai-core`

#### Input
- Helidon SE gRPC server support
- Proto-generated service stubs (provided by the developer)

#### Tasks

- [ ] Implement `app.grpc(BindableService service)` — register a gRPC service
- [ ] Implement `app.grpc(ServerServiceDefinition definition)` — low-level registration
- [ ] Internally maps to Helidon SE `GrpcRouting.builder().service(service)`
- [ ] gRPC server runs on a configurable port (default: 9090, separate from HTTP 8080)
- [ ] Implement `app.grpc(GrpcOptions options)` — configure gRPC server:
  ```java
  app.grpc(GrpcOptions.builder()
      .port(9090)
      .tls(TlsConfig.fromKeystore("server.p12", "password"))
      .maxInboundMessageSize(10 * 1024 * 1024)  // 10MB
      .interceptor(new AuthInterceptor())
      .build())
  ```
- [ ] gRPC interceptors: `app.grpcInterceptor(ServerInterceptor)` — global interceptor
- [ ] gRPC health check: auto-registers gRPC health check protocol (`grpc.health.v1`)
- [ ] gRPC reflection: opt-in via `GrpcOptions.reflection(true)` (useful for grpcurl)

#### Output
```java
// Register gRPC services alongside HTTP routes
app.grpc(ChatServiceGrpc.bindService(new ChatServiceImpl(aiService)));
app.grpc(EmbeddingServiceGrpc.bindService(new EmbeddingServiceImpl(ragService)));
app.grpc(AgentServiceGrpc.bindService(new AgentServiceImpl(orchestrator)));

// gRPC server config
app.grpc(GrpcOptions.builder()
    .port(9090)
    .reflection(true)           // enable grpcurl discovery
    .interceptor(new JwtAuthInterceptor(jwtProvider))
    .build());

// HTTP routes coexist on port 8080
app.post("/chat", (req, res) -> res.stream(app.prompt(req.body("message"))));

app.listen(8080);
// gRPC listens on 9090 automatically
```

#### AI Use Cases (documented in code comments):
```java
// Proto service definitions for AI workloads:

// ChatService — bidirectional streaming conversation
// rpc Chat(stream ChatRequest) returns (stream ChatResponse);

// EmbeddingService — batch embedding generation
// rpc Embed(EmbedRequest) returns (EmbedResponse);
// rpc EmbedStream(stream EmbedRequest) returns (stream EmbedResponse);

// AgentService — invoke named agents via gRPC
// rpc RunAgent(AgentRequest) returns (stream AgentEvent);
```

#### Acceptance Criteria
- [ ] gRPC service reachable on configured port
- [ ] Unary RPC calls work correctly
- [ ] Server streaming RPC works correctly
- [ ] Client streaming RPC works correctly
- [ ] Bidirectional streaming RPC works correctly
- [ ] gRPC interceptor applies to all service methods
- [ ] gRPC health check responds correctly
- [ ] gRPC reflection works when enabled
- [ ] HTTP server (port 8080) and gRPC server (port 9090) coexist
- [ ] TLS configuration applies to gRPC server
- [ ] Integration test: gRPC client calls registered service end-to-end
- [ ] Integration test: bidirectional streaming gRPC call

---

### Phase 4 — `app.helidon()` — The Foundation Gateway

**Goal:** A fully capable, fully idiomatic `HelidonConfigurator` that provides
explicit, unapologetic access to Helidon SE's operational layer.
This is the permanent architectural escape hatch from the Express vocabulary.

**Module:** `cafeai-core`

#### Tasks

**`HelidonConfigurator` top-level:**
- [ ] Implement `app.helidon()` returning a `HelidonConfigurator` instance
- [ ] `HelidonConfigurator` is fluent — all methods return `this`
- [ ] `HelidonConfigurator` is the single access point for all Helidon operational concerns

**Health checks:**
- [ ] `helidon.health()` → `HealthConfigurator`
- [ ] `.liveness(String path)` — Kubernetes liveness probe endpoint
- [ ] `.readiness(String path)` — Kubernetes readiness probe endpoint
- [ ] `.startup(String path)` — Kubernetes startup probe endpoint
- [ ] `.check(String name, HealthCheck check)` — custom health check
- [ ] Built-in checks: `HelidonChecks.deadlock()`, `HelidonChecks.heapMemory()`,
  `HelidonChecks.diskSpace()`
- [ ] AI-specific checks: `CafeAIChecks.llmProvider()`, `CafeAIChecks.vectorStore()`,
  `CafeAIChecks.memoryStore()`

**Metrics:**
- [ ] `helidon.metrics()` → `MetricsConfigurator`
- [ ] `.endpoint(String path)` — Prometheus metrics endpoint
- [ ] `.vendor(boolean)` — include Helidon vendor metrics
- [ ] `.appName(String)` — application name tag on all metrics
- [ ] Auto-registers AI metrics: LLM call count, token usage, latency histograms,
  cache hit rate, guardrail trigger count

**OpenAPI:**
- [ ] `helidon.openApi()` → `OpenApiConfigurator`
- [ ] `.endpoint(String path)` — OpenAPI JSON spec endpoint
- [ ] `.ui(String path)` — Swagger UI endpoint
- [ ] `.info(String title, String version, String description)`
- [ ] `.security(SecurityScheme...)` — document security schemes

**Fault tolerance:**
- [ ] `helidon.faultTolerance()` → `FaultToleranceConfigurator`
- [ ] `.circuitBreaker(CircuitBreaker)` — global circuit breaker
- [ ] `.retry(Retry)` — global retry policy
- [ ] `.timeout(Duration)` — global request timeout
- [ ] `.bulkhead(Bulkhead)` — concurrent request limiter
- [ ] Per-route fault tolerance via middleware: `Middleware.circuitBreaker(...)`,
  `Middleware.retry(...)`

**Config:**
- [ ] `helidon.config()` → `ConfigConfigurator`
- [ ] `.source(ConfigSource)` — add ordered config source
- [ ] `.profile(String)` — activate named config profile
- [ ] Built-in sources: `ConfigSources.environmentVariables()`,
  `ConfigSources.file(String)`, `ConfigSources.classpath(String)`,
  `ConfigSources.systemProperties()`

**Security:**
- [ ] `helidon.security()` → `SecurityConfigurator`
- [ ] `.provider(SecurityProvider)` — register auth provider
- [ ] Built-in providers: `JwtProvider`, `HttpBasicAuthProvider`,
  `OidcProvider`, `ApiKeyProvider`
- [ ] `.protect(String path)` — require auth for path prefix

#### Output
```java
app.helidon()
   .health()
       .liveness("/health/live")
       .readiness("/health/ready")
       .check("vectordb", () -> vectorStore.isConnected())
       .check(CafeAIChecks.llmProvider())      // checks AI provider is reachable

   .metrics()
       .endpoint("/metrics")
       .vendor(true)

   .openApi()
       .endpoint("/openapi")
       .ui("/swagger-ui")
       .info("CafeAI Customer Service", "1.0.0", "AI-powered support")

   .faultTolerance()
       .circuitBreaker(CircuitBreaker.builder()
           .delay(Duration.ofSeconds(5))
           .failureRatio(0.5)
           .build())
       .retry(Retry.builder().retries(3).build())

   .config()
       .source(ConfigSources.environmentVariables())
       .source(ConfigSources.file("application.yaml").optional())

   .security()
       .provider(JwtProvider.builder()
           .jwksUri(app.helidon().config().value("auth.jwks-uri"))
           .build())
       .protect("/api");
```

#### Acceptance Criteria
- [ ] `app.helidon()` returns a non-null `HelidonConfigurator`
- [ ] Health check endpoints respond correctly (200 UP, 503 DOWN)
- [ ] Liveness/readiness/startup endpoints independently configurable
- [ ] Custom health check integrated into health response
- [ ] AI health checks (`CafeAIChecks.*`) report correct status
- [ ] Prometheus metrics endpoint returns valid Prometheus format
- [ ] AI metrics auto-registered and incrementing correctly
- [ ] OpenAPI spec generated correctly from registered routes
- [ ] Swagger UI renders at configured path
- [ ] Circuit breaker opens after configured failure threshold
- [ ] Circuit breaker closes after configured delay + successes
- [ ] Retry policy applied to failing operations
- [ ] Config sources merged in priority order
- [ ] JWT auth provider rejects invalid tokens
- [ ] JWT auth provider allows valid tokens
- [ ] Integration tests for each subsystem
- [ ] Integration test: full `app.helidon()` configuration end-to-end

---

### Phase 5 — Documentation and Patterns

**Goal:** The two-layer model documented so completely that future developers
understand immediately where to find each capability and why it is there.

**Module:** `docs/`

#### Tasks
- [ ] Write `docs/guide/CONNECTIVITY.md`:
  - `app.ws()` — full guide with examples
  - `app.sse()` vs `res.stream()` — the distinction explained with diagrams
  - `app.grpc()` — full guide including proto file setup
  - When to use each protocol (decision guide)
- [ ] Write `docs/guide/HELIDON-FOUNDATION.md`:
  - The two-layer model diagram (from ADR-008)
  - `app.helidon()` — full reference for each subsystem
  - Health checks guide — Kubernetes integration
  - Metrics guide — Prometheus + Grafana setup
  - Fault tolerance guide — patterns and anti-patterns
  - Config sources guide — ordered config, profiles, secrets
  - Security providers guide
- [ ] Update `README.md` — add connectivity and foundation layer sections
- [ ] Add `docs/guide/PROTOCOL-SELECTION.md`:
  - HTTP REST: stateless request/response
  - WebSocket: persistent bidirectional, AI conversations
  - SSE: persistent server-push, agent progress
  - gRPC: typed high-throughput, ML infrastructure integration

#### Acceptance Criteria
- [ ] Connectivity guide covers all three protocols end-to-end
- [ ] SSE distinction (`res.stream()` vs `app.sse()`) clearly explained
- [ ] Foundation guide covers all `app.helidon()` subsystems
- [ ] Protocol selection guide gives clear decision criteria
- [ ] All guides include working code examples
- [ ] Guides linked from `README.md`

---

## Phase Dependencies

```
Phase 1  (app.ws)       ← depends on ROADMAP-01 Phase 1 only
Phase 2  (app.sse)      ← depends on ROADMAP-04 Phase 7 (res.stream exists)
Phase 3  (app.grpc)     ← depends on ROADMAP-01 Phase 1 only
Phase 4  (app.helidon)  ← depends on ROADMAP-01 Phase 1 only
Phase 5  (docs)         ← depends on all prior phases
```

Phases 1, 3, and 4 are independently parallelisable after ROADMAP-01 Phase 1.
Phase 2 must wait for `res.stream()` to be complete.

---

## New Module Required: `cafeai-cdi` Addition

Phase 3 (gRPC) introduces a new module dependency:

```groovy
// cafeai-core/build.gradle — new dependency for gRPC
implementation 'io.helidon.grpc:helidon-grpc-server'
implementation 'io.grpc:grpc-stub'
implementation 'io.grpc:grpc-protobuf'
```

This must be added before Phase 3 implementation begins.

---

## Definition of Done

- [ ] All five phases complete
- [ ] WebSocket, SSE, and gRPC all tested at load
- [ ] `app.helidon()` covers all six subsystems (health, metrics, openApi,
  faultTolerance, config, security)
- [ ] The two SSE patterns (`res.stream()` and `app.sse()`) clearly
  distinguishable in API, documentation, and tests
- [ ] Zero regression on existing HTTP routes after connectivity layer added
- [ ] MILESTONE-09.md updated to reflect completion
