# ADR-009: Variadic Handlers, `filter`/`use` Distinction, and Post-Processing Model

**Status:** Accepted  
**Date:** March 2026  
**Supercedes:** Partial revision of ADR-002, ADR-005 §4 (route methods), ADR-005 §8 (app.use)

---

## Context

During ROADMAP-01 Phase 2 implementation, three architectural gaps were identified:

1. **Route methods accepted `BiConsumer<Request, Response>` instead of `Middleware`.** Express
   route methods accept arrays of middleware handlers. CafeAI's original API accepted a single
   handler function of a different type. This missed the most important ergonomic feature of
   Express routing and fragmented the type system.

2. **`app.use()` was doing too much.** It registered global middleware, path-scoped middleware,
   AND mounted sub-routers — all through the same method with no semantic distinction. This
   replicated Express's overloaded `app.use()` without inheriting its trade-offs deliberately.

3. **The Filter concept was leaking into the implementation.** An early implementation used
   Helidon's `Filter` / `FilterChain` types in method signatures and Javadoc visible to the
   application layer. This is a servlet-ism. It creates a second conceptual tier alongside
   `Middleware` and breaks ADR-002's invariant.

---

## Decision 1: Variadic Middleware Handlers on All Route Methods

All route registration methods now accept `Middleware` varargs:

```java
Router get(String path, Middleware... handlers);
Router post(String path, Middleware... handlers);
Router put(String path, Middleware... handlers);
Router patch(String path, Middleware... handlers);
Router delete(String path, Middleware... handlers);
Router head(String path, Middleware... handlers);
Router options(String path, Middleware... handlers);
Router all(String path, Middleware... handlers);
```

**The `BiConsumer<Request, Response>` type is removed entirely from the routing API.**

A route handler is a `Middleware` that does not call `next.run()`. An inline middleware in
a route array is a `Middleware` that does call `next.run()`. No other distinction exists.

```java
// Before (wrong — fragmented types):
app.get("/users/:id", (req, res) -> res.json(user));

// After (correct — unified type, Express-parity):
app.get("/users/:id", authenticate, authorize("admin"),
    (req, res, next) -> res.json(userService.find(req.params("id"))));
```

**Rationale:** Express's handler array is the single most powerful ergonomic feature of its
routing system. It allows per-route middleware composition without polluting the global
middleware stack. Omitting it was the biggest gap between CafeAI and Express.

Unifying on `Middleware` also eliminates the awkward question of "is this a handler or a
middleware?" The answer is always: it's a `Middleware`. If it calls `next.run()`, it passes
control forward. If it doesn't, it terminates.

---

## Decision 2: `app.filter()` for Cross-Cutting Pre-Processing

A new registration method `app.filter()` is introduced specifically for middleware that must
run before route dispatch in their own execution scope:

```java
// Global cross-cutting — runs for every request before any route is matched
CafeAI filter(Middleware... middlewares);

// Path-scoped cross-cutting — runs for requests matching the path prefix
CafeAI filter(String path, Middleware... middlewares);
```

`app.use()` retains its existing semantics — inline route pipeline composition and router
mounting — but is **no longer used for cross-cutting pre-processing**.

### The Semantic Distinction

| Registration | Execution Scope | Runs When | Use For |
|---|---|---|---|
| `app.filter(mw)` | Pre-route, own call frame | Before every route dispatch | Auth, body parsing, logging, guardrails, CORS |
| `app.filter("/api", mw)` | Pre-route, path-scoped | Before dispatch for `/api/**` | Scoped auth, scoped rate limiting |
| `app.use(mw)` | Inline route pipeline | During route matching | Route-specific middleware |
| `app.use("/v1", router)` | Path mount | Route composition | Sub-router mounting |
| `app.get("/x", mw...)` | Per-route pipeline | On matching route only | Route handlers with inline guards |

### Why `filter` Instead of Keeping Everything in `use`

Express's `app.use()` is deliberately overloaded. This is pragmatic in JavaScript but causes
real confusion: developers aren't always sure whether `app.use(mw)` runs before or after route
matching, or how it interacts with nested routers.

`app.filter()` makes the execution scope explicit at the call site. The developer writing:

```java
app.filter(CafeAI.json());
app.filter(Middleware.auth());
```

is unambiguously stating: "these run before anything else, for every request." There is no
ambiguity, no ordering question, no "does this run for sub-router routes too?"

This is not a departure from Express — it is an honest evolution of it. Developers already
mentally call global `app.use()` middleware "filters". CafeAI just names that concept
explicitly.

### Body Parsers Move to `filter`

Body parsers (`CafeAI.json()`, `CafeAI.raw()`, `CafeAI.text()`) are cross-cutting
pre-processors. They must run before any route handler reads `req.body()`. They belong in
`app.filter()`, not `app.use()`.

