# MILESTONE-10: cafeai-connect — Phase 1 Complete

**Roadmap:** ROADMAP-10  
**Module:** `cafeai-connect`  
**Completed:** March 2026  
**Status:** 🟢 Complete

---

## What Was Built

### Core Abstractions (3 files)

**`Connection`** — the central interface. Every out-of-process service is a
`Connection`. Three responsibilities: `probe()` to check reachability,
`register(CafeAI)` to wire the capability, `fallback()` to handle unavailability.
The `onUnavailable(Fallback)` default method returns a decorated connection —
the fluent API works without a separate builder pattern.

**`HealthStatus`** — three-state reachability model. `REACHABLE` (with latency),
`UNREACHABLE` (with reason), `DEGRADED` (connected but struggling). This is the
correct model for network services — binary present/absent is wrong.

**`Fallback`** — functional interface with four built-in strategies:
- `warnAndContinue()` — log, keep going. Default for all connections.
- `failFast()` — throw `ServiceUnavailableException`, abort startup.
- `use(alternative)` — register an alternative CafeAI capability instead.
- `connectInstead(connection)` — try a different `Connection`.
- `ignore()` — silently skip.

### Built-in Connectors (4 files)

| Class | Probe method | Registers |
|---|---|---|
| `Redis` | TCP socket to host:port | `MemoryStrategy.redis()` |
| `Ollama` | HTTP GET `/api/tags` + model name check | `app.ai(Ollama.at(...))` |
| `PgVector` | `DriverManager.getConnection()` | `app.vectordb(PgVectorStore)` — via reflection |
| `McpEndpoint` | HTTP GET, 2xx/4xx = up | `app.mcp(McpServer)` — via reflection |

`PgVector` and `McpEndpoint` use reflection to call into `cafeai-rag` and
`cafeai-tools` respectively — avoids compile-time dependency while still
enabling full registration.

### Entry Point (1 file)

**`Connect`** — two static methods:
- `fromEnv()` — reads `CAFEAI_*`, `REDIS_*`, `DATABASE_URL`, `OLLAMA_BASE_URL`,
  `CAFEAI_MCP_SERVERS` and returns a `List<Connection>`. Returns empty list if
  nothing configured. Not autoconfiguration — nothing happens until the developer
  calls `app.connect()` on each result.
- `healthCheck(CafeAI app)` — returns a `Middleware` that probes all registered
  connections and responds with JSON status map. HTTP 200 if all healthy, 503 if
  any degraded or unreachable.

### SPI Bridge (2 files across 2 modules)

**`ConnectBridge`** in `cafeai-core` — 24-line SPI interface. The only seam
between core and the connect module. `app.connect(Object)` loads this via
ServiceLoader and delegates.

**`CafeAIConnectBridge`** in `cafeai-connect` — casts the `Object` to `Connection`,
adds to the `Locals.CONNECTIONS` registry, probes, then either calls
`register()` or invokes the fallback.

---

## Architectural Significance

This module represents a design pivot that clarifies CafeAI's entire extension
model. Before `cafeai-connect`, all optional capabilities were in-process modules.
After it, CafeAI has two explicit extension models:

**In-process optional** (`cafeai-memory`, `cafeai-rag`, `cafeai-tools`):
- Same JVM, same lifecycle
- Binary: present or absent
- Registered via module-specific methods: `app.memory()`, `app.vectordb()`, `app.tool()`

**Out-of-process connectable** (`cafeai-connect`):
- Separate process, independent lifecycle
- Three states: reachable, unreachable, degraded
- All registered via one surface: `app.connect()`
- Always carry a degradation policy

This boundary also clarifies what belongs in `cafeai-core`: only what genuinely
needs to run in the same JVM. Everything else is either an in-process module or
an out-of-process connection.

---

## Design Decisions Made

**`fromEnv()` returns a List, not void** — the developer must explicitly call
`app.connect()` on each result. This is not Spring autoconfiguration. Activation
is explicit and visible in the application code.

**Reflection for `PgVector` and `McpEndpoint` registration** — `cafeai-connect`
only hard-depends on `cafeai-core`. `cafeai-rag` and `cafeai-tools` are accessed
via `Class.forName()`. A missing module produces a clear error with the exact
dependency coordinates to add.

**`onUnavailable()` returns a new `Connection`** — immutable decoration rather
than builder mutation. Consistent with CafeAI's general preference for
value-oriented configuration objects.

**No Docker-specific code** — the module name `cafeai-connect` was chosen
deliberately over `cafeai-docker`. Environment variables are infrastructure-agnostic:
Docker Compose, Kubernetes, bare metal, `.env` files — all identical to the module.

---

## What Remains (Future Phases)

See ROADMAP-10 for planned phases:
- **Phase 2** — Chroma, Weaviate, Qdrant, OpenAI-compatible, Pinecone connectors
- **Phase 3** — retry/circuit-breaker Fallback strategies, background health monitoring
- **Phase 4** — Custom Connection SDK for community and enterprise connectors
