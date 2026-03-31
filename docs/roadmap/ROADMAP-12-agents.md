# ROADMAP-12: CafeAI Agents — HTTP Identity for Helidon Agentic LangChain4j

**Maps to:** No Express equivalent — this is CafeAI's agent binding layer  
**Modules:** `cafeai-agents` (new), `cafeai-core`, `cafeai-guardrails`, `cafeai-observability`  
**ADR Reference:** SPEC.md §11.2  
**Depends On:** ROADMAP-11 Phase 1 (Helidon 4.4 upgrade), ROADMAP-07 (guardrails, observability)  
**Status:** 🔴 Not Started

---

## Objective

Give every Helidon agentic LangChain4j agent the same things CafeAI gives every other
capability: an HTTP identity, session threading, guardrail protection, and an observability
context. The developer registers an annotated agent interface; CafeAI handles the rest.

CafeAI does not implement the agent loop, tool dispatch, workflow patterns, AgenticScope, or
any reasoning primitives. Helidon 4.4 + LangChain4j 1.11 implement all of that. CafeAI writes
the binding between Helidon's agent lifecycle and CafeAI's HTTP session and observability model.

### Why This Exists

Three ideas were explored and rejected before arriving at this design.

**Rejected: Chains and Steps.** Built, used in capstone 1, and removed. They duplicated
middleware without adding capability. Every primitive has a learning cost; if that cost is not
paid back, the developer stops. Chains did not pay back.

**Rejected: Building a workflow orchestrator.** The Quarkus workshop showed the full scope of
a real agentic workflow system. Building a competing version would produce something worse than
what Helidon and LangChain4j are already building together.

**Rejected: Temporal as backing orchestrator.** Sound design, wrong question. The question
is not "how does CafeAI orchestrate agents" — it is "what does CafeAI give to an agent that
neither Helidon nor LangChain4j provides." The answer is: HTTP identity, session, guardrails,
and observability. Exactly what CafeAI gives to every other capability.

**The right design:** `app.agent()` registers a Helidon agentic LangChain4j interface with
CafeAI. CafeAI binds session threading, guardrail pre-screening, and observability to every
invocation. Helidon runs the loop.

---

## Phases

---

### Phase 1 — Helidon Version Upgrade (shared with ROADMAP-11)

This phase is shared with ROADMAP-11 Phase 1. Helidon must be upgraded to 4.4.0 before any
agent work begins. Complete ROADMAP-11 Phase 1 first.

**Prerequisite:** ROADMAP-11 Phase 1 complete.

---

### Phase 2 — `cafeai-agents` Module Scaffold

**Goal:** Create the new module with correct Gradle configuration, package structure, and
Helidon agentic LangChain4j dependency. No functional code yet — just the buildable skeleton.

**Module:** `cafeai-agents` (new)

#### Tasks
- [ ] Add `cafeai-agents` to `settings.gradle`
- [ ] Create `cafeai-agents/build.gradle` with:
  - `cafeai-core` dependency
  - `cafeai-observability` dependency
  - `helidon-integrations-langchain4j` dependency (agentic support)
  - LangChain4j agentic module dependency
- [ ] Create package `io.cafeai.agents`
- [ ] Create `AgentBridge.java` — empty class placeholder
- [ ] Create `AgentRun.java` — empty class placeholder
- [ ] Create `AgentResult.java` — empty class placeholder
- [ ] Verify module compiles: `./gradlew :cafeai-agents:compileJava`

#### Acceptance Criteria
- [ ] `cafeai-agents` in `settings.gradle`
- [ ] `./gradlew :cafeai-agents:compileJava` exits with BUILD SUCCESSFUL
- [ ] No circular dependencies introduced

---

### Phase 3 — `AgentRun` and `AgentResult` — Fluent Invocation API

**Goal:** Define the types the developer uses to invoke a registered agent and read its result.
These mirror `PromptRequest` and `PromptResponse` in their structure and feel — same pattern,
same ergonomics.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Implement `AgentRun<T>` — fluent builder analogous to `PromptRequest`:
  - `AgentRun<T> session(String sessionId)` — attaches session for memory threading
  - `AgentRun<T> guard(boolean enabled)` — opt-out of guardrails for trusted callers
  - `<R> R run(Function<T, R> invocation)` — executes the agent and returns result
- [ ] Implement `AgentResult` — wraps agent response:
  - `String text()` — primary text response
  - `List<AgentStep> trace()` — reasoning steps (from LangChain4j `AgentMonitor`)
  - `int toolCallCount()` — how many tools were invoked
  - `TokenUsage tokenUsage()` — total tokens across all steps
- [ ] `AgentStep` record: `String agentName`, `String input`, `String output`, `long durationMs`

