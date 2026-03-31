# MILESTONE-12: CafeAI Agents

**Roadmap:** ROADMAP-12  
**Module:** `cafeai-agents` (new), `cafeai-core`, `cafeai-guardrails`, `cafeai-observability`  
**Started:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Module | Status | Completed |
|---|---|---|---|---|
| Phase 1 | Helidon 4.4 upgrade (shared with ROADMAP-11) | root `build.gradle` | 🔴 Not Started | — |
| Phase 2 | `cafeai-agents` module scaffold | `cafeai-agents` | 🔴 Not Started | — |
| Phase 3 | `AgentRun` and `AgentResult` — fluent API types | `cafeai-agents` | 🔴 Not Started | — |
| Phase 4 | `AgentBridge` — Helidon lifecycle to CafeAI | `cafeai-agents` | 🔴 Not Started | — |
| Phase 5 | `app.agent()` API surface | `cafeai-core`, `cafeai-agents` | 🔴 Not Started | — |
| Phase 6 | Guardrail pre-screening for agents | `cafeai-agents` | 🔴 Not Started | — |
| Phase 7 | MCP exposure of agents (integration with ROADMAP-11) | `cafeai-mcp` | 🔴 Not Started | — |
| Phase 8 | Capstone 2 verification | capstone 2 app | 🔴 Not Started | — |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised · 🔷 Deferred

---

## Measurable Outputs

By the end of this milestone, the following must be demonstrable:

| Output | How Measured |
|---|---|
| `app.agent(name, interface)` compiles and registers | Startup log shows agent line |
| `AgentRun.run(agent -> agent.method(...))` invokes agent | Returns `AgentResult` with text |
| Session memory threads into agent | Second call in same session has memory of first |
| Guardrail blocks jailbreak before agent sees it | `AgentGuardRailException` thrown |
| Observability trace fires on agent invocation | Console trace shows agent execution |
| Agent discoverable via MCP (Phase 7) | `curl http://localhost:8080/mcp` shows agent |
| Capstone 2 test suite passes | All capstone 2 assertions pass |
| `cafeai-agents` module published to mavenLocal | `./gradlew publishToMavenLocal` succeeds |

---

## Key Decisions Recorded

**Why CafeAI does not own the agent loop:**
The agent loop — reasoning steps, tool dispatch, AgenticScope, termination conditions — is
Helidon 4.4 + LangChain4j 1.11's domain. Building a competing version would produce something
worse. CafeAI gives the agent an HTTP identity, session threading, guardrail protection, and
observability. The loop itself is opaque to CafeAI.

**Why the rejected alternatives matter:**
Three designs were explored: Chains/Steps (removed — duplicated middleware), building a
workflow orchestrator (rejected — reinventing what Helidon is already building), and Temporal
as backing execution engine (explored — sound design, wrong question). The rejection path is
documented in SPEC.md §11.2. Understanding what was rejected is as important as understanding
what was chosen.

**Why `app.agent()` follows the same pattern as `app.tool()`, `app.memory()`, etc.:**
The naming philosophy requires consistency. A developer who knows `app.tool(new GitHubTools())`
and `app.memory(MemoryStrategy.inMemory())` should be able to guess `app.agent("name", Interface.class)`
before reading the documentation. The pattern is the API.

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

- Helidon 4.4.0+ (provides agentic LangChain4j support, LangChain4j 1.11)
- ROADMAP-07 Phases 7, 9 complete (guardrails and observability)
- ROADMAP-11 Phase 1 complete (Helidon upgrade — shared prerequisite)
- ROADMAP-11 Phase 3–4 complete (MCP bridge built — required for Phase 7)
