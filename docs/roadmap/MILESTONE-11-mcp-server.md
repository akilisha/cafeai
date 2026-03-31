# MILESTONE-11: CafeAI MCP Server

**Roadmap:** ROADMAP-11  
**Module:** `cafeai-mcp` (new), `cafeai-core`, `cafeai-tools`  
**Started:** —  
**Current Status:** 🔴 Not Started

---

## Progress Tracker

| Phase | Description | Module | Status | Completed |
|---|---|---|---|---|
| Phase 1 | Helidon 4.4 upgrade (shared with ROADMAP-12) | root `build.gradle` | 🔴 Not Started | — |
| Phase 2 | `cafeai-mcp` module scaffold | `cafeai-mcp` | 🔴 Not Started | — |
| Phase 3 | `McpServerBridge` — tool registry to Helidon MCP | `cafeai-mcp` | 🔴 Not Started | — |
| Phase 4 | `app.mcp().serve()` API surface | `cafeai-core`, `cafeai-mcp` | 🔴 Not Started | — |
| Phase 5 | n8n integration verification | `cafeai-examples` | 🔴 Not Started | — |
| Phase 6 | Elicitation support (HITL via MCP) | `cafeai-mcp` | 🔴 Not Started | — |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised · 🔷 Deferred

---

## Measurable Outputs

By the end of this milestone, the following must be demonstrable:

| Output | How Measured |
|---|---|
| All existing tests pass on Helidon 4.4 + LangChain4j 1.11 | `./gradlew test` — 278+ tests, 0 failures |
| `app.mcp().serve("/mcp")` compiles and starts | Server startup log shows MCP line |
| All `@CafeAITool` methods discoverable via MCP | `curl http://localhost:8080/mcp` returns tool list |
| n8n connects and discovers tools | Manual verification — tools appear as n8n nodes |
| n8n workflow invokes tool, receives result | Manual verification — end-to-end workflow runs |
| `cafeai-support` test suite still passes | `./test.sh` returns 9/9 |
| `cafeai-mcp` module published to mavenLocal | `./gradlew publishToMavenLocal` succeeds |

---

## Key Decisions Recorded

**Why Helidon MCP and not a custom implementation:**
Helidon 4.3/4.4 already implements MCP 1.1 aligned with the June 2025 specification, including
Elicitation. CafeAI sits on Helidon. Writing a competing MCP implementation would produce
something worse. CafeAI writes the bridge from its tool registry to Helidon's registration API —
approximately 300 lines. Helidon handles the protocol.

**Why `app.mcp().serve()` and not automatic exposure:**
Explicit registration is intentional. Not every CafeAI application should be an MCP server.
The developer opts in with one line. Automatic exposure would violate the principle of
least surprise.

**Why tools and agents are both exposed:**
The MCP client does not distinguish between a Java method annotated `@CafeAITool` and a
Helidon agentic LangChain4j interface. Both are capabilities. Both deserve a discoverable
identity. The bridge registers both from their respective registries.

---

## Dependencies

- Helidon 4.4.0+ (provides `io.helidon.integrations.mcp` — MCP 1.1 server)
- ROADMAP-07 Phases 5+ complete (tool registry populated)
- ROADMAP-12 Phase 1 complete (Helidon upgrade — shared prerequisite)