#### Output
```java
// Developer usage in a route handler:
var result = app.agent("support-agent", SupportAgent.class)
                .session(req.header("X-Session-Id"))
                .run(agent -> agent.answer(req.body("message")));

res.json(Map.of(
    "answer",     result.text(),
    "steps",      result.trace().size(),
    "toolCalls",  result.toolCallCount(),
    "tokens",     result.tokenUsage().totalTokenCount()
));
```

#### Acceptance Criteria
- [ ] `AgentRun` compiles with correct generic type binding
- [ ] `AgentResult` exposes all fields correctly
- [ ] `session()` is chainable and does not mutate the agent registration
- [ ] Unit tests for `AgentRun` builder with mock agent

---

### Phase 4 — `AgentBridge` — Helidon Lifecycle to CafeAI

**Goal:** Implement the bridge between Helidon's agent instantiation/execution model and
CafeAI's session, guardrail, and observability model. This is the core of the module.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Research Helidon 4.4 agent instantiation API (how `@Ai.Agent` interfaces are resolved)
- [ ] Implement `AgentBridge`:
  - Resolves Helidon-managed agent instance for a given interface class
  - Applies CafeAI session threading before agent invocation:
    - Retrieves conversation history from `MemoryStrategy` using session ID
    - Injects history into agent context (LangChain4j `ChatMemory` or equivalent)
    - After invocation, persists updated history back to memory strategy
  - Wraps invocation in CafeAI observability:
    - Records start time, agent name, input
    - Records end time, output, token usage, tool call count
    - Emits `ObserveEvent` to registered `ObserveStrategy`
  - Wraps `AgentMonitor` output into `AgentResult`
- [ ] Handle agent exceptions: wrap in `AgentExecutionException` with original cause
- [ ] Handle session absence: agent runs without memory, no error

#### Acceptance Criteria
- [ ] Agent invocation with session ID persists conversation history
- [ ] Same session ID in two sequential calls produces memory continuity
- [ ] Different session IDs produce isolated histories
- [ ] Observability trace fires on every agent invocation
- [ ] Token usage reported correctly in `AgentResult`
- [ ] Tool call count reported correctly
- [ ] Agent exceptions wrapped and not swallowed

---

### Phase 5 — `app.agent()` API Surface

**Goal:** Add `app.agent()` to the `CafeAI` interface and implement it in `CafeAIApp`. The
developer registers and retrieves agents with the same ergonomic pattern as every other
CafeAI primitive.

**Module:** `cafeai-core`, `cafeai-agents`

#### Tasks
- [ ] Add to `CafeAI` interface:
  ```java
  <T> CafeAI agent(String name, Class<T> agentInterface);
  <T> AgentRun<T> agent(String name, Class<T> type);
  ```
- [ ] Implement in `CafeAIApp`:
  - `agent(name, interface)` — registers agent name → interface mapping in locals
  - `agent(name, type)` — returns `AgentRun<T>` wrapping the `AgentBridge` for that agent
  - Startup log: `Agent registered: {name} ({interface.simpleName})`
- [ ] SPI bridge in `cafeai-agents`: `AgentBridgeProvider` loaded via `ServiceLoader`
- [ ] If `cafeai-agents` not on classpath, `app.agent(name, type)` throws clear error:
  `"app.agent() requires cafeai-agents on the classpath"`
- [ ] Agent registration must occur before `app.listen()` — enforce with `assertNotStarted()`

#### Output
```java
// Startup registration:
app.agent("support-agent", SupportAgent.class);

// Handler invocation:
app.post("/support", (req, res, next) -> {
    var result = app.agent("support-agent", SupportAgent.class)
                    .session(req.header("X-Session-Id"))
                    .run(agent -> agent.answer(req.body("message")));
    res.json(Map.of("answer", result.text()));
});
```

#### Acceptance Criteria
- [ ] `app.agent(name, interface)` compiles and runs
- [ ] `app.agent(name, type).run(...)` invokes the Helidon-managed agent
- [ ] Registration appears in startup log
- [ ] `assertNotStarted()` enforced on registration
- [ ] Clear error when `cafeai-agents` absent from classpath
- [ ] Unit test: mock agent bridge, verify session and observability wiring

---

### Phase 6 — Guardrail Pre-screening for Agents

**Goal:** Registered guardrails run before every agent invocation, just as they run before
every HTTP handler. An agent should not receive a jailbreak attempt or prompt injection.

**Module:** `cafeai-agents`

#### Tasks
- [ ] `AgentBridge.invoke()` checks registered guardrails before calling the agent:
  - Extracts input text from agent invocation parameters
  - Runs each registered `GuardRail` against the input
  - If any guardrail blocks: throw `AgentGuardRailException` with guardrail name and reason
