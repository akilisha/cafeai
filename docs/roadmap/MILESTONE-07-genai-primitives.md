# MILESTONE-07: Gen AI Primitives — CafeAI-Native API

**Roadmap:** ROADMAP-07  
**Modules:** `cafeai-core`, `cafeai-memory`, `cafeai-rag`, `cafeai-tools`, `cafeai-agents`, `cafeai-guardrails`, `cafeai-observability`, `cafeai-security`, `cafeai-streaming`  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Module | Status | Completed |
|---|---|---|---|---|
| Phase 1 | `app.ai()` — LLM provider registration | `cafeai-core` | 🔴 Not Started | — |
| Phase 2 | `app.system()` + `app.template()` | `cafeai-core` | 🔴 Not Started | — |
| Phase 3 | `app.memory()` — tiered context memory | `cafeai-memory` | 🔴 Not Started | — |
| Phase 4 | `app.vectordb()` + `app.embed()` + `app.ingest()` + `app.rag()` | `cafeai-rag` | 🔴 Not Started | — |
| Phase 5 | `app.tool()` + `app.mcp()` | `cafeai-tools` | 🔴 Not Started | — |
| Phase 6 | `app.chain()` — named composable pipelines | `cafeai-core` | 🔴 Not Started | — |
| Phase 7 | `app.guard()` — guardrails as middleware | `cafeai-guardrails` | 🔴 Not Started | — |
| Phase 8 | `app.agent()` + `app.orchestrate()` | `cafeai-agents` | 🔴 Not Started | — |
| Phase 9 | `app.observe()` + `app.eval()` | `cafeai-observability` | 🔴 Not Started | — |
| Phase 10 | Security layer | `cafeai-security` | 🔴 Not Started | — |

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

> **How to use this section:**  
> This is the most likely roadmap to require design updates as AI libraries
> evolve rapidly. When Langchain4j releases a breaking change, when a new
> vector DB becomes the preferred option, when a better serialization format
> for off-heap memory emerges — record it here with date and rationale.
> This document is a living audit trail, not a static plan.

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
| Phase 6–7 complete | — | — | — |
| Phase 8 complete | — | — | — |
| Phase 9–10 complete | — | — | — |
| MILESTONE-07 closed | — | — | — |

---

## Notes & Observations

> **Phase 3 (Memory) is the most technically novel phase in the entire project.**
> FFM `MemorySegment` for off-heap session storage with FlatBuffers serialization
> is not a well-trodden path. Budget extra time. Document findings thoroughly —
> this is blog post material.
>
> **Phase 5 (MCP) — the protocol-first decision.**
> CafeAI implements MCP as a protocol client via Helidon WebClient rather than
> adopting a third-party MCP library. This is a deliberate architectural decision
> (see SPEC.md). If this decision is ever revisited, record it here with full
> rationale.
>
> **Phase 8 (Agents) — Structured Concurrency is load-bearing.**
> The multi-agent orchestration design depends on Java 21 `StructuredTaskScope`.
> Verify with JFR profiling that scopes are correctly nested and virtual threads
> are being used (not platform threads). Any deviation must be documented here.
>
> **Phase 9 (Observability) — the production proof.**
> When Phase 9 is complete, CafeAI has a production-grade AI observability story
> that no other Java AI framework currently offers at this level of detail.
> This phase's completion is a significant project milestone worth a blog post.
