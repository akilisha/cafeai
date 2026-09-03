# ROADMAP-12: CafeAI Agents — HTTP Identity for LangChain4j AiServices

**Maps to:** No Express equivalent — this is CafeAI's agent binding layer  
**Modules:** `cafeai-agents` (new), `cafeai-core`  
**ADR Reference:** SPEC.md §13  
**Depends On:** ROADMAP-11 Phase 1 (Helidon 4.4 + LangChain4j 1.11 complete ✅)  
**Status:** 🟢 Complete — `app.agent()` wired (Phases 1–5), `AgentConfig.rag()` + `ObserveBridge` agent hooks added, all four capstones migrated in-tree (Phase 6). 14 binding tests green.

---

## Objective

Give every LangChain4j `AiService` agent the same things CafeAI gives every other capability:
an HTTP identity, session threading, guardrail protection, and an observability context. The
developer defines a typed agent interface; CafeAI wires it up and gives it a home on the
HTTP server.

CafeAI does not implement the agent loop, tool dispatch, or reasoning primitives. LangChain4j
`AiServices` implements all of that. CafeAI writes the binding: `AiServices.builder()` is
called internally, pre-wired with the registered model, tools, and memory, then optionally
extended by the developer via a builder consumer escape hatch.

---

## Architecture Decision: `AiServices` directly, not `@Ai.Agent`

Helidon 4.4 introduced `@Ai.Agent` as a declarative annotation processed at compile time by
Helidon Inject (Helidon's service registry framework). Using it would require adding Helidon
Inject to CafeAI — the same architectural conflict that killed `cafeai-mcp`.

CafeAI uses `AiServices.builder()` directly from LangChain4j core. This is what Helidon's
annotation processor generates anyway. By going direct we keep CafeAI's pure Helidon SE model
intact and give the developer full builder access via the `.configure()` escape hatch.

### No CafeAI proxy — adapt to LangChain4j's extension points

`app.agent(name, Interface.class, sessionId)` returns the **raw LangChain4j `AiService`
proxy**, not a CafeAI wrapper around it. A proxy-of-a-proxy would be exactly the "wrap, don't
bind" mistake ROADMAP-12 exists to avoid.

Everything CafeAI wants to contribute is applied at `AiServices.builder()` time, by adapting
CafeAI abstractions to LangChain4j's own hooks:

All hooks are on `AiServices<T>` itself (LangChain4j has no separate `.Builder` — `AiServices.builder(cls)` returns the builder-shaped `AiServices<T>`, chain, then `.build()`).

| CafeAI abstraction | LangChain4j hook (verified, 1.11.0) |
|---|---|
| `GuardRail` (PRE) | `InputGuardrail` → `.inputGuardrails(I...)` |
| `GuardRail` (POST) | `OutputGuardrail` → `.outputGuardrails(O...)` |
| `ObserveBridge` | `AiServiceListener<AiServiceStartedEvent \| …ResponseReceivedEvent \| …CompletedEvent \| …ErrorEvent>` → `.registerListeners(...)` — **whole-invocation events**, not per model call |
| `MemoryStrategy` | `ChatMemoryProvider` → `.chatMemoryProvider(id -> ...)` |
| system prompt override | `.systemMessage(String)` |
| RAG retriever | `RetrievalAugmentor` → `.retrievalAugmentor(...)` |
| Java `@Tool` object | `.tools(Object...)` |
| MCP tools | `ToolProvider` → `.toolProvider(...)` |

Because `AiServiceListener` fires on the *whole* invocation (start / response / completed /
error), the "wrap it in a proxy for a whole-method span" case is already covered — **no
`java.lang.reflect.Proxy` is needed at all** for v1. The only thing a proxy would still add
is surfacing the agent's final text to POST_LLM *HTTP* filters via `LLM_RESPONSE_TEXT`; that
is niche and deferred.

### `AgentBridge` SPI — signature fix needed

`spi/AgentBridge.resolve(...)` currently takes `LangchainBridge.ChatModelAccess` (a test
seam) as the provider argument. It needs the resolved `AiProvider` (or `ChatModel`). Correct
this signature in Phase 2 before anything is built against it.

### MCP scope for v1

`cafeai-agents` v1 handles **Java `@Tool` objects only** (`.tool(new OrderLookupTool())`).
Consuming an external **MCP server's** tools is a follow-on: the *connection* (probe,
reachability, fallback) belongs in `cafeai-connect` as an `McpEndpoint` connector, and
`cafeai-agents` references it by name and adapts it to a `ToolProvider`. So `AgentConfig`
must model tools as a list of **tool sources** (a `ToolProvider` is one source, a plain
`@Tool` object is another) — not a hard-coded `List<Object>` — so the MCP source is additive.
Exposing CafeAI's *own* tools/agents *as* an MCP server is unrelated — that is the
`app.helidon()` escape hatch (see ROADMAP-11).

