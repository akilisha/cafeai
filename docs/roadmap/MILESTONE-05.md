# MILESTONE-05: `Router` — Composable Route Groups

**Roadmap:** ROADMAP-05
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `CafeAI.Router()` factory & lifecycle | 🟢 Complete | March 2026 |
| Phase 2 | `router.METHOD()` — HTTP verb routes | 🟢 Complete | March 2026 |
| Phase 3 | `router.use()` — middleware & nested routers | 🟢 Complete | March 2026 |
| Phase 4 | `router.param()` | 🟢 Complete | March 2026 |
| Phase 5 | `router.route()` fluent builder | 🟢 Complete | March 2026 |
| Phase 6 | Router composition patterns | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**All Phases (March 2026)**

- `CafeAI.Router()` — factory returning `SubRouter` instance
- `SubRouter` — implements `Router` interface; stores `RouteRegistration` and
  `MiddlewareRegistration` lists; expanded recursively at `listen()` time
- `Router.get/post/put/patch/delete/head/options/all(path, Middleware...)` — variadic
  `Middleware` handlers, composed via `CafeAIApp.compose()` before storage
- `Router.all(path, handlers...)` — registers composed handler for all 7 HTTP verbs
- `Router.use(Middleware...)` — registers as sub-router filter-scope middleware
- `Router.use(String path, Router subRouter)` — mounts sub-router; expanded into parent
  builder at `listen()` time with full prefix prepending
- `Router.param(String name, ParamCallback)` — route parameter pre-processor
- `RouteBuilderImpl` — fluent builder returned by `app.route(path)` and `router.route(path)`
- `app.use("/path", subRouter)` — triggers recursive `registerRoutes()` expansion in
  `CafeAIApp.buildRouting()` with correct path prefix propagation

---

## Decisions & Design Updates

**March 2026 — Eager compose at registration, not at request time**

`SubRouter.get(path, mw1, mw2, mw3)` calls `CafeAIApp.compose(handlers)` immediately at
registration time and stores the single composed `Middleware`. This means composition
happens once at startup, not on every request. The composed middleware is then registered
as a single Helidon `Handler` at `buildRouting()` time.

**March 2026 — Sub-router filter-scope middleware**

Middleware registered via `router.use(mw)` is treated as filter-scope for the sub-router's
prefix. When the parent expands the sub-router in `registerRoutes()`, these middlewares are
registered as `addFilter(toPathScopedFilter(prefix, mw))` on the parent builder.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| All phases complete | — | March 2026 | Delivered alongside ROADMAP-01 |
| Integration tests passing | — | March 2026 | Sub-router tests in CafeAIIntegrationTest |
| MILESTONE-05 closed | — | March 2026 | |
