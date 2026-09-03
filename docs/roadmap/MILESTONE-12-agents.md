# MILESTONE-12: CafeAI Agents

**Roadmap:** ROADMAP-12  
**Module:** `cafeai-agents` (new), `cafeai-core`  
**Started:** 2026-09-02  
**Current Status:** 🟢 Complete — `app.agent()` wired (Phases 1–5), `AgentConfig.rag()` + `ObserveBridge` agent hooks, all four capstones migrated in-tree (`capstones/`). 14 binding tests green.

---

## Progress Tracker

Phases mirror [ROADMAP-12](ROADMAP-12-agents.md).

| Phase | Description | Module | Status |
|---|---|---|---|
| 1 | Prerequisites — Helidon 4.4, LangChain4j 1.11, `app.helidon()` | root, `cafeai-core` | 🟢 Complete |
| 2 | `cafeai-agents` module scaffold | `cafeai-agents` | 🟢 Complete (`17fad1d`) |
| 3 | `AgentConfig<T>` — fluent registration API | `cafeai-core` | 🟢 Complete (`17fad1d`) |
| 4 | `AgentRegistry` — build/resolve agents, per-session memory, guardrails | `cafeai-agents` | 🟢 Complete (`33912e5`) |
| 5 | `app.agent()` API surface (register + invoke) | `cafeai-core`, `cafeai-agents` | 🟢 Complete (`33912e5`) |
| 6 | Capstone 4 — `invoice-processor` + fold all four capstones into `capstones/` | capstone apps | 🟢 Complete (`e300c4c`, `33f6852`) |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised · 🔷 Deferred

---

## Measurable Outputs

| Output | How Measured |
|---|---|
| `app.agent(name, Interface.class)` compiles and registers | Startup log shows agent line |
| `app.agent(name, Interface.class, sessionId).method(...)` invokes the `AiService` | Returns the interface's return type |
| Session memory threads into the agent | Second call in same session has memory of the first ✅ |
| Guardrail flags a violating agent response | POST_LLM output guardrail tested; PRE_LLM input screening wired (needs a `GuardRail.checkInput` seam) |
| Observability trace fires on agent invocation | `AiServiceListener` logs + `ObserveBridge.beforeAgent`/`afterAgent` → console line / OTel span (name, latency, outcome) |
| RAG on an agent | `AgentConfig.rag(...)` or app-level `app.rag(...)` → LangChain4j `ContentRetriever` via the `RagPipeline` SPI |
| Supervisor pattern works — an agent calls another agent as a `@Tool` | `AgentExample` (`OrderDesk` tool → `OrderNarrator` agent); Capstone 4 next |
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
LLM call. `AgentConfig.guard(GuardRail...)` adapts each rail to an `InputGuardrail` and/or
`OutputGuardrail` by its `Position`. Today `GuardRail` exposes only `checkOutput(String)`, so
POST_LLM enforcement is real and PRE_LLM screening runs the same detector against the user
message; a dedicated `GuardRail.checkInput` seam is the clean follow-on.

**Why there is no CafeAI proxy:**
`app.agent(name, Interface.class, sessionId)` returns LangChain4j's *own* `AiService` proxy.
CafeAI's contributions are applied at `AiServices.builder()` time by adapting CafeAI
abstractions to LangChain4j's build-time hooks — `GuardRail` → `InputGuardrail`/`OutputGuardrail`,
observability → `AiServiceListener` + `ObserveBridge.beforeAgent`/`afterAgent`,
`MemoryStrategy` → `ChatMemoryStore` (session-keyed, wrapped in `MessageWindowChatMemory`),
RAG → `ContentRetriever` (via the `RagPipeline` SPI), tool sources → `.tools(...)`.
Wrapping the proxy in a second proxy would be the
"wrap, don't bind" mistake this milestone exists to avoid. A thin `java.lang.reflect.Proxy`
is deferred to if-and-when a capstone proves it necessary.

**The three MCP concerns are three different things, in three places:**
1. *Serve* CafeAI's tools/agents as an MCP server → `app.helidon()` + Helidon `McpFeature`
   (routing lifecycle, no module).
2. *Reach* an external MCP server → `cafeai-connect` `McpEndpoint` connector (persistent
   transport, three-state reachability, fallback policy — like Redis/Ollama/pgvector).
3. *Give* an agent those tools → `cafeai-agents` adapts a named MCP connection to a
   `ToolProvider` at `AiServices.builder()` time.
`cafeai-agents` v1 does #3 for **Java `@Tool` objects only**; MCP tool sources are additive
once #2 exists.

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
  `ChatMemory`, `@Tool`, `InputGuardrail`/`OutputGuardrail`, `ChatModelListener`,
  `ChatMemoryProvider`, `ToolProvider`, `McpToolProvider`) ✅
- `cafeai-guardrails` and `cafeai-observability` (adapted to the LangChain4j hooks above) ✅
- `app.helidon()` escape hatch — for exposing an agent *as* an MCP tool ✅
- `cafeai-connect` `McpEndpoint` connector — for *consuming* an external MCP server's tools
  (not required for v1; makes MCP a tool source once it lands)