---

## Agent Mental Model (read before implementing)

### One `AiService` is a reasoning loop, not a single LLM call

When a developer calls `agent.advise("can I afford this house?")`, LangChain4j runs a cycle:

1. Send user message + system prompt to the LLM
2. LLM may respond with a tool call request rather than a final answer
3. LangChain4j executes the tool, appends result to conversation
4. Send updated conversation back to LLM
5. Repeat until LLM produces a final text response

One interface method call may make 5–6 LLM calls internally. This is what "agentic" means.
CafeAI wraps the entry and exit of this loop — not the individual steps.

### `app.agent()` corresponds to one AiService

One `app.agent()` registration corresponds to one `AiService` instance (or factory). Multiple
registered agents are independent — they do not share memory, model context, or tool state.

### Multi-agent patterns

Complex workflows involving multiple agents follow established patterns:

**Supervisor / subagent** — a supervisor `AiService` treats other agents as tools via
`@Tool`-annotated methods that delegate to subordinate agents. LangChain4j supports this
natively. CafeAI registers each agent independently; the supervisor receives subagents as
tool instances via `.configure(builder -> builder.tools(...))`.

**Sequential pipeline** — CafeAI middleware chain where each step invokes a different agent
and passes results via `req.local()`:

```java
app.post("/process",
    agentStep("classifier"),   // sets req.local("intent")
    agentStep("specialist"),   // reads req.local("intent"), sets req.local("result")
    (req, res, next) -> res.json(req.local("result")));
```

**Parallel fan-out with aggregation** — `CompletableFuture` across multiple agent invocations,
results passed to an aggregator agent. This is application code, not a CafeAI primitive.

**Durable multi-step workflows with human approval** — this is the territory of an external
orchestrator (Orkes Conductor, Temporal). CafeAI agents exposed via `app.helidon()` as MCP
tools are callable from those orchestrators. CafeAI owns the inner loop; the orchestrator
owns the outer workflow.

### The builder escape hatch

`AiServices.builder()` is rich. CafeAI pre-wires the common path. The `.configure()` escape
hatch gives access to the full builder for cases CafeAI does not abstract:

```java
app.agent("advisor", LoanAdvisor.class)
   .system("You are a conservative mortgage advisor...")
   .memory(MemoryStrategy.inMemory())
   .guard(GuardRail.regulatory().ecoa().fairHousing())
   .configure(builder -> builder                    // escape hatch
       .chatMemoryProvider(id ->                    // per-session memory
           MessageWindowChatMemory.withMaxMessages(20))
       .retrievalAugmentor(myAdvancedRag)           // advanced RAG
       .moderationModel(openAiModeration));         // built-in moderation
```

The `.configure()` consumer receives the `AiServices.Builder` after CafeAI has applied its
own configuration. The developer may override or extend anything.

---

## Phases

---

### Phase 1 — Prerequisites ✅ Complete

- Helidon 4.4.0 migration complete
- LangChain4j 1.11.0 migration complete  
- `app.helidon()` escape hatch implemented
- 311 tests passing

---

### Phase 2 — `cafeai-agents` Module Scaffold ✅ Complete

Delivered in `17fad1d`: module in `settings.gradle`, opted into `gradle/maven-central.gradle`,
`ToolSource` sealed type + `AgentConfig<T>` in `cafeai-core`, corrected `AgentBridge` SPI
(`init(AgentSupport)` + `resolve(name, type, sessionId)`), `AgentRegistry` stub +
`META-INF/services` registration.

