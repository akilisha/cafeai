# MILESTONE-04: `Response` — `res` Object

**Roadmap:** ROADMAP-04  
**Module:** `cafeai-core` + `cafeai-streaming`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | Core send methods | 🔴 Not Started | — |
| Phase 2 | Headers management | 🔴 Not Started | — |
| Phase 3 | Redirects & `res.format()` | 🔴 Not Started | — |
| Phase 4 | Cookies | 🔴 Not Started | — |
| Phase 5 | File responses | 🔴 Not Started | — |
| Phase 6 | `res.render()` + JSONP omission | 🔴 Not Started | — |
| Phase 7 | CafeAI streaming extensions | 🔴 Not Started | — |

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
| Phase 7 complete | — | — | — |
| MILESTONE-04 closed | — | — | — |

---

## Notes & Observations

> **Phase 7 is the most strategically important phase in this roadmap.**
> `res.stream()` is CafeAI's primary AI-native response primitive.
> Every chatbot and streaming AI endpoint depends on it.
> Prioritise getting the backpressure and disconnect-cancellation behaviour
> right. A resource leak here at scale is a serious production issue.
>
> **Phase 5 security note:** Path traversal protection in `res.sendFile()`
> must be tested with adversarial inputs (`../../../etc/passwd` etc.)
> before this phase is closed.
