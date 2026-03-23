# MILESTONE-06: `Middleware` — The Composability Engine

**Roadmap:** ROADMAP-06  
**Module:** `cafeai-core`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `Middleware` interface & pipeline engine | 🔴 Not Started | — |
| Phase 2 | Error-handling middleware | 🔴 Not Started | — |
| Phase 3 | Built-in HTTP utility middlewares | 🔴 Not Started | — |
| Phase 4 | Cost & token middleware (AI-native) | 🔴 Not Started | — |
| Phase 5 | Composition patterns & validation | 🔴 Not Started | — |

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
| Phase 3 complete | — | — | — |
| Phase 4 complete | — | — | — |
| Phase 5 complete | — | — | — |
| MILESTONE-06 closed | — | — | — |

---

## Notes & Observations

> **Phase 5 is the integration proof.** The full 15-stage pipeline from SPEC.md §2.1
> must work end-to-end before this milestone closes. This is the moment where
> the "everything is middleware" philosophy either proves itself or reveals its gaps.
> Any gaps discovered here should be recorded as design updates above.
>
> **Phase 4 semantic cache** depends on the Vector API (Java 21) for cosine similarity.
> If the Vector API proves insufficient for the similarity computation, document the
> fallback approach (e.g. plain dot product via streams) here.