**Goal:** Create the module skeleton. Compiles cleanly. No functional code yet.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Add `cafeai-agents` to `settings.gradle`
- [ ] Create `cafeai-agents/build.gradle`:
  - `cafeai-core` dependency
  - `langchain4j` core (already on classpath via BOM — `AiServices`, `ChatMemory`,
    `@Tool`, `McpToolProvider`); tool + MCP support comes from LangChain4j directly,
    there is no separate `cafeai-tools` module
- [ ] Create package `io.cafeai.agents`
- [ ] `AgentConfig<T>` **stays in `cafeai-core`** — `CafeAI.agent(name, iface)` returns it for
  chaining, and core already depends on `langchain4j` (so `Consumer<AiServices<T>>` is fine).
  Fix its `.configure()` doc (the arg *is* `AiServices<T>`, not a `.Builder`).
- [ ] Add a `ToolSource` sealed type in `cafeai-core` (`JavaTool(Object)` |
  `McpTool(String connectionName)`); `AgentConfig` collects `List<ToolSource>`.
- [ ] **Correct the `AgentBridge` SPI** — `resolve(name, type, sessionId)` (no bogus
  `ChatModelAccess` arg); add `init(AgentSupport)` so `cafeai-core` lends it
  `chatModel(AiProvider)`, `defaultProvider()`, `observeBridge()`, `defaultMemory()`.
  `register(...)` returns `AgentConfig<T>`.
- [ ] Create `AgentRegistry.java` implementing `AgentBridge` — methods throw
  `UnsupportedOperationException` (Phase 3/4 fill them in)
- [ ] `META-INF/services/io.cafeai.core.spi.AgentBridge` → `AgentRegistry`
- [ ] Verify: `./gradlew :cafeai-agents:compileJava` and full `build` → BUILD SUCCESSFUL

#### Acceptance Criteria
- [x] Module in `settings.gradle`, opted into `gradle/maven-central.gradle`
- [x] No circular dependencies (`cafeai-agents` → `cafeai-core`; never the reverse)
- [x] Clean compile; corrected `AgentBridge` SPI signature

---

### Phase 3 — `AgentConfig` — Fluent Registration API ✅ Complete

Delivered in `17fad1d` (+ `rag(...)` in the Phase 2 addendum): `system` / `model` / `memory` /
`guard` / `tool` / `mcp` / `rag` / `configure(Consumer<AiServices<T>>)`, tools stored as
`List<ToolSource>`, package-visible accessors for the bridge. Note: LangChain4j has no `AiServices.Builder` type —
`.configure()` receives the builder-shaped `AiServices<T>` itself.

**Goal:** Define the fluent API the developer uses to configure an agent at registration time.
Mirrors the feel of `GuardRail` builder — readable, chainable, self-documenting.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Implement `AgentConfig<T>`:
  - `AgentConfig<T> system(String prompt)` — system prompt
  - `AgentConfig<T> memory(MemoryStrategy strategy)` — CafeAI memory strategy
  - `AgentConfig<T> guard(GuardRail... rails)` — guardrails applied pre-invocation
  - `AgentConfig<T> tool(Object toolInstance)` — a Java `@Tool`-annotated object (a *tool source*)
  - `AgentConfig<T> model(AiProvider provider)` — override app-level provider
  - `AgentConfig<T> configure(Consumer<AiServices.Builder<T>> consumer)` — escape hatch
  - `T build()` — internal — builds and returns the raw LangChain4j AiService proxy
- [ ] Store tools as a `List<ToolSource>` (a `@Tool` object is one kind; a `ToolProvider`
  — e.g. an MCP connection — is another). Do **not** hard-code `List<Object>`.
- [ ] `AgentConfig` is not thread-safe — one instance per registration

#### Output
```java
app.agent("loan-advisor", LoanAdvisor.class)
   .system("You are a conservative mortgage advisor...")
   .memory(MemoryStrategy.inMemory())
   .guard(GuardRail.regulatory().ecoa().fairHousing())
   .configure(builder -> builder
       .chatMemoryProvider(id ->
           MessageWindowChatMemory.withMaxMessages(20)));
```