- [ ] `AgentRun.guard(false)` disables guardrail pre-screening for trusted callers
- [ ] Route handler can catch `AgentGuardRailException` and return 400
- [ ] Guardrails run in registration order — same order as HTTP pipeline

#### Output
```java
app.guard(GuardRail.jailbreak());
app.agent("support-agent", SupportAgent.class);

app.post("/support", (req, res, next) -> {
    try {
        var result = app.agent("support-agent", SupportAgent.class)
                        .session(req.header("X-Session-Id"))
                        .run(agent -> agent.answer(req.body("message")));
        res.json(Map.of("answer", result.text()));
    } catch (AgentGuardRailException e) {
        res.status(400).json(Map.of(
            "error",     "Request blocked by guardrail",
            "guardrail", e.guardrailName(),
            "reason",    e.reason()
        ));
    }
});
```

#### Acceptance Criteria
- [ ] Jailbreak input to agent returns `AgentGuardRailException`
- [ ] On-topic input reaches the agent normally
- [ ] `AgentRun.guard(false)` bypasses guardrail check
- [ ] All registered guardrails run in order, not just the first registered
- [ ] Unit test: mock guardrail, verify exception thrown on block

---

### Phase 7 — MCP Exposure of Agents (Integration with ROADMAP-11)

**Goal:** When `app.mcp().serve("/mcp")` is called, registered agents are exposed as MCP
tools alongside registered `@CafeAITool` methods. An external orchestrator can discover
and invoke CafeAI agents exactly as it invokes tools.

**Module:** `cafeai-mcp`, `cafeai-agents`

#### Tasks
- [ ] Update `McpServerBridge` to also register agents from `AgentRegistry`:
  - Agent tool name: registered name (e.g., "support-agent")
  - Description: from `@Ai.Agent` annotation description or a default
  - Input schema: `{ "question": "string", "sessionId": "string" }`
  - Handler: delegates to `AgentRun.run()` — full session + guardrail + observability path
- [ ] Session ID from MCP tool call is threaded into `AgentRun.session()`
- [ ] Agent result serialised to MCP response as JSON string
- [ ] Startup log: `MCP server active at /mcp — N tools, M agents registered`

#### Acceptance Criteria
- [ ] Agent appears in MCP discovery response alongside tools
- [ ] n8n can discover and invoke a registered agent
- [ ] Session ID passed from n8n workflow is threaded into agent memory
- [ ] Guardrails fire on agent invocations from n8n same as from direct HTTP
- [ ] Observability trace records agent invocations from MCP path

---

### Phase 8 — Capstone Verification (Loan Pre-Qualification Assistant)

**Goal:** Demonstrate `cafeai-agents` in capstone 2 — the loan pre-qualification assistant.
This is the use case that will surface whether the agentic direction holds up under regulatory
guardrail requirements, structured output, and genuinely complex routing logic.

**Module:** Capstone 2 application

#### Tasks
- [ ] Build loan pre-qualification assistant using `app.agent()`
- [ ] Register a `QualificationAgent` with regulatory guardrails (FCRA, ECOA, bias)
- [ ] Demonstrate multi-step agent reasoning: credit check → compliance → decision
- [ ] Verify structured output from agent maps correctly to `AgentResult`
- [ ] Add agent to MCP server — invoke from external test client
- [ ] All capstone 2 test assertions pass

#### Acceptance Criteria
- [ ] Loan qualification workflow runs end-to-end via `app.agent()`
- [ ] FCRA and ECOA guardrails block non-compliant inputs
- [ ] Agent reasoning trace available in `AgentResult.trace()`
- [ ] Same agent invocable via HTTP and via MCP
- [ ] Observability traces show complete agent execution including tool calls

---

## Testing Strategy

```
Phase 1: regression — all existing 278 tests pass
Phase 2: compile guard — :cafeai-agents:compileJava
Phase 3: AgentRunTest, AgentResultTest — unit tests
Phase 4: AgentBridgeTest — unit with mock Helidon agent and mock memory
Phase 5: CafeAIAppAgentTest — unit with mock bridge, app.agent() wiring
Phase 6: AgentGuardRailTest — unit with mock guardrails
Phase 7: integration — McpServerBridge includes agents, manual n8n verification
Phase 8: capstone 2 — full application test suite
```

---

## Non-Goals

- CafeAI does not implement the agent reasoning loop
- CafeAI does not implement `@SequenceAgent`, `@ParallelAgent`, `@ConditionalAgent`, `@LoopAgent`
- CafeAI does not implement `AgenticScope`
- CafeAI does not implement `@HumanInTheLoop` directly (Helidon does; CafeAI bridges it)
- CafeAI does not build a workflow graph editor or visual composer
- CafeAI does not implement Temporal integration (the `Orchestrator` interface concept was
  explored and deferred — see SPEC.md §11.2 for the full journey)
