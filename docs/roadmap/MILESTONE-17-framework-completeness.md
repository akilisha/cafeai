# MILESTONE-17 — Framework Completeness

**Current Status:** 🔴 Not Started

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | ReAct agent loop (`cafeai-agents`) | 🔴 |
| 2 | `AgentDefinition` API | 🔴 |
| 3 | `app.agent()` entry point | 🔴 |
| 4 | Multi-agent orchestration (Structured Concurrency) | 🔴 |
| 5 | `app.orchestrate()` entry point | 🔴 |
| 6 | nova-tutor agent integration | 🔴 |
| 7 | PgVector implementation | 🔴 |
| 8 | PgVector integration test | 🔴 |
| 9 | Real OpenTelemetry spans | 🔴 |
| 10 | OTel semantic conventions for AI | 🔴 |
| 11 | Hybrid retrieval (BM25 + dense) | 🔴 |
| 12 | 0.2.0 release to Maven Central | 🔴 |

---

## Phase 1 — ReAct Agent Loop

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ReActAgent` class in `cafeai-agents` implements the reason/act loop
- [ ] `AgentResult` carries final answer and full reasoning trace
- [ ] `maxIterations` guard throws `AgentMaxIterationsException`
- [ ] Tool errors are caught and returned as observations, not exceptions
- [ ] Each iteration traced via `ObserveBridge`
- [ ] Agent unit tests: 2-iteration success, max iterations exceeded,
      tool error handled gracefully

### ReAct Loop Contract
```
while not finished and iterations < maxIterations:
    thought = prompt(state + tools)
    if thought.isFinal():
        return AgentResult(thought.answer, trace)
    action = thought.action
    observation = tools.invoke(action.tool, action.args)
    state = state + [thought, observation]
    iterations++
throw AgentMaxIterationsException(maxIterations)
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — `AgentDefinition` API

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AgentDefinition.react()` fluent builder
- [ ] `.tools(Object... toolObjects)` — registers `@CafeAITool` methods
- [ ] `.maxIterations(int)` — loop guard (default: 5)
- [ ] `.systemPrompt(String)` — agent-specific system prompt
- [ ] `.provider(String name)` — use named provider for reasoning
- [ ] `.stopWhen(StopCondition)` — early exit condition
- [ ] `AgentDefinition` is immutable once built

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `app.agent()` Entry Point

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAI.agent(String name, AgentDefinition def)` registers an agent
- [ ] `CafeAI.agent(String name)` returns an `AgentRequest`
- [ ] `AgentRequest.run(String input)`, `.session(String)`, `.call()` chain
- [ ] `AgentResult.answer()`, `.trace()`, `.iterations()` accessible
- [ ] `beforeAgent`/`afterAgent` hooks on `ObserveBridge`
- [ ] Guardrails apply to agent final output (POST_LLM position)
- [ ] Unknown agent name throws with helpful message
- [ ] `./gradlew :cafeai-agents:test` — all tests pass

### Usage
```java
app.agent("qualifier", AgentDefinition.react()
    .tools(new CreditCheckTool(), new ComplianceTool())
    .maxIterations(5)
    .provider("tutor"));

AgentResult result = app.agent("qualifier")
    .run("Qualify applicant A123 for $250,000 mortgage")
    .session("applicant-A123")
    .call();

System.out.println(result.answer());     // final answer
System.out.println(result.iterations()); // how many iterations
result.trace().forEach(System.out::println); // reasoning trace
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — Multi-Agent Orchestration

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `StructuredTaskScope.ShutdownOnFailure` used for agent fan-out
- [ ] All agents in scope cancelled immediately if any fails
- [ ] `OrchestrationResult` carries results from all agents
- [ ] `OrchestrationResult.get(String agentName)` retrieves individual result
- [ ] Orchestration traced end-to-end via `ObserveBridge`
- [ ] Unit tests: all succeed, one fails (all cancelled), timeout behaviour

### Structured Concurrency Contract
```
ShutdownOnFailure scope:
    fork all agents as subtasks
    join — blocks until all complete or any fails
    if any failed: throwIfFailed() propagates first failure
    all subtasks cancelled on failure — no resource leaks
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 5 — `app.orchestrate()` Entry Point

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAI.orchestrate(String name, String... agentNames)` registers pipeline
- [ ] `CafeAI.orchestrate(String name)` (no agents) returns `OrchestrationRequest`
- [ ] `OrchestrationRequest.input(String)`, `.session(String)`, `.call()` chain
- [ ] All named agents must be registered before orchestration is defined
- [ ] Unknown agent name in orchestration throws at registration time (not call time)

### Usage
```java
app.orchestrate("loan-pipeline", "classifier", "qualifier", "compliance");