#### Acceptance Criteria
- [x] All builder methods return `AgentConfig<T>` for chaining
- [x] `.configure()` receives the real `AiServices<T>` builder — not a stub
- [x] Unit tests covering the builder path (`AgentRegistryTest`)

---

### Phase 4 — `AgentRegistry` — Agent Lifecycle ✅ Complete

Delivered in `33912e5`, extended in the capstone-migration work. `AgentRegistry.resolve()`
assembles `AiServices.builder(type)` from the config via adapters — no wrapper proxy:
- `GuardrailAdapters` — `GuardRail` → `InputGuardrail` / `OutputGuardrail`, split by `Position`
- `CafeAiChatMemoryStore` — `MemoryStrategy` → `ChatMemoryStore`, session-keyed, wrapped in a
  `MessageWindowChatMemory` (20-message window)
- `CafeAiContentRetriever` — `AgentConfig.rag(retriever)` (or the app-level `app.rag(...)` when
  unset) → LangChain4j `ContentRetriever` via `.contentRetriever(...)`, dispatching through the
  same `RagPipeline` SPI `app.prompt()` uses; degrades to no-op without `cafeai-rag`
- `AgentObserveListener` — whole-invocation `AiServiceListener` logging **plus**
  `ObserveBridge.beforeAgent`/`afterAgent` (default no-op on the SPI; `cafeai-observability`
  emits a console line / OTel `cafeai.agent.invoke` span — name, latency, outcome; no token
  accounting on the agent path)
- MCP tool sources: `AgentConfig.mcp(...)` + the `ToolSource` sealed type were **removed**
  (2026-09) — a method that only throws is worse than an absent one. `AgentConfig.tools`
  is a plain `List<Object>` of `@Tool` instances again. Re-introduce `ToolSource` / `.mcp()`
  when the `cafeai-connect` `McpEndpoint` connector is built; the design stands (below)
- stateless agents cached by name; stateful cached by `name::sessionId`

**Goal:** Store registered agents, build them on demand, manage per-session memory.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Implement `AgentRegistry` (the `AgentBridge` SPI impl):
  - `register(String name, Class<T> type, AgentConfig<T> config)` — stores config
  - `<T> T resolve(String name, Class<T> type, String sessionId)` — builds or retrieves the
    raw AiService proxy
  - Per-session proxy for agents with memory; stateless agents built once and reused
- [ ] Assembly — `AiServices.builder(type)` wired from `AgentConfig` via **adapters**, not
  a wrapper:
  - `GuardRail` → `InputGuardrail` / `OutputGuardrail` (`.inputGuardrails` / `.outputGuardrails`)
  - `ObserveBridge` → `ChatModelListener` (`.listeners` on the model)
  - `MemoryStrategy` → `ChatMemoryProvider` (`.chatMemoryProvider`)
  - tool sources → `ToolProvider` (`.toolProvider`) and/or `.tools(...)`
  - then `.configure()` consumer runs last, over the assembled builder
- [ ] Session threading: `sessionId` → `ChatMemory` via `ConcurrentHashMap`;
  `MessageWindowChatMemory` default (20 messages); eviction via `MemoryStrategy` if set

#### Acceptance Criteria
- [x] Two sequential calls with same `sessionId` share conversation history
- [x] Two calls with different `sessionId` have isolated histories
- [x] A guardrail violation is enforced (POST_LLM output guardrail tested; PRE_LLM input
  screening is wired but `GuardRail` has only `checkOutput` — a `checkInput` seam is a follow-on)
- [x] Stateless agents (no memory) build once, reuse safely
- [x] The returned object is LangChain4j's proxy — no CafeAI `Proxy` in the call path

---

### Phase 5 — `app.agent()` API Surface ✅ Complete

Delivered in `33912e5`. Two forms only — the three-arg `agent(name, Class)` returning
`AgentConfig<T>` (registration) and a two-arg `agent(name, Class)` returning `T` erase
identically, so invocation is always `agent(name, Class, sessionId)` with `sessionId` nullable
for stateless. `CafeAIApp.discoverAgentBridge()` loads the SPI via `ServiceLoader` and lends it
`chatModel` / `defaultProvider` / `observeBridge` / `defaultMemory`; registration after
`listen()` throws; missing module throws with the `cafeai-agents` coordinate.
`AgentExample` (supervisor + delegating `@Tool` sub-agent, Jlama-backed) exercises the surface.

