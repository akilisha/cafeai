# MILESTONE-02: `Application` — `app` Object

**Roadmap:** ROADMAP-02
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `app.locals` / scoped state | 🟢 Complete | March 2026 |
| Phase 2 | `app.mountpath` + mount event | 🟢 Complete | March 2026 |
| Phase 3 | HTTP verb methods | 🟢 Complete | March 2026 (delivered in ROADMAP-01) |
| Phase 4 | `app.use()` middleware mounting | 🟢 Complete | March 2026 (delivered in ROADMAP-01) |
| Phase 5 | `app.param()` | 🟢 Complete | March 2026 (delivered in ROADMAP-01) |
| Phase 6 | `app.route()` fluent builder | 🟢 Complete | March 2026 (delivered in ROADMAP-01) |
| Phase 7 | `app.set()` / settings | 🟢 Complete | March 2026 |
| Phase 8 | `app.engine()` / `app.render()` | 🟢 Complete | March 2026 |
| Phase 9 | `app.path()` | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — Application Locals (March 2026)**

- `app.local(key, value)` — thread-safe application-lifetime store (`ConcurrentHashMap`)
- `app.local(key)` — untyped retrieval; `null` if absent
- `app.local(key, Class<T>)` — typed retrieval; `ClassCastException` on mismatch
- `app.locals()` — unmodifiable snapshot, excludes `__cafeai.*` internal keys
- `Locals.java` — pre-defined constants for AI infrastructure keys:
  `AI_PROVIDER`, `MODEL_ROUTER`, `SYSTEM_PROMPT`, `MEMORY_STRATEGY`,
  `VECTOR_STORE`, `EMBEDDING_MODEL`, `OBSERVE_STRATEGY`
- `Locals.isInternal(key)` — identifies `__cafeai.*` keys for snapshot filtering

**Phase 2 + 9 — Mount Path and app.path() (March 2026)**

- `app.mountpath()` — returns mount path; empty string for root app
- `app.mountpaths()` — unmodifiable list for multi-path mounts
- `app.onMount(Consumer<CafeAI>)` — callback fired when sub-app is mounted
- `CafeAIApp.notifyMount(parent, path)` — public method called by parent on mount
- `app.path()` — full canonical path including all ancestor mount paths

**Phase 7 — Settings (March 2026)**

- `Setting.java` — 14-entry typed enum with Express-matching defaults:
  `ENV`, `TRUST_PROXY`, `X_POWERED_BY`, `CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`,
  `SUBDOMAIN_OFFSET`, `ETAG`, `JSON_ESCAPE_HTML`, `JSON_SPACES`, `QUERY_PARSER`,
  `VIEWS`, `VIEW_ENGINE`, `VIEW_CACHE`
- `app.set(Setting, value)` — stores value; `null` resets to default (removes key)
- `app.setting(Setting)` — retrieves value using `containsKey` sentinel (not `getOrDefault`)
- `app.setting(Setting, Class<T>)` — typed retrieval
- `app.enable(Setting)` / `app.disable(Setting)` — boolean-capable settings including
  `TRUST_PROXY` (Object type but boolean-default) via `isBooleanCapable()` check
- `app.enabled(Setting)` / `app.disabled(Setting)` — truthy/falsy evaluation
- Constructor initialises all non-null defaults at creation time

**Phase 8 — Template Engine (March 2026)**

- `ResponseFormatter.java` — `@FunctionalInterface` for template engines
- `ResponseFormatter.template()` — zero-dependency `{{variable}}` substitution,
  documented as **development/simple use only** — no loops, no escaping
- `ResponseFormatter.mustache()` — ServiceLoader discovery; throws `RenderException`
  with actionable dependency message if `cafeai-views-mustache` absent
- `ResponseFormatter.markdown()` — same ServiceLoader pattern
- `ResponseFormatter.RenderException` — clean error surface
- `ViewEngineProvider.java` (SPI) — interface for optional engine modules to implement
- `cafeai-views-mustache/` — full Mustache implementation module:
  `MustacheViewEngineProvider`, `MustacheResponseFormatter` (thread-safe factory cache),
  `META-INF/services` registration
- `app.engine(ext, formatter)` — registers formatter; normalises leading dot
- `app.render(view, locals, callback)` — callback form
- `app.render(view, locals)` — `CompletableFuture<String>` form
- `validateRenderConfig()` — eager synchronous validation; returns `failedFuture()`
  immediately so `isCompletedExceptionally()` assertions are race-free

---

## Decisions & Design Updates

**March 2026 — `setting()` uses `containsKey`, not `getOrDefault`**

`getOrDefault` cannot distinguish "key absent → use default" from "key present with null
value." Using `containsKey` makes the semantics explicit. `set(setting, null)` removes the
key and resets to default — null is not a storable value, it means "clear this setting."

**March 2026 — `isBooleanCapable()` for `enable()`/`disable()`**

`TRUST_PROXY` has `valueType = Object.class` (it accepts boolean or integer hop count)
so `isBoolean()` returns `false`, yet `app.enable(TRUST_PROXY)` is a valid Express-style
operation. `isBooleanCapable()` checks `isBoolean() OR (Object.class type AND boolean default)`
to allow this without widening the `Boolean.class` declaration.

**March 2026 — ServiceLoader pattern for optional view engines (ADR-009 pattern extension)**

No template engine is bundled in `cafeai-core`. `ResponseFormatter.mustache()` and
`markdown()` use `ServiceLoader<ViewEngineProvider>` — adding the JAR is the only
configuration needed. `template()` stays as a zero-dependency development formatter,
clearly documented as not production-ready. This resolves the tension between "no
endorsement by default" and "something must work out of the box." See DEVELOPER_GUIDE.md §8.

**March 2026 — CompletableFuture.failedFuture() for synchronous render errors**

`render(view, locals)` calls `validateRenderConfig()` synchronously before `supplyAsync()`.
Configuration errors (missing engine, missing VIEW_ENGINE setting) return
`CompletableFuture.failedFuture(e)` — already-completed exceptionally — rather than a
future that might complete before or after the caller checks it. This makes
`assertThat(future).isCompletedExceptionally()` deterministic in tests.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phases 1–2 complete | — | March 2026 | |
| Phases 7–9 complete | — | March 2026 | |
| Phase 8 (engine/render) complete | — | March 2026 | |
| ApplicationTest suite | — | March 2026 | 37 tests passing |
| MILESTONE-02 closed | — | March 2026 | |