OrchestrationResult result = app.orchestrate("loan-pipeline")
    .input(applicantData)
    .session("A123")
    .call();

result.get("qualifier").answer();   // → "APPROVED"
result.get("compliance").answer();  // → "COMPLIANT"
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 6 — nova-tutor Agent Integration

**Status:** 🔴 Not Started  
**Depends on:** Phases 1–5, Capstone 5 Phases 1–5

### Acceptance Criteria
- [ ] `nova-tutor` `TutorAgent.java` manual loop replaced with `app.agent()`
- [ ] `WhiteboardTool` and `LessonProgressTool` registered as `@CafeAITool`
- [ ] End-to-end test passes with agent-based loop
- [ ] Reasoning trace visible in observability output

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — PgVector Implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `PgVectorConfig` builder in `cafeai-rag`
- [ ] `PgVectorStore` implements `VectorStore` SPI
- [ ] `VectorStore.pgVector(config)` factory method registered via SPI
- [ ] DDL auto-migration on first connection (create table if not exists)
- [ ] `upsert()` uses `INSERT ... ON CONFLICT (id) DO UPDATE`
- [ ] `search()` uses `ORDER BY embedding <=> $1 LIMIT $2` (cosine)
- [ ] `deleteBySource()` uses `DELETE WHERE source_id = $1`
- [ ] HikariCP connection pool with sensible defaults
- [ ] `./gradlew :cafeai-rag:compileJava` passes

### Schema
```sql
CREATE TABLE IF NOT EXISTS cafeai_chunks (
    id          TEXT PRIMARY KEY,
    source_id   TEXT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1536),
    metadata    JSONB,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS cafeai_chunks_embedding_idx
    ON cafeai_chunks USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS cafeai_chunks_source_idx
    ON cafeai_chunks (source_id);
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — PgVector Integration Test

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] Testcontainers `pgvector/pgvector:pg16` container used
- [ ] Full RAG pipeline test: ingest → search → delete by source → re-ingest
- [ ] Cosine similarity results ordered correctly
- [ ] Connection pool properly closed after test
- [ ] `./gradlew :cafeai-rag:test` includes integration test

### Notes
Testcontainers requires Docker. CI must have Docker available.

---

## Phase 9 — Real OpenTelemetry Spans

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `ObserveStrategy.otel()` produces real spans (not stubs)
- [ ] `gen_ai.system` attribute set on all spans
- [ ] `gen_ai.operation.name` correct for each call type (chat, embed, etc.)
- [ ] `gen_ai.request.model` and `gen_ai.response.model` set
- [ ] `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens` set
- [ ] Span lifecycle: started in `before*`, ended in `after*`
- [ ] Vision spans include content byte count
- [ ] Audio spans include MIME type and byte count
- [ ] Tool call spans nested under parent LLM span

### Notes
<!-- Add implementation notes here -->

---

## Phase 10 — OTel Semantic Conventions

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `Attributes.java` constants use semantic convention names
- [ ] RAG retrieval spans: `gen_ai.retrieval` with `db.system=vector_db`
- [ ] Agent spans: `gen_ai.agent.invoke` with `gen_ai.agent.iterations`
- [ ] `CHANGELOG.md` documents attribute name changes

### Reference
https://opentelemetry.io/docs/specs/semconv/gen-ai/

### Notes
<!-- Add implementation notes here -->

---

## Phase 11 — Hybrid Retrieval

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `Retriever.hybrid(k)` factory method
- [ ] `.denseWeight(double)` and `.sparseWeight(double)` configuration
- [ ] BM25 index built alongside vector index at ingestion
- [ ] Reciprocal Rank Fusion combines dense and sparse scores
- [ ] `HybridRetriever` tests: precision vs semantic-only on keyword-heavy queries
- [ ] `./gradlew :cafeai-rag:test` — all tests pass

### Notes
In-memory BM25 for now. PgVector-backed sparse index is a stretch goal.

---

## Phase 12 — 0.2.0 Maven Central Release

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] All ROADMAP-17 phases ✅ Complete
- [ ] `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
- [ ] `./gradlew javadoc` — zero warnings
- [ ] `CHANGELOG.md` covers all changes from 0.1.0-SNAPSHOT
- [ ] `MIGRATION.md` written: local Maven → Maven Central
- [ ] Module versions updated to `0.2.0`
- [ ] POM metadata complete: description, URL, SCM, developers, licenses
- [ ] GPG signing configured
- [ ] `./gradlew publishToMavenCentral` succeeds
- [ ] Artifacts visible at `search.maven.org/artifact/io.cafeai`
- [ ] GitHub release `v0.2.0` tagged with release notes
- [ ] capstone `build.gradle` files updated from `mavenLocal()` to `mavenCentral()`

