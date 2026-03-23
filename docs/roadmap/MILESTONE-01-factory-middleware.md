# MILESTONE-01: `CafeAI` Factory & Built-in Middleware

**Roadmap:** ROADMAP-01  
**Module:** `cafeai-core`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🟡 In Progress

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `CafeAI.create()` + `listen()` | 🟢 Complete | March 2026 |
| Phase 2 | `CafeAI.json()` | 🔴 Not Started | — |
| Phase 3 | `CafeAI.raw()` | 🔴 Not Started | — |
| Phase 4 | `CafeAI.Router()` | 🔴 Not Started | — |
| Phase 5 | `CafeAI.static()` | 🔴 Not Started | — |
| Phase 6 | `CafeAI.text()` | 🔴 Not Started | — |
| Phase 7 | `CafeAI.urlencoded()` | 🔴 Not Started | — |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — ROADMAP-01 Phase 1 (March 2026)**

- `CafeAI` interface — full surface layer with all ROADMAP-01–07 primitives declared
- `CafeAIApp` — concrete Helidon SE implementation, Service Loader discovery, virtual thread startup
- `PathUtils` — Express `:param` → Helidon `{param}` translation (ADR-007)
- `CafeAIConfigurer`, `CafeAIModule`, `CafeAIRegistry` — full SPI layer (ADR-006)
- `Request`, `Response`, `Router`, `Route`, `Next`, `Middleware` — complete surface interfaces (ADR-005)
- `HelidonRequest`, `HelidonResponse` — Helidon 4.x adapters
- `BuiltInMiddleware` — `json()`, `cors()`, `requestLogger()`, `rateLimit()`, `tokenBudget()`
- `AiProvider`, `OpenAI`, `Anthropic`, `Ollama`, `ModelRouter` — AI provider layer
- `GuardRail` — full interface with stubs and regulatory/topic-boundary builders
- `MemoryStrategy` — all six rungs with `InMemoryStrategy` fully functional
- `ConversationContext`, `RedisConfig`, `CookieOptions`, `ContentMap` — supporting types
- `CafeAIAppTest` — 45 unit tests covering all Phase 1 acceptance criteria
- `PathUtilsTest` — 15 parameterised path translation tests
- `HelloCafeAI` — canonical runnable example updated to compile against live API
- `.gitignore`, `gradlew`, `gradlew.bat`, `GETTING-STARTED.md` — project scaffolding

---

## In-Progress Items

_Nothing in progress yet._

---

## Decisions & Design Updates

**March 2026 — PathUtils extraction**

During Phase 1 implementation, path translation logic was extracted from `CafeAIApp`
into a standalone `PathUtils` class. Rationale: `CafeAIApp` is package-private and
`toHelidonPath()` needed to be directly unit-testable without server infrastructure.
`PathUtils` is now the single source of truth for Express→Helidon path translation.
Impact: `PathUtilsTest` now has 15 exhaustive tests for the translation logic.

**March 2026 — Two Helidon 4.x API calls to verify**

`HelidonRequest.java` has two calls that need verification against live Helidon 4.x Javadoc:
1. Line ~174: `h.name()` in `headers()` forEach — may need `h.headerName().defaultCase()`
2. Line ~117: `pathParameters().toMap()` — verify exact method name on `Parameters` interface
Both are single-method-name fixes. IntelliJ red squiggles will pinpoint them immediately.

> **How to use this section:**  
> When a design decision is made during implementation that differs from the roadmap,
> record it here with date, rationale, and impact. This is the living audit trail.

---

## Blockers & Issues

_No blockers recorded yet._

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1 complete | — | — | — |
| Phase 2–3 complete | — | — | — |
| Phase 4 complete | — | — | — |
| Phase 5–7 complete | — | — | — |
| MILESTONE-01 closed | — | — | — |

---

## Notes & Observations

_Use this section for implementation notes, gotchas, performance observations,
and anything worth remembering for future phases._

---

## Session 2 — Compile Fixes (March 2026)

**8 compiler errors resolved:**

| # | Error | Fix |
|---|---|---|
| 1 | `*/json` terminates Javadoc comment | Changed to `"*" + "/json"` in `Request.java` |
| 2 | `HeaderName.create(field)` wrong API for response | Introduced `setHeader()` using `HeaderValues.create(HeaderName, String)` |
| 3 | `BuiltInMiddleware` not accessible cross-package | Made class and all factory methods `public` |
| 4 | `CafeAIApp` not accessible cross-package | Made class and `newInstance()` `public` |
| 5 | `WebServer.builder().routing()` wrong Helidon 4 API | Changed to `.addRouting()` |
| 6 | `headers().value("Host")` takes `HeaderName` not `String` | Changed to `headers().value(HeaderNames.HOST)` |
| 7 | `pathParameters().toMap()` doesn't exist in Helidon 4 | Changed to iterating `.names()` + `.first(name)` |
| 8 | `query().get(name)` doesn't exist in Helidon 4 | Changed to `.first(name)` |

**Import hygiene pass — all 30 files:**
- All fully-qualified references replaced with proper imports
- `CafeAIRegistry.java` — `Supplier` import fixed (bad `sed` insertion corrected)
- `HelidonResponse.java` — `Files`, `StandardCharsets`, `Duration`, `IOException` added
- `GuardRail.java` — `List`, `ArrayList`, `Request`, `Response`, `Next` added
- `Router.java` — `Next` added, FQ ParamCallback param replaced
- `Middleware.java` — `BuiltInMiddleware` import added, 5 FQ calls replaced
- `CafeAI.java` — `CafeAIApp` import added, FQ `create()` call replaced
- `MemoryStrategy.java` — `ConcurrentHashMap` import added
- `Response.java` — `Flow` import added for `Publisher`
- Test file — `ConversationContext`, `ContentMap`, `CookieOptions`, `AiProvider`, `Router`, `Middleware`, `Duration`, `Instant`, `ArrayList` all added

**Status after Session 2:** Project should compile clean after Gradle sync.
