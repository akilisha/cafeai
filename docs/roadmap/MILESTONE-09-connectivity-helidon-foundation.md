# MILESTONE-09: Connectivity Protocols & Helidon Foundation Layer

**Roadmap:** ROADMAP-09  
**Modules:** `cafeai-core`, `cafeai-streaming` (extended)  
**Started:** —  
**Target:** —  
**Completed:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | `app.ws()` — WebSocket endpoints | 🔴 Not Started | — |
| Phase 2 | `app.sse()` — Persistent SSE connections | 🔴 Not Started | — |
| Phase 3 | `app.grpc()` — gRPC service registration | 🔴 Not Started | — |
| Phase 4 | `app.helidon()` — Foundation gateway | 🔴 Not Started | — |
| Phase 5 | Connectivity + foundation documentation | 🔴 Not Started | — |

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
> The connectivity layer introduces persistent connection state which is a
> fundamentally different concern from stateless HTTP request handling.
> When implementation surfaces unexpected behaviour — connection cleanup,
> resource leaks, backpressure under load — record it here with full detail.
> These are the lessons future contributors need.

---

## Blockers & Issues

_No blockers recorded yet._

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phase 1 (WebSocket) complete | — | — | — |
| Phase 2 (SSE connections) complete | — | — | — |
| Phase 3 (gRPC) complete | — | — | — |
| Phase 4 (app.helidon) complete | — | — | — |
| Phase 5 (documentation) complete | — | — | — |
| MILESTONE-09 closed | — | — | — |

---

## Notes & Observations

> **The SSE distinction is the most important conceptual boundary in this roadmap.**
> `res.stream()` and `app.sse()` serve fundamentally different purposes and
> must never be conflated in documentation, code comments, or API design.
> Every time a new contributor asks "what is the difference between these two?"
> it means the documentation failed. Record any such confusion here so the
> docs can be improved before closing Phase 5.
>
> Quick reference card (pin this to the codebase):
> ```
> res.stream()  →  client sends POST → server streams tokens → connection closes
> app.sse()     →  client connects GET → server pushes events independently → server closes
> app.ws()      →  client connects WS upgrade → both sides send messages → either side closes
> app.grpc()    →  typed RPC, server-side: unary / streaming / bidirectional
> ```

> **Phase 1 load test is non-negotiable before closing.**
> 1000 concurrent WebSocket connections is the minimum bar.
> Virtual threads are the mechanism that makes this feasible without a
> thread-per-connection model. Verify with JFR profiling that virtual
> threads are in use (not platform threads) before marking Phase 1 complete.
> If the load test reveals unexpected behaviour, record it here.

> **Phase 3 (gRPC) introduces proto compilation to the build.**
> The `protobuf` Gradle plugin must be added to `cafeai-core` or a new
> `cafeai-grpc` submodule. Decide before Phase 3 begins:
> - Option A: proto files in `cafeai-core/src/main/proto` — simple, single module
> - Option B: separate `cafeai-grpc` module — cleaner separation, more overhead
> Record the decision here with rationale.

> **Phase 4 (`app.helidon()`) is the most strategically important phase.**
> This is CafeAI's statement about what kind of framework it is: one that
> gives developers full access to the runtime it sits on, without apology
> and without hiding capability behind a false abstraction.
> `app.helidon()` must be fully capable — not a toy wrapper.
> If Helidon SE adds new operational capabilities in future versions,
> `app.helidon()` should expose them. This is a living API, not a frozen one.

> **`CafeAIChecks.llmProvider()` health check — implementation note.**
> This check should send a minimal, low-cost probe to the registered AI provider
> (e.g. a token count request, not a full completion). It must not generate
> tokens or incur meaningful cost. It must complete within 2 seconds.
> If the provider is Ollama (local), the check should verify the Ollama
> process is running and the model is loaded. Document the probe strategy
> for each provider when implementing.
