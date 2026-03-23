# ADR-002: Everything is Middleware

**Status:** Accepted — Extended March 2026  
**Date:** March 2026  
**Extended:** March 2026 (variadic handlers, filter/use distinction, post-processing model)

---

## Context

CafeAI needs a composability model. An AI request pipeline has many cross-cutting concerns:
auth, rate limiting, PII scrubbing, guardrails, RAG, LLM calls, observability, memory writes,
streaming. These concerns need to be independently developable, testable, and deployable.

An early implementation introduced `Filter` / `FilterChain` concepts directly in the API and
implementation. This was identified as a servlet-ism — it introduced a second conceptual tier
alongside `Middleware` and broke the fundamental invariant that everything in CafeAI is a
middleware.

---

## Decision

**Every concern in CafeAI is expressed as a `Middleware`.** No other handler abstraction exists.

The `Middleware` functional interface is the single unit of composability across all concerns:

```java
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next);
}
```

Route handlers, body parsers, auth checks, guardrails, LLM calls, observability hooks — all
are `Middleware`. A terminating handler is simply a `Middleware` that does not call
`next.run()`.

### Corollary: Route Methods Are Variadic

Because handlers are `Middleware`, route registration methods accept an array of middleware
composing an inline per-route pipeline:

```java
// Express: app.get("/users/:id", authenticate, authorize("admin"), getUser)
app.get("/users/:id", authenticate, authorize("admin"), getUser);

// Each element is a Middleware. authenticate calls next.run(), getUser does not.
// The array is the per-route pipeline. Order is the architecture.
```

This is the most important ergonomic property of Express routing and was missing in the
original CafeAI API. It is now the canonical form.

```java
// Router method signatures:
Router get(String path, Middleware... handlers);
Router post(String path, Middleware... handlers);
Router put(String path, Middleware... handlers);
// ... all verb methods follow this pattern
```

The `BiConsumer<Request, Response>` type that was used for route handlers is removed entirely.
All routes speak `Middleware`. This unifies the type system completely.

### Corollary: Two Registration Surfaces, One Type

There are two ways to register middleware, reflecting two different execution scopes. Both
accept `Middleware`. Neither introduces a new type.

#### `app.filter(Middleware... middlewares)` — Cross-Cutting Pre-Processing

For concerns that must run unconditionally before any route is dispatched — body parsing,
authentication, rate limiting, PII scrubbing, CORS. These execute in a scope that is
architecturally separate from route dispatch:

```java
app.filter(CafeAI.json());                    // parse body before any route sees it
app.filter(Middleware.requestLogger());       // log every request
app.filter("/api", Middleware.auth());        // auth every /api/* request
app.filter(GuardRail.pii());                 // scrub PII from every prompt
```

Internally, `filter` middlewares are wired as Helidon 4 `Filter` objects — but this is an
implementation detail invisible to the application. The public API speaks `Middleware` only.

#### `app.use(Middleware... middlewares)` — Inline Route Pipeline

For concerns that are part of the route dispatch pipeline — composing routes, mounting
sub-routers, adding route-specific middleware inline. Executes within the route matching phase:

```java
app.use(CafeAI.Router());                    // mount a sub-router
app.use("/api/v1", apiRouter);              // path-scoped router mounting
app.get("/upload", CafeAI.raw(), handler);  // inline body parser for one route
```

The semantic distinction is explicit and deliberate:

| Method | Scope | Execution | Use for |
|---|---|---|---|
| `app.filter(mw)` | Global pre-route | Before dispatch, own call frame | Auth, logging, body parsing, guardrails |
| `app.filter(path, mw)` | Path pre-route | Before dispatch for matching paths | Scoped auth, scoped rate limiting |
| `app.use(mw)` | Inline route | Within route pipeline | Route-specific middleware |
| `app.use(path, router)` | Path mount | Route composition | Sub-router mounting |
| `app.get(path, mw...)` | Route | Per-method pipeline | Route handlers with inline middleware |

---

## Post-Processing — Not a Framework Primitive

Post-processing (code that runs after a route handler responds) is not a framework concept in
CafeAI. It does not require a separate API.

Any `filter` middleware that calls `next.run()` and then executes code afterwards is a
post-processor. This is the standard onion/interceptor pattern:

```java
// Timing middleware — logs AFTER the route handler completes
app.filter((req, res, next) -> {
    long start = System.nanoTime();
    next.run();                              // entire downstream chain executes here
    long elapsed = System.nanoTime() - start;
    log.info("{} {} — {}ms", req.method(), req.path(), elapsed / 1_000_000);
});
```

This works naturally because CafeAI runs on Java 21 virtual threads. `next.run()` is a
real blocking call — the stack unwinds cleanly after the route handler returns. Post-processing
middleware simply puts code after `next.run()`. There is no async/await complication, no
promise chaining, no callback hell.

**This is one of CafeAI's genuine advantages over Express.** In Node.js, post-processing
requires `async/await` and careful promise handling. In CafeAI, it's three lines:

```java
app.filter((req, res, next) -> {
    before();
    next.run();   // blocks until response is committed on a virtual thread
    after();      // runs after — natural, readable, no framework ceremony
});
```

The architecture of pre/post processing is the developer's concern. CafeAI provides the
mechanism (`next.run()` blocks) and stays out of the way.

---

## Rationale

The Express.js middleware pattern has been battle-tested for over a decade across millions of
production applications. It survives because:

1. **Mental model simplicity.** Every developer understands `(req, res, next)`.
2. **Composability is recursive.** Middleware can wrap middleware. Chains can contain chains.
3. **Order is explicit.** The sequence of registrations IS the architecture. It's readable.
4. **Independent testability.** Every middleware is a function. Every function is unit-testable
   in isolation with no framework setup.
5. **It maps perfectly to AI pipelines.** Auth, scrub, retrieve, generate, validate, log,
   cache — this is a sequential pipeline. This is middleware.

The extension decisions (variadic handlers, `filter` vs `use`, blocking `next.run()`) make
CafeAI's model *more explicit* than Express without being more complex. The developer gains:

- **Variadic handlers:** per-route pipelines without `app.use()`
- **`filter` vs `use`:** intentional vocabulary for execution scope
- **Blocking `next.run()`:** free post-processing without framework support

**The key insight:** AI pipelines were always middleware problems. CafeAI just names them that.

---

## Consequences

- `Middleware` is the only handler type. `BiConsumer<Request, Response>` is removed.
- All route methods are variadic: `get(path, Middleware... handlers)`.
- `app.filter()` is the canonical registration surface for cross-cutting pre-processing.
- `app.use()` is reserved for inline route pipeline composition and router mounting.
- Post-processing requires no framework mechanism — `next.run()` blocks on virtual threads.
- Helidon 4 `Filter` / `FilterChain` are internal implementation details, never in the public API.
- The pipeline is the documentation. Reading registrations top-to-bottom describes the entire
  request lifecycle.

---

## Implementation Notes

Internally, `CafeAIApp.buildRouting()` maps the two registration surfaces to Helidon 4
primitives:

- `app.filter(mw)` → `HttpRouting.Builder.addFilter(helidonFilter)` where `helidonFilter`
  adapts the `Middleware` lambda to Helidon's `Filter` functional interface
- `app.get(path, mw...)` → `HttpRouting.Builder.get(helidonPath, helidonHandler)` where
  `helidonHandler` composes the `Middleware` varargs left-to-right

The translation from CafeAI's `Middleware` to Helidon's `Filter` or `Handler` is a
**private concern of `CafeAIApp`**. No public API surface ever mentions `Filter`,
`FilterChain`, `Handler`, or any Helidon type.
