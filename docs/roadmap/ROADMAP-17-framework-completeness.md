# ROADMAP-17 — Framework Completeness

> PgVector, OTel GenAI semantic conventions, hybrid retrieval, and the 0.2.0
> release to Maven Central.
>
> **Status (2026-09):** Phases 7–11 complete. Phase 12 (the 0.2.0 release) is the
> only thing left — see MILESTONE-17. Notes on what the phases turned out to be:
> - *OTel* — the spans were already real (a `GlobalOpenTelemetry` tracer, started
>   in `before*` / ended in `after*`). Phases 9–10 were an attribute-name pass to
>   the OTel GenAI semantic conventions, not a from-scratch implementation.
> - *Hybrid retrieval* — `Retriever.hybrid(k)` already existed as a stub with a
>   broken BM25; Phase 11 rewrote it with real term-frequency scoring and
>   `.denseWeight`/`.sparseWeight`.
>
> **Superseded:** the agent + orchestration portions of this roadmap (Phases 1–6)
> were replaced by **[ROADMAP-12](ROADMAP-12-agents.md)** — CafeAI binds LangChain4j
> `AiServices` rather than implementing its own ReAct loop / `app.orchestrate()`.

---

## Sequencing

```
ROADMAP-16 ✅
    ↓
nova-tutor (Capstone 5)
    ↓
Evangelism — blog series, conference talks, README polish
    ↓
ROADMAP-17
    ↓
0.2.0 on Maven Central
```

The evangelism phase is not a detour. The framework has earned an audience.
Building in public before the audience exists is how good tools stay unknown.

---

## What ROADMAP-17 delivers

### Agents & orchestration → moved to ROADMAP-12

The bespoke `AgentDefinition.react()` builder, `AgentResult.trace()`, and the
`app.orchestrate()` Structured-Concurrency primitive were dropped. CafeAI binds
LangChain4j `AiServices` — which owns the reasoning loop, tool dispatch, and chat
memory — and gives it an HTTP identity. Multi-agent workflows are supervisor-as-tool
or a middleware chain. See **[ROADMAP-12](ROADMAP-12-agents.md)**.

### PgVector — `VectorStore.pgVector()`

```java
app.vectordb(VectorStore.pgVector(
    PgVectorConfig.builder()
        .host("postgres.internal")
        .database("cafeai")
        .dimension(1536)
        .build()));
```

Production vector store on existing PostgreSQL infrastructure. ACID
transactions, SQL queryable, no new database service to operate.

### OpenTelemetry GenAI semantic conventions

```java
app.observe(ObserveStrategy.otel());
// Every call produces a span named by gen_ai.operation.name
// (chat / transcribe / invoke_agent / retrieve), carrying
// gen_ai.system, gen_ai.response.model,
// gen_ai.usage.input_tokens, gen_ai.usage.output_tokens.
// cafeai.latency_ms / cache_hit / rag.documents_retrieved / session.id
// remain as CafeAI extensions.
```

Integrates with Jaeger, Grafana, Honeycomb, Datadog out of the box.

### Hybrid retrieval

```java
app.rag(Retriever.hybrid(5)
    .denseWeight(0.7)   // semantic similarity
    .sparseWeight(0.3)); // BM25 keyword matching
```

Better than semantic-only for domains with product codes, policy numbers,
identifiers — anything with exact-match requirements alongside semantic search.

### 0.2.0 on Maven Central

```groovy
implementation 'com.akilisha.oss:cafeai-core:0.2.0'
implementation 'com.akilisha.oss:cafeai-rag:0.2.0'
// + cafeai-memory, -guardrails, -observability, -security, -streaming,
//   -connect, -views-mustache (cafeai-agents once ROADMAP-12 lands)
```

No more `mavenLocal()`. No more `publishToMavenLocal` before every project.
A versioned, tagged release with stable API guarantees.

---

## Phase inventory

| Phase | Description | Status |
|-------|-------------|--------|
| 1–6 | ~~Agents & orchestration~~ → **[ROADMAP-12](ROADMAP-12-agents.md)** | ⚫ superseded |
| 7 | PgVector implementation | 🟢 |
| 8 | PgVector integration test (Testcontainers) | 🟢 (needs Docker to run) |
| 9 | OTel spans → GenAI semconv attribute names | 🟢 |
| 10 | RAG retrieval + agent spans, semconv keys | 🟢 |
| 11 | Hybrid retrieval (BM25 + dense) — rewrite the stub | 🟢 |
| 12 | 0.2.0 release to Maven Central | 🔴 |

---

## What this roadmap does NOT cover

- Image generation — no genuine demand yet from the capstone series
- Real-time audio streaming — different pipeline model, different roadmap
- Multi-modal RAG — requires multi-modal embedding models
- CafeAI Cloud — not in scope for this project
- Fine-tuning integration — use the provider's API directly

The framework that ships 0.2.0 does 12 things well. Not 800 things tolerably.