**Goal:** Add `app.agent()` to the `CafeAI` interface. Two overloads — registration and
invocation — same pattern as `app.prompt()`.

**Module:** `cafeai-core`, `cafeai-agents`

#### Tasks
- [ ] Add to `CafeAI` interface:
  ```java
  // Registration (before listen())
  <T> AgentConfig<T> agent(String name, Class<T> agentInterface);

  // Invocation (in route handlers) — sessionId nullable for stateless.
  // No 2-arg T overload: it would erase to the same signature as registration.
  <T> T agent(String name, Class<T> type, String sessionId);
  ```
- [ ] Implement in `CafeAIApp` via SPI (`AgentBridge` loaded by `ServiceLoader`)
- [ ] If `cafeai-agents` absent: registration no-ops with WARN, invocation throws clear error
- [ ] Startup log: `Agent registered: {name} ({interface.simpleName})`

#### Output — complete developer experience
```java
// Registration
app.agent("loan-advisor", LoanAdvisor.class)
   .system("You are a conservative mortgage advisor...")
   .memory(MemoryStrategy.inMemory())
   .guard(GuardRail.regulatory().ecoa().fairHousing());

// Invocation in route handler
app.post("/advise", (req, res, next) -> {
    String sessionId = req.header("X-Session-Id");
    LoanAdvisor advisor = app.agent("loan-advisor", LoanAdvisor.class, sessionId);
    String advice = advisor.advise(req.body("request"));
    res.json(Map.of("advice", advice));
});
```

#### Acceptance Criteria
- [x] Registration → invocation round-trip works end-to-end (`AgentExample` + `AgentRegistryTest`)
- [x] Session memory persists across two sequential POST requests
- [x] Output guardrail flags a violating response (PRE_LLM screening — see Phase 4 note)
- [x] Missing `cafeai-agents` produces clear error message (with the Maven coordinate)
- [x] `app.agent()` after `listen()` throws `IllegalStateException`

---

### Phase 6 — Capstone 4: `invoice-processor` ✅ Migrated

`capstones/invoice-processor` (was the standalone `atlas-inbox`, package
`io.meridian.invoice`). A batch job: Gmail → sentiment (`app.prompt().returning`)
→ attachment classification + invoice extraction (`app.vision().returning`) →
**a `ReconciliationAgent`** (`app.agent("reconciler", ...)` with three `@Tool`
classes, returning a typed `ReconciliationVerdict`) → vendor reply. `app.budget()`
/ `app.retry()` for the free-tier rate limit; `GuardRail.jailbreak()` on the endpoint.

The four capstones (`support-desk`, `meridian-qualify`, `acme-claims`,
`invoice-processor`) now live in the umbrella build as `project(':cafeai-*')`
consumers — CI compiles them, so an agent-API change breaks the build. See
`capstones/README.md`. The richer supervisor/sub-agent + `app.helidon()` webhook
scenario from the original spec is deferred to a future capstone or to
`nova-tutor` (`docs/roadmap/CAPSTONE-5-nova-tutor.md`).

---

## Testing Strategy

```
Phase 2: compile only                                                    ✅
Phase 3–5: AgentRegistryTest — 12 tests: register/resolve lifecycle,      ✅
           system prompt, model override, stateless proxy caching,
           session memory threading + isolation, output guardrail
           block/allow, MCP tool-source deferral. Real AiServices over a
           fake ChatModel + a fake AgentSupport; MemoryStrategy.inMemory().
Phase 2 add.: AgentRegistryTest — +2 (observe bracketing, RAG degrade)    ✅
Phase 6: capstones compile against project(':cafeai-*') in CI;            ✅
         live-LLM harnesses (OpenAI / Gmail) run manually
```

---

## Non-Goals

- CafeAI does not implement the agent reasoning loop
- CafeAI does not own multi-step workflow orchestration (that is Orkes/Temporal's job)
- CafeAI does not build a UI for agent monitoring
- CafeAI does not implement Agent-to-Agent (A2A) protocol directly — agents are exposed
  as MCP tools or HTTP endpoints, and A2A orchestration happens outside CafeAI
