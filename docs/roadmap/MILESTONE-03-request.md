# MILESTONE-03: `Request` — `req` Object

**Roadmap:** ROADMAP-03  
**Module:** `cafeai-core`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | Core identity properties | 🔴 Not Started | — |
| Phase 2 | Parameters, query & body | 🔴 Not Started | — |
| Phase 3 | Headers & content negotiation | 🔴 Not Started | — |
| Phase 4 | Cookies & cache freshness | 🔴 Not Started | — |
| Phase 5 | Route info & range | 🔴 Not Started | — |
| Phase 6 | CafeAI extensions (stream, attributes) | 🔴 Not Started | — |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

_Nothing completed yet._

---

## In-Progress Items

_Nothing in progress yet._

---

## Decisions & Design Updates

_No decisions recorded yet._

---

## Blockers & Issues

_No blockers recorded yet._

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1–2 complete | — | — | — |
| Phase 3–4 complete | — | — | — |
| Phase 5–6 complete | — | — | — |
| MILESTONE-03 closed | — | — | — |

---

## Notes & Observations

> **Key watch point:** `req.attribute()` is the primary data carrier between
> middleware and handlers. It replaces JavaScript's dynamic property assignment
> (`req.user = ...`). The typed retrieval API must be ergonomic — test it with
> real middleware chains during Phase 6 to validate the design holds up.
