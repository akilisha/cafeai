# MILESTONE-01: `CafeAI` Factory & Built-in Middleware

**Roadmap:** ROADMAP-01
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `CafeAI.create()` + `listen()` | 🟢 Complete | March 2026 |
| Phase 2 | `CafeAI.json()` | 🟢 Complete | March 2026 |
| Phase 3 | `CafeAI.raw()` | 🟢 Complete | March 2026 |
| Phase 4 | `CafeAI.Router()` | 🟢 Complete | March 2026 |
| Phase 5 | `CafeAI.serveStatic()` | 🟢 Complete | March 2026 |
| Phase 6 | `CafeAI.text()` | 🟢 Complete | March 2026 |
| Phase 7 | `CafeAI.urlencoded()` | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — Core Bootstrap (March 2026)**

- `CafeAI` interface — full public API surface with all ROADMAP-01–07 primitives declared
- `CafeAIApp` — concrete Helidon SE 4.x implementation with virtual thread startup
- `CafeAIApp.RequestContext` — `WeakHashMap<ServerRequest, RequestContext>` ensures a single
  `HelidonRequest`/`HelidonResponse` pair flows through all filters and the route handler for
  each HTTP request — the load-bearing fix for body parsing and post-processing correctness
- `PathUtils` — Express `:param` → Helidon `{param}` translation (ADR-007)
- `CafeAIConfigurer`, `CafeAIModule`, `CafeAIRegistry` — full SPI layer (ADR-006)
- `Request`, `Response`, `Router`, `Route`, `Next`, `Middleware` — complete public interfaces
- `HelidonRequest`, `HelidonResponse` — Helidon 4.x adapters
- `SubRouter`, `RouteBuilderImpl` — sub-router and fluent route builder implementations
- `CafeAIAppTest` — 85 unit tests covering all acceptance criteria
- `PathUtilsTest` — 14 parameterised path translation tests
- `HelloCafeAI` — canonical runnable example

**Phases 2–7 — Body Parsers, Router, Static Server (March 2026)**

- `CafeAI.json()` / `JsonOptions` — full JSON body parsing with inflate, limit, strict mode
- `CafeAI.raw()` / `RawOptions` — raw `byte[]` body parsing
- `CafeAI.text()` / `TextOptions` — plain text body parsing with charset detection
- `CafeAI.urlencoded()` / `UrlEncodedOptions` — form body parsing, flat and bracket notation
- `CafeAI.serveStatic()` / `StaticOptions` — static file server: ETag, Last-Modified,
  Cache-Control, max-age, dotfile protection, path traversal prevention, extension fallback,
  index.html fallback, HEAD support, 24-type MIME table
- `req.body(Class<T>)` — typed Jackson deserialization via `convertValue()`
- `CafeAI.Router()` — standalone sub-router factory (ADR-009 — variadic `Middleware` handlers)
- `BuiltInMiddleware` — `cors()`, `requestLogger()`, `rateLimit()`, `tokenBudget()`

**ADR-009 — Variadic Handlers, filter/use Distinction, Post-processing Model (March 2026)**

- All route methods are variadic `Middleware...` — `BiConsumer<Request, Response>` removed
- `app.filter()` introduced for cross-cutting pre-processing (distinct from `app.use()`)
- `next.run()` blocks on virtual thread — post-processing is free, no async needed
- `Middleware.NOOP` sentinel for empty handler arrays
- `CafeAIApp.compose(Middleware[])` — left-to-right composition utility, `public`

---

## Decisions & Design Updates

**March 2026 — ADR-009: Everything is Middleware, unified**

Route handler type changed from `BiConsumer<Request, Response>` to `Middleware`.
`app.filter()` introduced for cross-cutting concerns distinct from `app.use()`.
Full rationale in `ADR-009-variadic-handlers-filter-use-postprocessing.md`.

**March 2026 — RequestContext WeakHashMap (critical correctness fix)**

Initial implementation created new `HelidonRequest`/`HelidonResponse` wrapper objects
in each `toHelidonFilter()` and `toHelidonHandler()` call. This meant body-parsing filters
populated state on an object the route handler never saw. Fixed by introducing
`RequestContext` — a `WeakHashMap<ServerRequest, RequestContext>` keyed on the Helidon
`ServerRequest` identity. First adapter call creates the pair; all subsequent calls retrieve
the same instance. `WeakMap` ensures automatic GC after Helidon releases the request.

**March 2026 — serveStatic vs static naming**

Method named `serveStatic()` rather than `static()` because `static` is a reserved keyword
in Java. Matches the established precedent from other Java web frameworks.

**March 2026 — HTTP 204/304 body prohibition**

`sendStatus(204)` and `sendStatus(304)` now call `helidonRes.send()` with no body.
Java's `HttpClient` (used in integration tests) correctly rejects a body on 204 responses
per the HTTP specification. All other status codes send the reason phrase as a text body.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1 complete | — | March 2026 | |
| Phases 2–7 complete | — | March 2026 | |
| Integration test suite | — | March 2026 | 43 real HTTP tests |
| MILESTONE-01 closed | — | March 2026 | All 99 tests passing |
