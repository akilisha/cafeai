# ROADMAP-11: CafeAI MCP Server — Exposing Capabilities as Discoverable Nodes

**Maps to:** No Express equivalent — this is CafeAI's outward-facing capability exposure layer  
**Modules:** `cafeai-mcp` (new), `cafeai-core`, `cafeai-tools`  
**ADR Reference:** SPEC.md §11.1  
**Depends On:** ROADMAP-07 (tools complete), Helidon 4.4+ (MCP 1.1 server built-in)  
**Status:** 🔴 Not Started

---

## Objective

Give every capability registered in a CafeAI application a discoverable identity that any
MCP-aware external orchestrator can find and invoke. The developer adds one line —
`app.mcp().serve("/mcp")` — and every registered tool, every registered agent, and every
named prompt template becomes a typed, schema-described, invocable node.

CafeAI does not implement the MCP protocol. Helidon MCP 1.1 implements it. CafeAI writes the
bridge from its own tool and agent registries into Helidon's MCP server registration API.

### Why This Exists

The capstone analysis (March 2026) identified a clean three-way composition: CafeAI as HTTP
server, AI workload executor, and MCP server. The MCP server prong gives the application a
discoverable interface to the entire external orchestration ecosystem — n8n, Claude Desktop,
Temporal, and any future MCP-aware tool — without CafeAI needing to implement or understand
any of those tools. They speak MCP. CafeAI speaks MCP. That is sufficient.

The critical observation was that Helidon 4.3/4.4 already built a complete MCP 1.1 server
implementation, aligned with the June 2025 specification. CafeAI sits on Helidon. The bridge
between CafeAI's registry and Helidon's MCP server is the only code CafeAI needs to write.

---

## Phases

---

### Phase 1 — Helidon Version Upgrade

**Goal:** Upgrade CafeAI's Helidon dependency from 4.1.4 to 4.4.0 to gain MCP 1.1 server
support, agentic LangChain4j, LangChain4j 1.11, and Helidon's expanded declarative APIs.

**Module:** `cafeai-core`, root `build.gradle`

#### Tasks
- [ ] Upgrade Helidon from `4.1.4` to `4.4.0` in root `build.gradle`
- [ ] Upgrade LangChain4j from `1.0.0-alpha1` to `1.11.x` (via Helidon BOM)
- [ ] Run full test suite — all 278 `@Test` methods must pass
- [ ] Resolve any breaking API changes between Helidon 4.1.4 and 4.4.0
- [ ] Resolve any breaking API changes between LangChain4j alpha and 1.11
- [ ] Update `SPEC.md` technology stack table with new versions
- [ ] Verify `cafeai-connect` provider probing still works with new versions
- [ ] Verify `cafeai-guardrails` still compiles and all 33 tests pass
- [ ] Verify capstone 1 (`cafeai-support`) still compiles and `test.sh` returns 9/9

#### Acceptance Criteria
- [ ] All existing tests pass on Helidon 4.4.0 + LangChain4j 1.11
- [ ] No regressions in `cafeai-support` test suite
- [ ] Build produces no deprecation warnings for APIs CafeAI uses
- [ ] `./gradlew publishToMavenLocal` completes cleanly

---

### Phase 2 — `cafeai-mcp` Module Scaffold

**Goal:** Create the new module with the correct Gradle configuration, package structure,
and Helidon MCP dependency. No functional code yet — just the buildable skeleton.

**Module:** `cafeai-mcp` (new)

#### Tasks
- [ ] Add `cafeai-mcp` to `settings.gradle`
- [ ] Create `cafeai-mcp/build.gradle` with:
  - `cafeai-core` dependency
  - `cafeai-tools` dependency
  - `io.helidon.integrations.langchain4j` dependency (for MCP server classes)
  - `helidon-webserver-http2` if not already transitive
- [ ] Create package `io.cafeai.mcp`
- [ ] Create `McpServer.java` — empty class placeholder
- [ ] Create `McpServerBridge.java` — empty class placeholder
- [ ] Verify module compiles: `./gradlew :cafeai-mcp:compileJava`

#### Acceptance Criteria
- [ ] `cafeai-mcp` appears in `settings.gradle`
- [ ] `./gradlew :cafeai-mcp:compileJava` exits with BUILD SUCCESSFUL
- [ ] No circular dependencies introduced

---

### Phase 3 — `McpServerBridge` — Tool Registry to Helidon MCP

**Goal:** Walk CafeAI's tool registry and register each `@CafeAITool`-annotated method with
Helidon's MCP server. This is the core of the module — the bridge between CafeAI's registry
and Helidon's protocol implementation.

**Module:** `cafeai-mcp`

#### Tasks
- [ ] Research Helidon MCP 1.1 server registration API (`helidon-io/helidon` examples)
- [ ] Implement `McpServerBridge`:
  - Accept CafeAI `ToolRegistry` as input
  - Iterate all registered tools
  - For each `@CafeAITool` method, register with Helidon MCP server:
    - Tool name: method name (snake_case)
    - Description: `@CafeAITool` annotation value
    - Input schema: generated from method parameter names and types
    - Handler: delegates to the registered Java method
- [ ] Tool names must be stable — registration order independent
- [ ] Parameter type mapping: `String` → MCP `string`, `int`/`Integer` → MCP `number`, etc.
- [ ] Return type mapping: all tools return `String` to MCP (toString on non-String returns)
- [ ] Handle tool invocation exceptions: return error in MCP error format, do not throw

#### Output
```java
// Internal — not called directly by developer
McpServerBridge bridge = new McpServerBridge(app.toolRegistry());
bridge.registerAllWith(helidonMcpServer);
// Result: every @CafeAITool method is now discoverable via MCP
```

