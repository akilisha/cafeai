# ROADMAP-10: cafeai-connect — Out-of-Process Service Connectivity

**Status:** 🟢 Initial release complete  
**Module:** `cafeai-connect`  
**Depends on:** `cafeai-core` (compile), `cafeai-memory` (optional runtime), `cafeai-rag` (optional runtime), `cafeai-tools` *(removed — see ROADMAP-17)*

---

## Origin

This roadmap emerged from a conversation about Docker integration during the
ROADMAP-07 Phase 5 implementation session. What began as "how do we package
CafeAI for Docker?" crystallised into a more fundamental architectural insight:

> *In-process and out-of-process are fundamentally different extension models
> and they deserve different surfaces.*

`cafeai-memory`, `cafeai-rag`, and `cafeai-tools` are in-process — they run
inside the JVM, share CafeAI's lifecycle, and are binary (present or absent).
Redis, pgvector, Ollama, and MCP servers are out-of-process — they have
independent lifecycles and three reachability states: reachable, unreachable,
degraded.

`cafeai-connect` formalises this boundary. See **ADR-008** for the full
architectural rationale.

---

## Core Concepts

### The `Connection` Interface

Every out-of-process service is a `Connection`:

```java
public interface Connection {
    String      name();           // human-readable name for logs/health
    ServiceType type();           // LLM, MEMORY, VECTOR_DB, MCP, EMBEDDING, CUSTOM
    HealthStatus probe();         // is it reachable right now?
    void         register(CafeAI app); // wire its capability into the application
    Fallback     fallback();      // what to do if probe fails
}
```

### The `Fallback` Model

```java
Fallback.warnAndContinue()         // default — log, keep going
Fallback.failFast()                // abort startup if unreachable
Fallback.use(OpenAI.gpt4o())       // activate an alternative capability
Fallback.connectInstead(other)     // try a different Connection
Fallback.ignore()                  // silently skip
```

### The `HealthStatus` Model

Three states — not two:

- `REACHABLE` — service responded within probe timeout
- `UNREACHABLE` — could not connect; try a fallback
- `DEGRADED` — connected but reporting a problem (e.g. Ollama up, model not pulled)

---

## Phase 1 — Core Abstraction + Built-in Connectors ✅ (March 2026)

### Delivered

- `Connection` interface — probe / register / fallback contract
- `HealthStatus` — three-state reachability model with latency tracking
- `Fallback` — functional interface with four built-in strategies
- `ConnectBridge` SPI in `cafeai-core` — routes `app.connect(Object)` to the module
- `CafeAIConnectBridge` — ServiceLoader implementation
- `app.connect(Object)` on `CafeAI` interface and `CafeAIApp`
- `Locals.CONNECTIONS` — connection registry key for health check access

**Built-in connectors:**

| Connector | Probes | Registers as |
|---|---|---|
| `Redis.at(host:port)` | TCP socket connect | `MemoryStrategy.redis()` |
| `Ollama.at(url).model(id)` | HTTP `/api/tags` + model check | `app.ai(Ollama.at(...))` |
| `PgVector.at(jdbcUrl)` | JDBC connection attempt | `app.vectordb(PgVectorStore)` |
| `McpEndpoint.at(url)` | *(removed — see ROADMAP-17)* | — |

**Registration surface:**

```java
app.connect(Redis.at("redis:6379"));
app.connect(Ollama.at("http://ollama:11434").model("llama3")
    .onUnavailable(Fallback.use(OpenAI.gpt4o())));
app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai")
    .onUnavailable(Fallback.failFast()));
app.connect(McpEndpoint.at("http://mcp-server:3000")
    .onUnavailable(Fallback.ignore()));

// Environment-driven
Connect.fromEnv().forEach(app::connect);

// Health endpoint
app.get("/health", Connect.healthCheck(app));
```

**Environment variables recognised by `Connect.fromEnv()`:**

| Variable | Effect |
|---|---|
| `CAFEAI_AI_PROVIDER=ollama` | Creates `Ollama.at(OLLAMA_BASE_URL).model(CAFEAI_AI_MODEL)` |
| `CAFEAI_MEMORY=redis` | Creates `Redis.at(REDIS_HOST:REDIS_PORT)` or parses `REDIS_URL` |
| `CAFEAI_VECTOR_DB=pgvector` | Creates `PgVector.at(DATABASE_URL)` |
| `CAFEAI_MCP_SERVERS` | Comma-separated list → one `McpEndpoint` per URL |

---

## Phase 2 — Extended Connectors (Planned)

Additional built-in connectors for the most common out-of-process services:

- `Chroma.at(url)` — Chroma vector database
- `Weaviate.at(url)` — Weaviate vector search engine
- `Qdrant.at(url)` — Qdrant vector search engine
- `OpenAICompatible.at(url)` — any OpenAI-API-compatible LLM (vLLM, LM Studio, Groq)
- `Pinecone.at(url).apiKey(key)` — Pinecone managed vector database

---

## Phase 3 — Resilience Policies (Planned)

Enhanced `Fallback` strategies:

- `Fallback.retry(attempts, delay)` — retry before declaring unreachable
- `Fallback.circuitBreaker(threshold, timeout)` — stop probing after N failures
- `Fallback.timeout(Duration)` — override default probe timeout per connection

Connection lifecycle management:

- Background health monitoring — periodic re-probe of registered connections
- Automatic re-registration when a previously unreachable service becomes reachable
- Connection pool health reporting integrated into `Connect.healthCheck()`

---

## Phase 4 — Custom Connection SDK (Planned)

Make it straightforward for the community and enterprise teams to build and
publish their own `Connection` implementations:

- `AbstractHttpConnection` — base class for HTTP-probed services
- `AbstractJdbcConnection` — base class for JDBC-probed services
- `AbstractTcpConnection` — base class for TCP socket-probed services
- Documentation and examples for publishing a custom connection as a JAR

---

## Design Invariants

These must not change regardless of future phases:

1. `cafeai-core` never imports from `cafeai-connect` — only the `ConnectBridge` SPI seam
2. `cafeai-connect` only hard-depends on `cafeai-core` — all other module access is via reflection or runtime classpath
3. `app.connect()` is the single registration surface for all out-of-process services
4. Every `Connection` must implement `probe()` — connectivity awareness is non-optional
5. Every `Connection` has a `Fallback` — graceful degradation is non-optional
6. `HealthStatus` always has three states — binary reachability is wrong for network services
