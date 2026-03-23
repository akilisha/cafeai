# ROADMAP-06: `Middleware` — The Composability Engine

**Maps to:** Express middleware pattern — the `(req, res, next)` contract  
**Module:** `cafeai-core`  
**ADR Reference:** ADR-002, ADR-005 §2  
**Depends On:** ROADMAP-03, ROADMAP-04  
**Status:** 🔴 Not Started

---

## Objective

Implement the complete CafeAI middleware system — the universal unit of
composability. This covers the core `Middleware` interface and contract,
all built-in utility middlewares, the error-handling middleware variant,
and the composition operators (`then`, chaining). The middleware system
is what makes every AI concern — guardrails, memory, RAG, observability —
composable with every HTTP concern.

---

## Phases

---

### Phase 1 — `Middleware` Interface & Contract

**Goal:** The foundational middleware contract and pipeline execution engine.

#### Input
- `Middleware` interface (already defined)
- `Next` functional interface

#### Tasks
- [ ] Finalise `Middleware` interface: `handle(Request req, Response res, Next next)`
- [ ] Finalise `Next` functional interface: `void run()`
- [ ] Implement `Middleware.then(Middleware other)` — composition operator
- [ ] Implement the pipeline execution engine inside `CafeAIApp`:
  - Ordered chain of middlewares
  - `next.run()` advances to the next middleware
  - Not calling `next.run()` short-circuits the chain
  - Final `next.run()` call after all middlewares is a no-op (not an error)
- [ ] Virtual thread safety: each request executes its middleware chain on its own virtual thread
- [ ] Concurrent requests execute independent chains — no shared mutable state

#### Output
```java
Middleware logger = (req, res, next) -> {
    log.info("→ {} {}", req.method(), req.path());
    next.run();
    log.info("← {}", res.headersSent() ? "sent" : "pending");
};

Middleware auth = (req, res, next) -> {
    if (req.header("Authorization") == null) {
        res.sendStatus(401);
        return;                  // short-circuit — next.run() NOT called
    }
    next.run();
};

app.use(logger.then(auth));      // composed
```

#### Acceptance Criteria
- [ ] Middleware executes in registration order
- [ ] `next.run()` not called → chain stops, no further middleware executes
- [ ] `next.run()` called → next middleware executes
- [ ] Final `next.run()` in chain is safe (no exception)
- [ ] `Middleware.then()` composes two middlewares into one
- [ ] 1000 concurrent requests each run independent chains with no cross-contamination
- [ ] Unit tests for each pipeline behaviour
- [ ] Concurrency test: 1000 concurrent requests

---

### Phase 2 — Error-Handling Middleware

**Goal:** Four-argument error middleware variant — the Express error-handling pattern.

#### Tasks
- [ ] Define `ErrorMiddleware` interface: `handle(Request req, Response res, Throwable err, Next next)`
- [ ] Integrate error middleware into the pipeline: only invoked when an exception is thrown upstream
- [ ] `app.use(ErrorMiddleware)` registration
- [ ] `next.fail(Throwable)` — explicitly passes an error to the error middleware chain
- [ ] Unhandled exceptions in route handlers automatically routed to error middleware
- [ ] Default error handler: 500 response with error message (development) or generic message (production)
- [ ] Error middleware can call `next.run()` to pass to the next error handler

#### Output
```java
// Error-generating middleware
app.use((req, res, next) -> {
    if (someCondition) next.fail(new ServiceException("Downstream failure"));
    else next.run();
});

// Error handler
app.use((req, res, err, next) -> {
    log.error("Unhandled error", err);
    res.status(500).json(Map.of("error", err.getMessage()));
});
```

#### Acceptance Criteria
- [ ] Exception thrown in route handler routes to error middleware
- [ ] `next.fail(Throwable)` routes to error middleware
- [ ] Error middleware can recover (send response) OR pass forward (`next.run()`)
- [ ] Multiple error handlers execute in order until one sends a response
- [ ] Unhandled errors in production return generic 500 (no stack trace)
- [ ] Unhandled errors in development return detailed error (stack trace)
- [ ] Unit + integration tests for error propagation paths

---

### Phase 3 — Built-in HTTP Utility Middlewares

**Goal:** Common HTTP concerns as ready-to-use middleware instances.

#### Tasks
- [ ] Implement `Middleware.requestLogger()` — structured request/response logging
  Format: `[timestamp] METHOD /path → status (latency ms)`
- [ ] Implement `Middleware.cors()` — permissive CORS (development)
- [ ] Implement `Middleware.cors(CorsOptions)` — configurable CORS
  Options: `origin`, `methods`, `headers`, `credentials`, `maxAge(Duration)`
