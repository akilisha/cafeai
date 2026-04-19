# ROADMAP-17 — Framework Completeness

> Agents, orchestration, PgVector, real OTel spans, hybrid retrieval,
> and the 0.2.0 release to Maven Central.
>
> This roadmap follows Capstone 5 (nova-tutor) and the evangelism push.
> The framework is already solid. ROADMAP-17 makes it complete.

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

### Agents — `app.agent()`

```java
app.agent("qualifier", AgentDefinition.react()
    .tools(new CreditCheckTool(), new ComplianceTool())
    .maxIterations(5)
    .provider("tutor"));

AgentResult result = app.agent("qualifier")
    .run("Qualify applicant A123 for $250,000 mortgage")
    .session("applicant-A123")
    .call();

result.answer();      // final answer
result.iterations();  // how many tool calls were needed
result.trace();       // full thought/action/observation log
```

ReAct loop (Reason + Act): the agent reasons about what to do, calls a tool,
observes the result, reasons again. Stops when it has a final answer or hits
`maxIterations`.

### Multi-agent orchestration — `app.orchestrate()`

```java
app.orchestrate("loan-pipeline", "classifier", "qualifier", "compliance");

OrchestrationResult result = app.orchestrate("loan-pipeline")
    .input(applicantData)
    .call();

result.get("classifier").answer();  // CONVENTIONAL
result.get("qualifier").answer();   // APPROVED
result.get("compliance").answer();  // COMPLIANT
```

Powered by Java 21 Structured Concurrency (`ShutdownOnFailure`): all agents
run concurrently, any failure cancels all, no dangling threads.

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

### Real OpenTelemetry spans

```java
app.observe(ObserveStrategy.otel());
// Every call produces a real span with GenAI semantic conventions:
// gen_ai.system, gen_ai.operation.name, gen_ai.request.model,
// gen_ai.usage.input_tokens, gen_ai.usage.output_tokens
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
implementation 'io.cafeai:cafeai-core:0.2.0'
implementation 'io.cafeai:cafeai-rag:0.2.0'
implementation 'io.cafeai:cafeai-agents:0.2.0'
```

No more `mavenLocal()`. No more `publishToMavenLocal` before every project.
A versioned, tagged release with stable API guarantees.

---

## Phase inventory

| Phase | Description |
|-------|-------------|
| 1 | ReAct agent loop |
| 2 | `AgentDefinition` API |
| 3 | `app.agent()` entry point |
| 4 | Multi-agent orchestration (Structured Concurrency) |
| 5 | `app.orchestrate()` entry point |
| 6 | nova-tutor agent integration |
| 7 | PgVector implementation |
| 8 | PgVector integration test (Testcontainers) |
| 9 | Real OpenTelemetry spans |
| 10 | OTel GenAI semantic conventions |
| 11 | Hybrid retrieval (BM25 + dense) |
| 12 | 0.2.0 release to Maven Central |

---

## What this roadmap does NOT cover

- Image generation — no genuine demand yet from the capstone series
- Real-time audio streaming — different pipeline model, different roadmap
- Multi-modal RAG — requires multi-modal embedding models
- CafeAI Cloud — not in scope for this project
- Fine-tuning integration — use the provider's API directly

The framework that ships 0.2.0 does 12 things well. Not 800 things tolerably.
