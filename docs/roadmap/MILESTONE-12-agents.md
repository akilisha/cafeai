# MILESTONE-12: CafeAI Agents

**Roadmap:** ROADMAP-12  
**Module:** `cafeai-agents` (new), `cafeai-core`  
**Started:** —  
**Current Status:** 🟡 In Progress — Phase 1 complete, Phase 2 next

---

## Progress Tracker

Phases mirror [ROADMAP-12](ROADMAP-12-agents.md).

| Phase | Description | Module | Status |
|---|---|---|---|
| 1 | Prerequisites — Helidon 4.4, LangChain4j 1.11, `app.helidon()` | root, `cafeai-core` | 🟢 Complete |
| 2 | `cafeai-agents` module scaffold | `cafeai-agents` | 🔴 Not Started |
| 3 | `AgentConfig<T>` — fluent registration API | `cafeai-agents` | 🔴 Not Started |
| 4 | `AgentRegistry` — build/resolve agents, per-session memory, guardrails | `cafeai-agents` | 🔴 Not Started |
| 5 | `app.agent()` API surface (register + invoke) | `cafeai-core`, `cafeai-agents` | 🔴 Not Started |
| 6 | Capstone 4 — `invoice-processor` | capstone app | 🔴 Not Started |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised · 🔷 Deferred

---

## Measurable Outputs

| Output | How Measured |
|---|---|
| `app.agent(name, Interface.class)` compiles and registers | Startup log shows agent line |
| `app.agent(name, Interface.class, sessionId).method(...)` invokes the `AiService` | Returns the interface's return type |
| Session memory threads into the agent | Second call in same session has memory of the first |
| Guardrail blocks a jailbreak before the agent's LLM is called | `GuardRailViolationException` thrown |
| Observability trace fires on agent invocation | Console trace shows agent execution |
| Supervisor pattern works — an agent calls another agent as a `@Tool` | Capstone 4 `ApprovalAgent` scenario |
| `cafeai-agents` published to Maven Central | Part of the 0.2.x release |

---

## Key Decisions Recorded

**Why CafeAI does not own the agent loop:**
The agent loop — reasoning steps, tool dispatch, chat memory, termination conditions — is
LangChain4j `AiServices`' domain. CafeAI calls `AiServices.builder()` directly (not Helidon's
`@Ai.Agent` annotation, which needs Helidon Inject), pre-wires the common path, and exposes the
full builder via `.configure()`. CafeAI adds an HTTP identity, session threading, guardrail
protection, and observability. The loop itself is opaque to CafeAI.

**Why the rejected alternatives matter:**
Three designs were explored: Chains/Steps (removed — duplicated middleware), building a
workflow orchestrator (rejected — reinventing what Helidon is already building), and Temporal
as backing execution engine (explored — sound design, wrong question). The rejection path is
documented in SPEC.md §11.2. Understanding what was rejected is as important as understanding
what was chosen.

**Why `app.agent()` follows the same pattern as `app.memory()`, `app.guard()`, etc.:**
The naming philosophy requires consistency. A developer who knows
`app.memory(MemoryStrategy.inMemory())` and `app.guard(GuardRail.pii())` should be able to
guess `app.agent("name", Interface.class)` before reading the documentation. The pattern is
the API.

**Why guardrails apply to agents:**
An agent that can call tools and affect external systems is a higher-risk target than a plain
LLM call. Guardrail pre-screening before agent invocation is not optional — it is the correct
default. The `guard(false)` opt-out exists for trusted internal callers.

**On the Temporal direction:**
Temporal was identified as a production-grade orchestration engine that could give agent
workflows durable, long-running execution semantics. The design — an `Orchestrator` interface
with `InMemoryOrchestrator` and `TemporalOrchestrator` implementations — was architecturally
sound. It was set aside not because it was wrong, but because it was premature. The correct
sequence is: establish the agent binding layer first (this milestone), then evaluate whether
durable execution is needed in practice (capstone 2 and beyond will surface this).

---

## Dependencies

- Helidon 4.4.0 + LangChain4j 1.11.0 (on the classpath via the BOM — `AiServices`,
  `ChatMemory`, `@Tool`, `McpToolProvider`) ✅
- `cafeai-guardrails` and `cafeai-observability` (guardrail pre-screening + trace context) ✅
- `app.helidon()` escape hatch (for exposing an agent as an MCP tool) ✅

MCP *exposure* of an agent is not a phase here — it's the supervisor/`app.helidon()`
pattern described in ROADMAP-12's mental-model section.