#### Acceptance Criteria
- [ ] All `GitHubTools` methods discoverable after registration
- [ ] Tool descriptions appear correctly in MCP discovery response
- [ ] Parameter schemas are correct for `String`, `int`, `boolean` parameter types
- [ ] Tool invocation via MCP produces same result as direct Java invocation
- [ ] Invocation exceptions return MCP error format, not HTTP 500

---

### Phase 4 — `app.mcp()` API Surface

**Goal:** Add `app.mcp()` to the `CafeAI` interface with a fluent builder that exposes the
MCP server at a configured path. The developer writes one line; everything else is automatic.

**Module:** `cafeai-core`, `cafeai-mcp`

#### Tasks
- [ ] Add `McpConfig` interface to `cafeai-core` with:
  - `McpConfig serve(String path)` — registers Helidon MCP server at path
  - `McpConfig name(String serverName)` — MCP server identity (default: "cafeai")
  - `McpConfig version(String version)` — MCP server version (default: app version)
- [ ] Add `app.mcp()` to `CafeAI` interface returning `McpConfig`
- [ ] Implement `McpConfig` in `cafeai-mcp` (SPI bridge)
- [ ] On `serve(path)`:
  - Instantiate `McpServerBridge` with CafeAI's tool registry
  - Create Helidon MCP server instance
  - Register all tools via bridge
  - Mount MCP server at specified path in Helidon routing
- [ ] Startup log: `MCP server active at /mcp — N tools registered`
- [ ] If `cafeai-mcp` not on classpath, `app.mcp()` returns a no-op stub with a WARN log

#### Output
```java
// Developer's entire MCP setup:
app.tool(new GitHubTools());
app.tool(new HeliosKnowledgeBase());
app.mcp().serve("/mcp");
// All tools now discoverable at http://localhost:8080/mcp
```

#### Acceptance Criteria
- [ ] Single line `app.mcp().serve("/mcp")` makes all tools discoverable
- [ ] MCP discovery endpoint returns correct tool list and schemas
- [ ] No change required to existing tool registrations
- [ ] Works without `cafeai-mcp` on classpath (no-op with WARN log)
- [ ] `curl http://localhost:8080/mcp` returns valid MCP discovery response

---

### Phase 5 — n8n Integration Verification

**Goal:** Confirm that a real n8n instance can connect to a running CafeAI application,
discover its tools, and invoke them successfully from an n8n workflow. This is the end-to-end
proof of the MCP server direction.

**Module:** `cafeai-mcp`, `cafeai-examples`

#### Tasks
- [ ] Create `cafeai-examples/mcp-server-demo` example application:
  - Register `GitHubTools` and a simple echo tool
  - Call `app.mcp().serve("/mcp")`
  - Run on port 8080
- [ ] Install n8n locally (Docker: `docker run -p 5678:5678 n8nio/n8n`)
- [ ] In n8n: add MCP Tool node pointing to `http://localhost:8080/mcp`
- [ ] Verify tool discovery: all tools appear as n8n nodes
- [ ] Build a minimal n8n workflow: HTTP trigger → CafeAI MCP tool → response
- [ ] Execute workflow end-to-end: trigger fires, MCP tool invoked, result returned
- [ ] Document the n8n connection steps in example README

#### Acceptance Criteria
- [ ] n8n discovers all registered tools automatically
- [ ] Tool parameter schemas render correctly as n8n input fields
- [ ] Tool invocations from n8n workflow succeed
- [ ] A RAG-enabled tool returns documents retrieved from the vector store
- [ ] A guarded tool returns 400 when guardrail fires (MCP error format)
- [ ] Full n8n workflow executes end-to-end with CafeAI as the execution backend

---

### Phase 6 — Elicitation Support (Human-in-the-Loop via MCP)

**Goal:** Implement MCP Elicitation — the June 2025 spec mechanism that allows a CafeAI tool
to pause execution and request additional structured input from the MCP client mid-invocation.
This is the MCP-native equivalent of the Human-in-the-Loop pattern.

**Module:** `cafeai-mcp`

#### Tasks
- [ ] Research Helidon MCP 1.1 Elicitation API
- [ ] Add `@McpElicit` annotation to `cafeai-mcp`:
  - Marks a tool method as potentially requiring mid-execution input
  - Specifies the schema of the required additional input
- [ ] Implement elicitation handler in `McpServerBridge`:
  - When tool method calls `McpContext.elicit(schema)`, pause execution
  - Helidon MCP server sends elicitation request to client
  - Resume when client responds
- [ ] Example: approval tool that elicits "approve" or "reject" before proceeding
- [ ] Document in example application

#### Acceptance Criteria
- [ ] Tool can pause and request structured input via Elicitation
- [ ] n8n workflow pauses at elicitation and resumes on user input
- [ ] Elicitation schema renders correctly as n8n input form
- [ ] Rejection path handled correctly (tool returns error or fallback value)

---

## Testing Strategy

Each phase has unit tests. Integration tests require a running Helidon instance.
The n8n verification (Phase 5) is a manual integration test documented in the example README.

```
Phase 1: all existing tests pass — regression guard
Phase 2: module compiles — structural guard
Phase 3: McpServerBridgeTest — unit tests with mock Helidon MCP server
Phase 4: McpConfigTest — unit tests for app.mcp() wiring
Phase 5: manual integration — documented test script
Phase 6: ElicitationTest — unit tests for pause/resume flow
```

---

## Non-Goals

- CafeAI does not implement the MCP protocol
- CafeAI does not implement MCP authentication (delegated to Helidon MCP + standard HTTP auth middleware)
- CafeAI does not build a UI for workflow composition (that is n8n's job)
- CafeAI does not implement tool versioning in the MCP server (future work)
