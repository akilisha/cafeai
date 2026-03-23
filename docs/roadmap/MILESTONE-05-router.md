# MILESTONE-05: `Router` — Standalone Router Object

**Roadmap:** ROADMAP-05  
**Module:** `cafeai-core`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | Factory & lifecycle | 🔴 Not Started | — |
| Phase 2 | `router.METHOD()` HTTP verbs | 🔴 Not Started | — |
| Phase 3 | `router.use()` middleware & nesting | 🔴 Not Started | — |
| Phase 4 | `router.param()` | 🔴 Not Started | — |
| Phase 5 | `router.route()` fluent builder | 🔴 Not Started | — |
| Phase 6 | Composition patterns & validation | 🔴 Not Started | — |

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
| MILESTONE-05 closed | — | — | — |

---

## Notes & Observations

> **`mergeParams` is deceptively complex.** When `mergeParams=true`,
> parent path parameters must be merged into the child router's `req.params`.
> This interacts with `router.param()` callbacks — parent param callbacks
> may need to fire for the merged params. Test this combination thoroughly
> before closing Phase 4.