### Maven Coordinates
```groovy
implementation 'io.cafeai:cafeai-core:0.2.0'
implementation 'io.cafeai:cafeai-rag:0.2.0'
implementation 'io.cafeai:cafeai-guardrails:0.2.0'
implementation 'io.cafeai:cafeai-observability:0.2.0'
implementation 'io.cafeai:cafeai-memory:0.2.0'
implementation 'io.cafeai:cafeai-agents:0.2.0'
implementation 'io.cafeai:cafeai-security:0.2.0'
implementation 'io.cafeai:cafeai-tools:0.2.0'
```

### Notes
<!-- Add implementation notes here -->

---

## Completion Definition

MILESTONE-17 is **complete** when:

1. All 12 phases show ✅ Complete
2. Test count >= 450 (359 + agent tests + PgVector + OTel + hybrid retrieval)
3. `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
4. `./gradlew javadoc` — zero warnings
5. `cafeai` 0.2.0 visible on Maven Central
6. nova-tutor Capstone 5 uses `app.agent()` for the tutor reasoning loop
7. `app.orchestrate()` demonstrated in a test

**What success looks like — the full API, realized:**

```java
var app = CafeAI.create();

// Three named providers
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

// Production memory and RAG
app.memory(MemoryStrategy.redis(config));
app.vectordb(VectorStore.pgVector(pgConfig));
app.embed(EmbeddingModel.local());
app.rag(Retriever.hybrid(5).denseWeight(0.7).sparseWeight(0.3));

// Safety and observability
app.guard(GuardRail.jailbreak());
app.guard(GuardRail.pii());
app.observe(ObserveStrategy.otel());
app.budget(TokenBudget.perMinute(60_000));

// Registered agents
app.agent("tutor", AgentDefinition.react()
    .tools(new WhiteboardTool(), new LessonProgressTool())
    .maxIterations(20)
    .provider("tutor"));

app.agent("assessor", AgentDefinition.react()
    .tools(new ComprehensionCheckTool())
    .maxIterations(3)
    .provider("tutor"));

// Multi-agent orchestration
app.orchestrate("lesson-pipeline", "tutor", "assessor");

// HTTP routes
app.filter(CafeAI.json());
app.post("/session", sessionHandler);
app.listen(8080);
```

Every line of that startup block is real, implemented, tested code.
That is what ROADMAP-17 delivers.
