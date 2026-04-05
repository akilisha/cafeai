# ROADMAP-12: CafeAI Agents — HTTP Identity for LangChain4j AiServices

**Maps to:** No Express equivalent — this is CafeAI's agent binding layer  
**Modules:** `cafeai-agents` (new), `cafeai-core`  
**ADR Reference:** SPEC.md §13  
**Depends On:** ROADMAP-11 Phase 1 (Helidon 4.4 + LangChain4j 1.11 complete ✅)  
**Status:** 🟡 In Progress — Phase 1 complete, Phase 2 starting

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

### Phase 2 — `cafeai-agents` Module Scaffold

**Goal:** Create the module skeleton. Compiles cleanly. No functional code yet.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Add `cafeai-agents` to `settings.gradle`
- [ ] Create `cafeai-agents/build.gradle`:
  - `cafeai-core` dependency
  - `cafeai-tools` dependency (tool registration integration)
  - `langchain4j` core (already on classpath via BOM — `AiServices`, `ChatMemory`)
- [ ] Create package `io.cafeai.agents`
- [ ] Create `AgentRegistry.java` — placeholder
- [ ] Create `AgentConfig.java` — placeholder  
- [ ] Verify: `./gradlew :cafeai-agents:compileJava` → BUILD SUCCESSFUL

#### Acceptance Criteria
- [ ] Module in `settings.gradle`
- [ ] No circular dependencies
- [ ] Clean compile

---

### Phase 3 — `AgentConfig` — Fluent Registration API

**Goal:** Define the fluent API the developer uses to configure an agent at registration time.
Mirrors the feel of `GuardRail` builder — readable, chainable, self-documenting.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Implement `AgentConfig<T>`:
  - `AgentConfig<T> system(String prompt)` — system prompt
  - `AgentConfig<T> memory(MemoryStrategy strategy)` — CafeAI memory strategy
  - `AgentConfig<T> guard(GuardRail... rails)` — guardrails applied pre-invocation
  - `AgentConfig<T> tool(Object toolInstance)` — additional tools beyond app-level
  - `AgentConfig<T> model(AiProvider provider)` — override app-level provider
  - `AgentConfig<T> configure(Consumer<AiServices.Builder<T>> consumer)` — escape hatch
  - `T build()` — internal — builds and returns the AiService proxy
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
- [ ] All builder methods return `AgentConfig<T>` for chaining
- [ ] `.configure()` receives a real `AiServices.Builder<T>` — not a stub
- [ ] Unit tests for each builder method

---

### Phase 4 — `AgentRegistry` — Agent Lifecycle

**Goal:** Store registered agents, build them on demand, manage per-session memory.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Implement `AgentRegistry`:
  - `register(String name, Class<T> type, AgentConfig<T> config)` — stores config
  - `<T> T resolve(String name, Class<T> type, String sessionId)` — builds or retrieves agent
  - Per-session agent instances for agents with memory
  - Stateless (no memory) agents built once and reused
- [ ] Session threading:
  - `sessionId` → `ChatMemory` mapping via `ConcurrentHashMap`
  - `MessageWindowChatMemory` default (20 messages)
  - Memory eviction via `MemoryStrategy` if registered
- [ ] Guardrail application:
  - Pre-invocation: run registered guardrails against the input
  - If blocked: throw `GuardRailViolationException` with reason
  - Post-invocation: run output guardrails if registered

#### Acceptance Criteria
- [ ] Two sequential calls with same `sessionId` share conversation history
- [ ] Two calls with different `sessionId` have isolated histories
- [ ] Guardrail violation stops agent invocation — LLM never called
- [ ] Stateless agents (no memory) build once, reuse safely

---

### Phase 5 — `app.agent()` API Surface

**Goal:** Add `app.agent()` to the `CafeAI` interface. Two overloads — registration and
invocation — same pattern as `app.prompt()`.

**Module:** `cafeai-core`, `cafeai-agents`

#### Tasks
- [ ] Add to `CafeAI` interface:
  ```java
  // Registration (before listen())
  <T> AgentConfig<T> agent(String name, Class<T> agentInterface);

  // Invocation (in route handlers)
  <T> T agent(String name, Class<T> type, String sessionId);
  <T> T agent(String name, Class<T> type);   // no session
  ```
- [ ] Implement in `CafeAIApp` via SPI (`AgentBridgeProvider` loaded by `ServiceLoader`)
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
- [ ] Registration → invocation round-trip works end-to-end
- [ ] Session memory persists across two sequential POST requests
- [ ] Guardrail blocks invocation before LLM is called
- [ ] Missing `cafeai-agents` produces clear error message
- [ ] `app.agent()` after `listen()` throws `IllegalStateException`

---

### Phase 6 — Capstone 4: `invoice-processor`

**Goal:** Demonstrate the full agent model in a realistic fictional scenario. Shows:
- Single agent with tools and RAG
- Supervisor + subagent pattern
- `app.helidon()` for any capability outside CafeAI's vocabulary
- Multi-tenant session isolation

**Fictional company:** Meridian Billing Services — accounts payable automation

**Agents:**
- `InvoiceClassifierAgent` — classifies invoice type, detects anomalies (fast/cheap model)
- `PolicyLookupAgent` — RAG over AP policy documents
- `ApprovalAgent` — supervisor: calls classifier + policy lookup as tools, decides approve/reject/escalate
- `AuditLogAgent` — writes structured audit entries (no memory needed — stateless)

**Demonstrates:**
- `app.agent()` registration for each
- `.configure()` escape hatch for `chatMemoryProvider` (per-invoice-batch session)
- Guardrails on `ApprovalAgent` (regulatory: SOX compliance patterns)
- Raw Helidon route via `app.helidon()` for a webhook endpoint the AP system calls

---

## Testing Strategy

```
Phase 2: compile only
Phase 3: AgentConfigTest — unit tests, mock AiServices
Phase 4: AgentRegistryTest — session isolation, guardrail blocking
Phase 5: CafeAIAgentTest — integration, real AiServices with mock ChatModel
Phase 6: capstone — test.sh script, 12 acceptance scenarios
```

---

## Non-Goals

- CafeAI does not implement the agent reasoning loop
- CafeAI does not own multi-step workflow orchestration (that is Orkes/Temporal's job)
- CafeAI does not build a UI for agent monitoring
- CafeAI does not implement Agent-to-Agent (A2A) protocol directly — agents are exposed
  as MCP tools or HTTP endpoints, and A2A orchestration happens outside CafeAI