- [ ] Implement `Middleware.rateLimit(int requestsPerMinute)` — per-IP rate limiting
- [ ] Implement `Middleware.rateLimit(RateLimitOptions)` — configurable rate limiting
  Options: `limit`, `windowDuration`, `keyExtractor`, `onLimitReached`
- [ ] Implement `Middleware.timeout(Duration)` — request timeout
- [ ] Implement `Middleware.compress()` — gzip/deflate response compression
- [ ] Implement `Middleware.cookieParser()` — parses cookies into `req.cookies()`
- [ ] Implement `Middleware.cookieParser(String secret)` — with signed cookie support
- [ ] Implement `Middleware.session(SessionStore)` — session management
- [ ] `Middleware.json()` — see ROADMAP-01 Phase 2 (cross-reference)

#### Output
```java
app.use(Middleware.requestLogger())
app.use(Middleware.cors(CorsOptions.builder()
    .origin("https://myapp.com")
    .credentials(true)
    .build()))
app.use(Middleware.rateLimit(100))
app.use(Middleware.cookieParser("my-secret"))
app.use(Middleware.compress())
```

#### Acceptance Criteria
- [ ] `requestLogger()` logs method, path, status, and latency
- [ ] `cors()` sets correct `Access-Control-*` headers
- [ ] `cors()` with specific origin rejects non-matching origins
- [ ] `rateLimit()` returns 429 on breach with `Retry-After` header
- [ ] `timeout()` returns 408 on breach
- [ ] `compress()` gzips responses with correct `Content-Encoding` header
- [ ] `cookieParser()` populates `req.cookies()`
- [ ] Signed cookies verified by `cookieParser(secret)`
- [ ] Unit + integration tests for each middleware

---

### Phase 4 — Cost & Token Middleware

**Goal:** AI-specific cost control middleware — unique to CafeAI.

#### Tasks
- [ ] Implement `Middleware.tokenBudget(int maxTokensPerSession)` — per-session token cap
- [ ] Implement `Middleware.tokenBudget(TokenBudgetOptions)` — configurable
  Options: `maxTokensPerSession`, `maxTokensPerRequest`, `onBudgetExceeded`
- [ ] `Middleware.semanticCache(CacheStore)` — cache LLM responses for identical/near-identical prompts
  - Hash-based exact match cache
  - Vector similarity semantic cache (near-duplicate detection)
  - Cache hit: skip LLM call, return cached response
  - Cache miss: proceed to LLM, store result
- [ ] Token counting integrated with `req.attribute(Attributes.TOKEN_COUNT)`

#### Output
```java
app.use(Middleware.tokenBudget(TokenBudgetOptions.builder()
    .maxTokensPerSession(10_000)
    .maxTokensPerRequest(2_000)
    .onBudgetExceeded((req, res) -> res.status(429).json(Map.of("error", "token budget exceeded")))
    .build()))

app.use(Middleware.semanticCache(SemanticCache.inMemory()))
```

#### Acceptance Criteria
- [ ] Requests exceeding session token budget are rejected with 429
- [ ] Requests exceeding per-request token limit are rejected
- [ ] Token count accumulated correctly across multiple requests in a session
- [ ] Semantic cache returns cached response for exact prompt match
- [ ] Semantic cache returns cached response for near-duplicate prompt (above similarity threshold)
- [ ] Cache miss proceeds to LLM and stores result
- [ ] Unit + integration tests

---

### Phase 5 — Middleware Composition Patterns

**Goal:** Validate composition at scale and document patterns.

#### Tasks
- [ ] End-to-end test: 15-middleware chain (full AI pipeline)
- [ ] End-to-end test: mixed HTTP + AI middleware in same chain
- [ ] Performance test: 15-middleware chain, 1000 req/s sustained
- [ ] Document standard middleware composition recipes in `docs/`
- [ ] Validate the full CafeAI pipeline from SPEC.md §2.1 end-to-end:
  auth → rate limit → PII scrub → jailbreak → guardrails-pre → token budget →
  semantic cache → RAG → LLM → guardrails-post → hallucination → observe →
  memory write → streaming response

#### Acceptance Criteria
- [ ] Full 15-stage pipeline processes a request end-to-end
- [ ] Each middleware in the pipeline receives correct `req`/`res` state from upstream
- [ ] Pipeline sustains 1000 req/s with p99 latency under 50ms (excluding LLM call time)
- [ ] Memory stable under sustained load (no leaks from middleware chain execution)
- [ ] Composition pattern documentation written

---

## Definition of Done

- [ ] All five phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public middleware API members
- [ ] MILESTONE-06.md updated to reflect completion
