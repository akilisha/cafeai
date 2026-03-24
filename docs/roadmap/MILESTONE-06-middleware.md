# MILESTONE-06: `Middleware` — The Composability Engine

**Roadmap:** ROADMAP-06
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `Middleware` interface & pipeline engine | 🟢 Complete | March 2026 |
| Phase 2 | Error-handling middleware | 🟢 Complete | March 2026 |
| Phase 3 | Built-in HTTP utility middlewares | 🟢 Complete | March 2026 |
| Phase 4 | Cost & token middleware (AI-native) | 🟢 Complete | March 2026 |
| Phase 5 | Composition patterns & validation | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — Middleware Interface & Pipeline (March 2026)**

- `Middleware` — `@FunctionalInterface`: `void handle(Request, Response, Next)`
- `Middleware.NOOP` — sentinel constant; calls `next.run()` and nothing else
- `Middleware.then(other)` — default composition method: `this` runs, then `other`
- `Next` — `@FunctionalInterface`: `void run()` + default `void fail(Throwable)`
- `CafeAIApp.compose(Middleware[])` — left-to-right array composition via `then()`;
  empty array → `NOOP`; single element → identity (no wrapping); `public`
- `app.filter(Middleware...)` — cross-cutting pre-processing registration
- `app.filter(String path, Middleware...)` — path-scoped pre-processing
- `toHelidonFilter()` — adapts `Middleware` to Helidon `Filter`; `next.run()` → `chain.proceed()`
- `toHelidonHandler()` — adapts composed `Middleware` to Helidon `Handler`
- Post-processing: code after `next.run()` executes after full downstream chain —
  natural on virtual threads, no async needed (ADR-009 §3)

**Phase 2 — Error-handling Middleware (March 2026)**

- `ErrorMiddleware` — `@FunctionalInterface`: `void handle(Throwable, Request, Response, Next)`
  Deliberately a separate interface from `Middleware` — Express distinguishes by arity,
  Java cannot, so explicit typing is cleaner
- `app.onError(ErrorMiddleware)` — registers error handler; chains multiple in order
- `dispatchError(Throwable, Request, Response)` — builds right-to-left error chain;
  falls through to `defaultErrorHandler()` if no registered handler handles it
- `defaultErrorHandler()` — logs at ERROR, sends 500 JSON if response not yet committed
- `toHelidonFilter()` / `toHelidonHandler()` — both now catch exceptions and call
  `dispatchError()` so no exception silently swallows

**Phase 3 — Built-in HTTP Utility Middlewares (March 2026)**

- `Middleware.cors()` / `BuiltInMiddleware.cors()` — permissive CORS, development-ready
- `Middleware.requestLogger()` — structured logging with method, path, latency
- `Middleware.rateLimit(n)` — per-IP sliding window, returns 429 on breach
- `CafeAI.json()` / `jsonBody()` — JSON body parsing with inflate, limit, strict mode
- `CafeAI.raw()` / `rawBody()` — `byte[]` body parsing
- `CafeAI.text()` / `textBody()` — text body with charset detection
- `CafeAI.urlencoded()` / `urlEncodedBody()` — form parsing, flat and bracket notation
- `CafeAI.serveStatic()` / `serveStatic()` — full static file server

**Phase 4 — AI-native Token Middleware (March 2026)**

- `Middleware.tokenBudget(n)` — per-session token budget enforcer via `X-Session-Id` header;
  returns 429 with budget details on exhaustion

**Phase 5 — Composition Patterns (March 2026)**

- Variadic route handlers: `app.get(path, mw1, mw2, handler)` — inline per-route pipeline
- `Middleware.then()` — binary composition primitive; verified equivalent to `compose()`
- Post-processing onion model: pre → `next.run()` → post; verified in integration tests
- `CafeAIIntegrationTest` — 43 real HTTP tests proving the full composition model works
  end-to-end through a live Helidon SE server

---

## Decisions & Design Updates

**March 2026 — ErrorMiddleware as a separate interface (ADR-009 extension)**

Express uses `function(err, req, res, next)` and distinguishes error middleware by arity.
Java cannot do the same cleanly (overloading on functional interface would be confusing).
`ErrorMiddleware` is a separate `@FunctionalInterface` — explicit about its role, cannot be
accidentally registered as normal middleware.

**March 2026 — Virtual thread post-processing model confirmed**

The blocking `next.run()` model was validated end-to-end in integration tests. The
`lastRequestMs` `AtomicLong` captures timing set by a post-processing filter, proving
the value is set only after the full downstream chain completes. The original plan to
set a response header after `next.run()` was corrected — HTTP does not allow setting
headers after the body is committed.

**March 2026 — Phase 5 integration proof passed**

The full pipeline — `filter()` → route matching → variadic handler chain → post-processing —
was validated with 43 integration tests across all middleware types. No gaps were found
in the "everything is middleware" model. The composition patterns hold under real HTTP load.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1–2 complete | — | March 2026 | |
| Phase 3 complete | — | March 2026 | |
| Phase 4 complete | — | March 2026 | |
| Phase 5 integration proof | — | March 2026 | 43 tests green |
| MILESTONE-06 closed | — | March 2026 | |
