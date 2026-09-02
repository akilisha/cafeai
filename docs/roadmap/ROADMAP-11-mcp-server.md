# ROADMAP-11: Helidon Escape Hatch + MCP Exposure Pattern

> **Note:** `cafeai-tools` was removed in session 9 (deprecated
> `dev.langchain4j.agent.tool` APIs). MCP *consumption* (calling an MCP server's
> tools) is now part of the agent story — LangChain4j's `McpToolProvider`, see
> [ROADMAP-12](ROADMAP-12-agents.md). MCP *exposure* (serving CafeAI capabilities
> as MCP tools) is the `app.helidon()` escape-hatch pattern this document
> describes, which is implemented and current.

**Maps to:** No Express equivalent — this is CafeAI's strategy for raw Helidon access  
**Modules:** `cafeai-core` (escape hatch implemented), `cafeai-mcp` (abandoned — see below)  
**ADR Reference:** SPEC.md §11  
**Status:** ✅ Phase 1 Complete, ✅ Phase 2 Complete (redesigned), ⬜ Phase 3 deferred

---

## MCP is three concerns, in three places

"MCP" is not one feature. The reconciliation with ROADMAP-12 settled it as three distinct
things with three different homes and lifecycles:

| # | Concern | Home | Lifecycle |
|---|---|---|---|
| 1 | **Serve** — expose CafeAI's tools/agents *as* an MCP server | `app.helidon()` + Helidon `McpFeature` | routing registration, no module, no external state |
| 2 | **Reach** — connect to an external MCP server | `cafeai-connect` `McpEndpoint` connector *(planned)* | persistent transport, three-state reachability, fallback policy — like Redis/Ollama/pgvector |
| 3 | **Give** — make a reached server's tools callable by an agent | `cafeai-agents` — adapt a named MCP connection to a LangChain4j `ToolProvider` (ROADMAP-12) | built with the agent's `AiService`, disposable |

This document covers **#1**. #2 is a follow-on connector in `cafeai-connect` (see ROADMAP-10);
#3 is ROADMAP-12. There is no `cafeai-mcp` module in any of them.

---

## What Changed and Why

The original ROADMAP-11 planned a `cafeai-mcp` module that would bridge CafeAI's tool
registry into Helidon's MCP server extension. During implementation (April 2026), this was
abandoned for two reasons:

1. **Architectural mismatch.** Helidon MCP requires Helidon Inject — a compile-time annotation
   processor and service registry. CafeAI is deliberately pure Helidon SE with no injection
   framework. Forcing Helidon Inject into CafeAI would have changed the character of the
   entire framework to reconcile a single capability.

2. **Wrong ownership.** CafeAI should not own the MCP server. It contributes tools.
   The protocol infrastructure belongs to Helidon — or to whatever the developer chooses.

The resolution was to build something more fundamental and more honest: **the Helidon escape
hatch**. Instead of trying to absorb every Helidon capability into CafeAI abstractions,
give developers direct access to the underlying Helidon builders when they need to reach past
CafeAI's vocabulary.

---

## What Was Built: `app.helidon()`

CafeAI is an opinion on top of Helidon SE, not a cage around it. `app.helidon()` returns a
fluent configurator that gives direct access to the underlying Helidon builders before the
server starts.

```java
// TLS and connection tuning — server builder access
app.helidon()
   .server(builder -> builder
       .tls(TlsConfig.builder()
           .privateKey(KeyConfig.pemBuilder()
               .key(Paths.get("/certs/server.key"))
               .certChain(Paths.get("/certs/server.crt"))
               .build())
           .build()));

// Raw Helidon routing alongside CafeAI routes
app.helidon()
   .routing(routing -> routing
       .get("/helidon-native", (req, res) -> res.send("ok"))
       .register("/grpc", myGrpcService));

app.listen(8080);
```

Both methods return `HelidonConfig` for chaining:

```java
app.helidon()
   .server(builder -> builder.maxConcurrentRequests(500))
   .routing(routing -> routing.get("/probe", (req, res) -> res.send("alive")));
```

### When to use it

Use `app.helidon()` when you need a Helidon capability that has no CafeAI abstraction:

- **TLS** — `app.helidon().server(b -> b.tls(...))`
- **HTTP/2 tuning** — `app.helidon().server(b -> b.connectionConfig(...))`
- **gRPC endpoints** — `app.helidon().routing(r -> r.register("/grpc", service))`
- **MCP server** — see pattern below
- **Native Helidon health format** — `app.helidon().routing(r -> r.register(HealthFeature.create()))`
- **Connection limits, keep-alive, backpressure** — server builder access

### MCP via `app.helidon()`

The correct MCP pattern is:

```java
// CafeAI contributes the tools
app.tool(new GitHubTools());
app.tool(new DatabaseTools());

// Helidon exposes them via MCP — CafeAI steps aside
app.helidon()
   .routing(routing -> {
       McpFeature mcp = McpFeature.builder()
           .addTool(/* bridge your tools here */)
           .build();
       routing.register("/mcp", mcp);
   });

app.listen(8080);
```

CafeAI provides the tools. Helidon provides the MCP protocol. No forced reconciliation.
A helper utility for bridging CafeAI's `ToolRegistry` into the MCP tool format is a
candidate for a future lightweight `cafeai-mcp-bridge` utility class — but it lives
outside `cafeai-core` and requires no injection framework.

---

## Phase Summary

### Phase 1 — Helidon 4.4.0 + LangChain4j 1.11.0 Migration ✅

- Upgraded from Helidon 4.1.4 → 4.4.0
- Upgraded from LangChain4j alpha → 1.11.0
- Fixed 7 breaking API changes
- 304 tests passing

### Phase 2 — Helidon Escape Hatch ✅

`app.helidon()` implemented in `cafeai-core`. Provides:

- `HelidonConfig server(Consumer<WebServerConfig.Builder>)` — server-level access
- `HelidonConfig routing(Consumer<HttpRouting.Builder>)` — routing-level access
- Both consumers applied during `listen()` after CafeAI assembles its own routing
- 7 new tests covering null checks, chaining, and integration

**API surface:**
```java
CafeAI.HelidonConfig helidon();

interface HelidonConfig {
    HelidonConfig server(Consumer<WebServerConfig.Builder> consumer);
    HelidonConfig routing(Consumer<HttpRouting.Builder> consumer);
}
```

### Phase 3 — `cafeai-mcp` Module ❌ Abandoned

The `cafeai-mcp` module was attempted and abandoned. See "What Changed and Why" above.
The correct pattern for MCP *exposure* is Phase 2's `app.helidon()`.

### Follow-on (not this roadmap) — `McpEndpoint` connector in `cafeai-connect`

MCP *consumption* — reaching an external MCP server so its tools are available to agents —
is a planned `cafeai-connect` connector: `app.connect(McpEndpoint.at(url).onUnavailable(...))`.
It owns the `initialize` handshake, the persistent transport, health state, and the fallback
policy. `cafeai-agents` then references it by name and adapts it to a LangChain4j
`ToolProvider`. Tracked against `cafeai-connect` (ROADMAP-10), consumed by ROADMAP-12.

---

## Non-Goals (Updated)

- CafeAI does not own the MCP server — that is Helidon's or the developer's responsibility
- CafeAI does not require Helidon Inject or any annotation processing framework
- CafeAI does not reimplement the MCP client protocol — LangChain4j's `McpClient` /
  `McpToolProvider` does that; `cafeai-connect` wraps it in a `Connection` lifecycle
- `app.helidon()` is an escape hatch, not an invitation to bypass CafeAI for routine work
