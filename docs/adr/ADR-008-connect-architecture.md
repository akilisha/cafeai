# ADR-008: The Connect Architecture — In-Process vs Out-of-Process Extension

**Date:** March 2026  
**Status:** Accepted  
**Module:** `cafeai-connect`  
**Authors:** CafeAI Core Team

---

## Context

As CafeAI's capability surface grew through Phases 3–5 of ROADMAP-07 —
memory tiers, RAG pipelines, tools and MCP — a design question crystallised:

> *Where is the right boundary between CafeAI itself and the services it uses?*

Redis is not CafeAI. pgvector is not CafeAI. Ollama is not CafeAI. An MCP
server running on port 3000 is not CafeAI. These are independent services
with independent lifecycles, independent scale, and independent deployment.
CafeAI *uses* them — it does not *contain* them.

Yet the existing registration API treated them the same as in-process modules:

```java
app.memory(MemoryStrategy.redis(RedisConfig.of("redis:6379")));  // out-of-process
app.memory(MemoryStrategy.inMemory());                           // in-process
```

The developer had to know which was which. More importantly, the framework had
no model for the fundamental difference between a capability that starts and
stops with the JVM, and one that might be temporarily unreachable, might scale
independently, or might not exist at all in some environments.

---

## Decision

We formalise the distinction between **in-process extensions** and
**out-of-process connections** as a first-class architectural boundary.

### The Two Extension Models

**In-process optional modules** (`cafeai-memory`, `cafeai-rag`):
- Run inside the same JVM as CafeAI
- Share CafeAI's lifecycle — they start when CafeAI starts, stop when it stops
- Are either present (JAR on classpath) or absent — binary
- Registered via module-specific APIs: `app.memory()`, `app.vectordb()`
- Activated by adding a JAR; deactivated by removing it

**Out-of-process connections** (`cafeai-connect`):
- Run in separate processes with independent lifecycles
- CafeAI reaches out to them over a network boundary
- Have *three* reachability states: reachable, unreachable, degraded
- Registered via a single surface: `app.connect()`
- Come with an explicit degradation policy (`Fallback`)
- Can be discovered from the environment: `Connect.fromEnv()`

### The `Connection` Interface

Every out-of-process service is a `Connection`:

```java
public interface Connection {
    String       name();       // for logging and health checks
    ServiceType  type();       // LLM, MEMORY, VECTOR_DB, MCP, EMBEDDING, CUSTOM
    HealthStatus probe();      // is it reachable right now?
    void         register(CafeAI app);  // wire its capability in
    Fallback     fallback();   // what to do if probe fails
}
```

### The `Fallback` Model

A `Connection` carries its own degradation policy:

```java
Fallback.warnAndContinue()         // log warning, start anyway (default)
Fallback.failFast()                // abort startup if unreachable
Fallback.use(OpenAI.gpt4o())       // register an alternative capability
Fallback.connectInstead(other)     // try a different Connection
Fallback.ignore()                  // silently skip
```

This encodes operational intelligence at the connection level — not in
application code, not in infrastructure, but at the exact point where the
policy is relevant.

### The Registration Surface

```java
// One method for all out-of-process services
app.connect(Redis.at("redis:6379"));
app.connect(Ollama.at("http://ollama:11434").model("llama3"));
app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai"));

// With explicit fallback policy
app.connect(Ollama.at("http://ollama:11434").model("llama3")
    .onUnavailable(Fallback.use(OpenAI.gpt4o())));

// Environment-driven
Connect.fromEnv().forEach(app::connect);
```

### The `cafeai-core` Contract

`cafeai-core` does not import anything from `cafeai-connect`. The
`app.connect(Object)` method takes `Object` and routes through the
`ConnectBridge` SPI — the same pattern used for `RagPipeline` and
`cafeai-core` is completely unaware that `cafeai-connect`
exists. Adding `cafeai-connect` to the classpath activates the feature.

---

## Consequences

### Positive

**CafeAI's core remains small.** The boundary is now explicit. `cafeai-core`
is an Express-equivalent HTTP framework with thin AI primitives. Everything
else is optional — either an in-process module or an out-of-process connection.

**The framework models reality correctly.** Redis going down is not the same
as `cafeai-memory` not being on the classpath. The three-state health model
(`REACHABLE`, `UNREACHABLE`, `DEGRADED`) captures what actually happens in
production. A binary present/absent model does not.

**Degradation is a first-class citizen.** `Fallback` transforms what is usually
an ops concern (what happens when Redis is down?) into a developer-expressed
policy at the point where it matters. The application code never changes —
only the fallback strategy does.

**Open to any future service.** The `Connection` interface makes no assumptions
about protocol, transport, or service type. Any service that can be probed
and that can register a capability with CafeAI can become a `Connection`.
Services we have not imagined yet — future vector databases, future LLM
providers, future protocol servers — fit without framework changes.

**Environment-driven deployment story.** `Connect.fromEnv()` reads standard
environment variables and assembles a connection graph. Docker Compose,
Kubernetes, `.env` files, CI pipelines — all the same to CafeAI. No
Docker-specific code, no Kubernetes-specific code. Just environment variables
and the `Connection` abstraction.

### Trade-offs

**Two registration patterns to learn.** Developers now have both
`app.memory(MemoryStrategy.redis(...))` (in-process module API) and
`app.connect(Redis.at(...))` (out-of-process connection API). The distinction
is semantically correct but adds surface area.

**Reflection for some registrations.** `PgVector.register()` and
`RagPipelineEndpoint.register()` uses reflection to call into `cafeai-rag` to avoid compile-time circular dependencies.
This is a known trade-off — the same pattern used by `RagPipeline` SPI and

---

## Future Directions

The `Connection` abstraction opens several directions that require no framework
changes:

- **`Chroma.at(url)`** — Chroma vector database connection
- **`Weaviate.at(url)`** — Weaviate vector database connection  
- **`Pinecone.at(url).apiKey(...)`** — Pinecone managed vector database
- **`OpenAICompatible.at(url)`** — any OpenAI-API-compatible LLM server
- **`Qdrant.at(url)`** — Qdrant vector search engine
- **Retry and circuit-breaker policies** as additional `Fallback` strategies
- **Connection pool monitoring** integrated into `Connect.healthCheck()`
- **Automatic reconnection** when a previously unreachable service comes up

Each of these is a `Connection` implementation in `cafeai-connect` or in a
community module. None require changes to `cafeai-core`.

---

## Relationship to Other ADRs

- **ADR-002 (ServiceLoader):** `ConnectBridge` SPI follows the same pattern as
  `MemoryStrategyProvider`, `RagPipeline`, and The ServiceLoader
  is the consistent extension mechanism across all module boundaries.
- **ADR-003 (Module Structure):** `cafeai-connect` is a new category of module —
  neither a core primitive nor an in-process optional — but the pattern fits the
  existing module hierarchy without disruption.