```java
// Before (ambiguous):
app.use(CafeAI.json());

// After (explicit):
app.filter(CafeAI.json());
```

Route-specific body parsing (only parse JSON for upload routes) is still possible inline:

```java
app.post("/upload", CafeAI.raw(), (req, res, next) -> {
    byte[] data = req.bodyBytes();
    // ...
});
```

---

## Decision 3: Post-Processing Is Not a Framework Primitive

Post-processing — code that executes after a route handler has responded — is not a separate
framework concept in CafeAI. No `app.afterFilter()`, no `ContainerResponseFilter`, no
`postHandle()` method.

Any `filter` middleware that calls `next.run()` and then executes code is a post-processor.
This is the onion/interceptor pattern, and it works naturally on Java 21 virtual threads:

```java
app.filter((req, res, next) -> {
    long start = System.nanoTime();
    next.run();                                          // blocks — entire chain executes
    log.info("{}ms", (System.nanoTime() - start) / 1_000_000);
});

app.filter((req, res, next) -> {
    MDC.put("requestId", UUID.randomUUID().toString()); // pre-processing
    try {
        next.run();                                      // blocks
    } finally {
        MDC.clear();                                     // post-processing — always runs
    }
});
```

### Why This Works (Virtual Thread Advantage)

In Node.js, `next()` is non-blocking. Post-processing requires `async/await`:

```javascript
// Node.js — awkward
app.use(async (req, res, next) => {
    const start = Date.now();
    await next();           // must await or post-processing runs immediately
    console.log(Date.now() - start);
});
```

In CafeAI on Java 21, `next.run()` is a real blocking call on a virtual thread. The call
stack parks, the downstream chain executes on the same virtual thread, and the stack unwinds
naturally when the chain completes. Post-processing is three natural lines with no framework
ceremony.

**This is a genuine CafeAI advantage over Express.** The virtual thread model makes the
interceptor pattern trivially correct by default. Developers get it for free without needing
to understand async execution models.

### Architecture Is the Developer's Concern

Whether pre/post processing happens in `filter` middleware or inside route handlers is an
architectural decision for the application developer. CafeAI provides the mechanism and
stays out of the way. The framework does not enforce where pre/post boundaries lie — it only
provides a clean way to express them when needed.

---

## Impact on Existing API

| Element | Before | After | Change |
|---|---|---|---|
| Route handler type | `BiConsumer<Request, Response>` | `Middleware` | Breaking — type changes |
| Route methods | `get(path, BiConsumer)` | `get(path, Middleware...)` | Breaking — varargs |
| Global middleware | `app.use(mw)` | `app.filter(mw)` | Semantic clarification |
| Path middleware | `app.use(path, mw)` | `app.filter(path, mw)` | Semantic clarification |
| Router mounting | `app.use(path, router)` | `app.use(path, router)` | No change |
| Body parsers | `app.use(CafeAI.json())` | `app.filter(CafeAI.json())` | Uses `filter` |
| Filter in impl | Helidon `Filter` in Javadoc | Private impl detail only | Encapsulation |
| Post-processing | Not supported | `next.run()` blocks naturally | Free via VT model |

---

## Implementation Notes

### Middleware Varargs Composition

When a route is registered with multiple handlers:
```java
app.get("/path", mw1, mw2, mw3);
```

`CafeAIApp` composes them left-to-right into a single `Middleware` chain before registering
with Helidon. `mw1` receives the composed `mw2 → mw3` as its `next`. Calling `next.run()`
in `mw1` executes `mw2`, which receives `mw3` as its `next`, and so on.

The composition uses `Middleware.then()`:
```java
// Effective composition:
Middleware composed = mw1.then(mw2).then(mw3);
```

A single Helidon `Handler` is registered for the route, wrapping the composed middleware.

### Filter Internal Wiring

`app.filter(mw)` middleware are internally wired as Helidon 4 `Filter` objects. This means
they execute in Helidon's filter chain, which runs before route matching. The `next.run()`
call in filter middleware corresponds to `chain.proceed()` in the Helidon filter. This
translation is entirely inside `CafeAIApp.buildRouting()` — no public API surface
references Helidon's `Filter` type.

### HelloCafeAI Updated

```java
var app = CafeAI.create();

// Cross-cutting pre-processing — explicit filter scope
app.filter(Middleware.requestLogger());
app.filter(CafeAI.json());
app.filter(Middleware.cors());
app.filter(GuardRail.pii());

// Routes — variadic middleware handlers
app.get("/health",
    (req, res, next) -> res.json(Map.of("status", "ok")));

app.get("/users/:id",
    authenticate,
    (req, res, next) -> res.json(userService.find(req.params("id"))));

app.post("/chat",
    Middleware.rateLimit(100),
    (req, res, next) -> res.stream(app.prompt(req.body("message"))));

app.listen(8080);
```
