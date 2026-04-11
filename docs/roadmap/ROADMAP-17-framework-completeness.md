# ROADMAP-17 — Framework Completeness

> Implements the capabilities that the CafeAI README and API vocabulary
> have promised since the beginning but never delivered: agents, multi-agent
> orchestration, PgVector, real OpenTelemetry spans, hybrid retrieval,
> and the 0.2.0 release to Maven Central.
>
> ROADMAP-16 cleaned the house. ROADMAP-17 finishes building it.

---

## Context

CafeAI's README describes a framework that can do things the current codebase
cannot. This is the natural result of spec-first development — the API vocabulary
was designed to be complete and coherent, then implemented incrementally as
capstone applications created genuine demand.

The demand is now established. Four capstones, 359 tests, 15 roadmap items,
and one fully specified fifth capstone (nova-tutor) have proven that the core
primitives — prompt, vision, audio, memory, RAG, tools, guardrails, observability
— are correct and production-ready. The framework has earned the right to
implement the more ambitious capabilities that were always in the design.

Six capability areas remain unimplemented. Each has a clear design (from the
README and SPEC), a clear demand (from the capstone series), and a clear test
strategy (from the established testing patterns).

---

## Capability Map

| Area | Current State | ROADMAP-17 Goal |
|------|--------------|-----------------|
| Agents | Scaffolded module, no implementation | ReAct loop with tool use, configurable max iterations |
| Multi-agent orchestration | Specced, not started | Structured Concurrency fan-out, isolated failure handling |
| PgVector | Specced, not started | PostgreSQL + pgvector extension, production vector store |
| OpenTelemetry | Partial — console strategy complete, OTel stubs | Real OTel spans with semantic conventions for AI |
| Hybrid retrieval | Specced, not started | Dense + sparse (BM25) retrieval, combined scoring |
| 0.2.0 release | Local Maven only | Maven Central publication, stable API guarantees |

---

## Dependency Map

```
Phase 1  (ReAct agent loop — cafeai-agents)
    └── Phase 2  (AgentDefinition API — register agents on app)
    └── Phase 3  (app.agent() entry point)

Phase 4  (Multi-agent orchestration — Structured Concurrency)
    └── Phase 5  (app.orchestrate() entry point)
    └── Phase 6  (Agent capstone — nova-tutor uses app.agent())

Phase 7  (PgVector — cafeai-rag)
    └── Phase 8  (PgVector integration test)

Phase 9  (OpenTelemetry — real spans)
    └── Phase 10 (OTel semantic conventions for AI calls)

Phase 11 (Hybrid retrieval — cafeai-rag)

Phase 12 (0.2.0 release — Maven Central)
    └── depends on all prior phases
```

---

## Phase 1 — ReAct Agent Loop

**Goal:** Implement the `cafeai-agents` module. A ReAct (Reason + Act) agent
runs a loop: reason about the current state, decide whether to call a tool or
produce a final answer, call the tool if needed, observe the result, repeat.

**Design:**

```java
// AgentDefinition — configures the agent
AgentDefinition agentDef = AgentDefinition.react()
    .tools(new CreditCheckTool(), new ComplianceTool())
    .maxIterations(5)
    .stopWhen(result -> result.confidence() > 0.9);

// Register on the app
app.agent("qualifier", agentDef);
```

**The ReAct loop:**

```
Iteration 1:
  Thought: "I need to check the applicant's credit score first."
  Action: creditCheck(applicantId="A123", amount=250000)
  Observation: {"score": 720, "status": "ELIGIBLE"}

Iteration 2:
  Thought: "Credit is good. Now I need to check regulatory compliance."
  Action: complianceCheck(applicantId="A123", loanType="CONVENTIONAL")
  Observation: {"compliant": true, "notes": []}

Iteration 3:
  Thought: "Both checks pass. I can produce a final recommendation."
  Final answer: {"decision": "APPROVED", "confidence": 0.95, ...}
```

**Implementation:**

- `ReActAgent` class in `cafeai-agents` implements the loop
- Uses `app.prompt()` internally for reasoning steps
- Uses registered tools for action steps
- `maxIterations` guard prevents infinite loops
- Each iteration is traced via `ObserveBridge`
- `AgentResult` carries the final answer and the full reasoning trace

**Tasks:**
- [ ] `AgentDefinition` class in `cafeai-agents`
- [ ] `ReActAgent` implements the reasoning loop
- [ ] `AgentResult` carries final answer + trace
- [ ] `maxIterations` guard with `AgentMaxIterationsException`
- [ ] Each iteration traced via observability
- [ ] Tools registered on `AgentDefinition` (not on `app` directly)
- [ ] `./gradlew :cafeai-agents:compileJava` passes
- [ ] Agent unit tests: 2-iteration success, max iterations exceeded,
      tool error handled correctly

---

## Phase 2 — `AgentDefinition` API

**Goal:** Clean API for building agent definitions. Fluent, readable, discoverable.

```java
AgentDefinition.react()                          // ReAct loop type
    .tools(new CreditCheckTool())                // tools available to this agent
    .maxIterations(5)                            // loop guard
    .systemPrompt("You are a loan qualifier")    // agent-specific system prompt
    .stopWhen(result -> result.isFinal())        // early exit condition
    .onIteration((i, thought) -> log(thought))  // iteration callback
```

**Tasks:**
- [ ] `AgentDefinition` fluent builder API
- [ ] `AgentType` enum: `REACT` (ROADMAP-17), `PLAN_AND_EXECUTE` (future)
- [ ] `StopCondition` functional interface
- [ ] `IterationCallback` functional interface
- [ ] `AgentDefinition` is serialisable for observability

---

## Phase 3 — `app.agent()` Entry Point

**Goal:** Register agents on the `CafeAI` app and invoke them through
the same fluent chain as prompt, vision, and audio.

```java
// Register
app.agent("qualifier", AgentDefinition.react()
    .tools(new CreditCheckTool(), new ComplianceTool())
    .maxIterations(5));

// Invoke
AgentResult result = app.agent("qualifier")
    .run("Qualify applicant A123 for a $250,000 conventional mortgage")
    .session("applicant-A123")
    .call();

// result.answer()  → the final answer
// result.trace()   → full reasoning trace (thought/action/observation)
// result.iterations() → number of iterations taken
```

**Tasks:**
- [ ] `CafeAI.agent(String name)` interface method
- [ ] `AgentRequest` fluent builder (mirrors `PromptRequest`)
- [ ] `CafeAIApp.executeAgent()` delegates to registered `ReActAgent`
- [ ] Agent execution traced end-to-end via `ObserveBridge`
- [ ] `beforeAgent`/`afterAgent` hooks on `ObserveBridge`
- [ ] Guardrails apply to agent final output (POST_LLM position)

---

## Phase 4 — Multi-Agent Orchestration

**Goal:** Implement `app.orchestrate()` using Java 21 Structured Concurrency.
Multiple agents run concurrently; the orchestrator collects results or fails
cleanly if any agent fails.

```java
// Define the pipeline
app.orchestrate("loan-pipeline",
    "classifier",    // Step 1: classify the loan type
    "qualifier",     // Step 2: run qualification agent
    "compliance");   // Step 3: compliance check agent

// Invoke
OrchestrationResult result = app.orchestrate("loan-pipeline")
    .input(applicantData)
    .session("A123")
    .call();
```

**Structured Concurrency model:**

```java
// Each agent runs as a subtask in a ShutdownOnFailure scope
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var classifyTask   = scope.fork(() -> classifyAgent.run(input));
    var qualifyTask    = scope.fork(() -> qualifyAgent.run(input));
    var complianceTask = scope.fork(() -> complianceAgent.run(input));

    scope.join().throwIfFailed();

    return OrchestrationResult.of(
        classifyTask.get(),
        qualifyTask.get(),
        complianceTask.get());
}
```

`ShutdownOnFailure` means: if any agent fails, all are cancelled immediately.
The orchestrator either succeeds completely or fails with the first error.
No dangling subtasks, no partial results passed forward.

**Tasks:**
- [ ] `OrchestrationDefinition` — ordered list of agent names
- [ ] `CafeAI.orchestrate(String name)` interface method
- [ ] `CafeAIApp.executeOrchestration()` uses `StructuredTaskScope`
- [ ] `OrchestrationResult` carries all agent results
- [ ] Failure semantics: `ShutdownOnFailure` — all or nothing
- [ ] Orchestration traced end-to-end via `ObserveBridge`
- [ ] `./gradlew :cafeai-agents:test` — orchestration tests pass

---

## Phase 5 — `app.orchestrate()` Entry Point

```java
// Register
app.orchestrate("loan-pipeline", "classifier", "qualifier", "compliance");

// Invoke
OrchestrationResult result = app.orchestrate("loan-pipeline")
    .input("Qualify Alex Johnson, 45, income $120k, requesting $350k mortgage")
    .call();

result.get("classifier").answer()   // → "CONVENTIONAL"
result.get("qualifier").answer()    // → "APPROVED"
result.get("compliance").answer()   // → "COMPLIANT"
```

**Tasks:**
- [ ] `CafeAI.orchestrate(String name)` registers an orchestration
- [ ] `CafeAI.orchestrate(String name)` (no args) returns an `OrchestrationRequest`
- [ ] `OrchestrationRequest.input(String)`, `.session(String)`, `.call()`
- [ ] Unknown orchestration name throws with helpful message

---

## Phase 6 — nova-tutor Agent Integration

**Goal:** Update `nova-tutor` Capstone 5 to use `app.agent()` for the tutor
reasoning loop, replacing the manual loop in `TutorAgent.java` with a proper
registered agent.

```java
// Before (manual loop in TutorAgent)
while (!session.isComplete()) {
    TutorResponse step = app.prompt(buildPrompt(session))
        .provider("tutor")
        .returning(TutorResponse.class)
        .call(TutorResponse.class);
    // ...
}

// After (registered agent)
app.agent("tutor", AgentDefinition.react()
    .tools(new WhiteboardTool(tldrawBridge),
           new LessonProgressTool(session))
    .maxIterations(20)
    .provider("tutor")
    .systemPrompt(TUTOR_SYSTEM_PROMPT));

AgentResult step = app.agent("tutor")
    .run(studentQuestion)
    .session(sessionId)
    .call();
```

**Tasks:**
- [ ] `nova-tutor` `TutorAgent.java` replaced with `app.agent()` registration
- [ ] `WhiteboardTool` as `@CafeAITool` methods
- [ ] `LessonProgressTool` as `@CafeAITool` methods
- [ ] End-to-end test still passes

---

## Phase 7 — PgVector Implementation

**Goal:** Implement `VectorStore.pgVector()` — a production-grade persistent
vector store backed by PostgreSQL with the `pgvector` extension.

```java
app.vectordb(VectorStore.pgVector(
    PgVectorConfig.builder()
        .host("postgres.internal")
        .port(5432)
        .database("cafeai")
        .username("cafeai")
        .password(System.getenv("PG_PASSWORD"))
        .dimension(1536)           // match your embedding model's dimension
        .distanceFunction("cosine") // cosine, l2, inner_product
        .build()));
```

**Why PgVector:**
- Existing PostgreSQL infrastructure — no new database service
- ACID transactions — vector updates are consistent
- SQL queryable — hybrid queries combining metadata filters with vector search
- Production-proven — used in production AI systems at scale

**Implementation:**
- `PgVectorStore` implements `VectorStore` SPI
- Uses JDBC via HikariCP connection pool
- DDL: `CREATE TABLE cafeai_chunks (id TEXT PRIMARY KEY, source_id TEXT, content TEXT, embedding vector(1536))`
- Upsert via `INSERT ... ON CONFLICT (id) DO UPDATE`
- Search via `ORDER BY embedding <=> $1 LIMIT $2` (cosine distance operator)
- `deleteBySource()` via `DELETE WHERE source_id = $1`

**Tasks:**
- [ ] `PgVectorConfig` builder in `cafeai-rag`
- [ ] `PgVectorStore` implements `VectorStore`
- [ ] HikariCP dependency in `cafeai-rag`
- [ ] DDL migration on first connection (create table if not exists)
- [ ] `VectorStore.pgVector(config)` factory method
- [ ] Integration test with embedded PostgreSQL (Testcontainers)
- [ ] `./gradlew :cafeai-rag:test` — all tests pass

---

## Phase 8 — PgVector Integration Test

**Goal:** End-to-end test of the full RAG pipeline with PgVector as the
vector store backend.

```java
// Uses Testcontainers to spin up PostgreSQL + pgvector
@Testcontainers
class PgVectorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("cafeai_test");

    @Test
    void ragPipeline_storesAndRetrievesCorrectly() {
        var app = CafeAI.create();
        app.ai(OpenAI.gpt4oMini());
        app.vectordb(VectorStore.pgVector(configFrom(postgres)));
        app.embed(EmbeddingModel.local());
        app.rag(Retriever.semantic(3));
        app.ingest(Source.text("The rate limit is 1000 RPM.", "helios/rate-limits"));

        var response = app.prompt("What is the rate limit?").call();
        assertThat(response.text()).contains("1000");
    }
}
```

**Tasks:**
- [ ] Testcontainers dependency in `cafeai-rag` test scope
- [ ] `PgVectorIntegrationTest` with container lifecycle management
- [ ] Test: upsert, retrieve, delete by source
- [ ] `./gradlew :cafeai-rag:test` passes including integration test

---

## Phase 9 — Real OpenTelemetry Spans

**Goal:** `ObserveStrategy.otel()` currently exists but produces partial or
stub spans. Implement real OTel spans with the emerging semantic conventions
for generative AI systems.

**Current state:** `ObserveBridgeImpl` has `otel` strategy stubs. The
`cafeai-observability` module has the OTel dependency. Spans may not be
emitted or may be missing required attributes.

**Target:** Full OTel instrumentation following
[OpenTelemetry Semantic Conventions for Generative AI](https://opentelemetry.io/docs/specs/semconv/gen-ai/):

```
Span: gen_ai.client.operation
  gen_ai.system:              "openai"
  gen_ai.operation.name:      "chat"
  gen_ai.request.model:       "gpt-4o"
  gen_ai.response.model:      "gpt-4o-2024-08-06"
  gen_ai.usage.input_tokens:  847
  gen_ai.usage.output_tokens: 23
  gen_ai.request.max_tokens:  4096

Nested span: gen_ai.tool.call (for each tool invocation)
  tool.name:   "lookupVendorByName"
  tool.trust:  "INTERNAL"
  tool.result: "success"
```

**Tasks:**
- [ ] Audit current `ObserveBridgeImpl` OTel implementation
- [ ] Implement full span lifecycle: `span.start()` in `before*`, `span.end()` in `after*`
- [ ] Add all semantic convention attributes for each call type
- [ ] Tool call spans nested under parent LLM call span
- [ ] Vision and audio calls use correct `gen_ai.operation.name`
- [ ] `ObserveStrategy.otel()` tested against a local OTel collector (Testcontainers)
- [ ] `./gradlew :cafeai-observability:test` passes

---

## Phase 10 — OTel Semantic Conventions for AI

**Goal:** Ensure CafeAI's OTel spans follow the published GenAI semantic
conventions so they integrate correctly with Jaeger, Grafana, Honeycomb,
and Datadog without custom mapping.

**Tasks:**
- [ ] `Attributes.java` constants updated to match semantic convention attribute names
- [ ] Span names follow `gen_ai.client.operation` convention
- [ ] RAG retrieval spans: `gen_ai.retrieval` with `db.system=vector_db`
- [ ] Agent spans: `gen_ai.agent.invoke` with iteration count
- [ ] `CHANGELOG.md` documents the OTel attribute changes (breaking change for custom dashboards)

---

## Phase 11 — Hybrid Retrieval

**Goal:** Implement `Retriever.hybrid()` — combined dense (semantic) and
sparse (BM25 keyword) retrieval with configurable weighting.

```java
app.rag(Retriever.hybrid(5)           // top 5 results
    .denseWeight(0.7)                  // 70% semantic
    .sparseWeight(0.3));               // 30% keyword (BM25)
```

**Why hybrid:**
Dense retrieval finds semantically similar chunks even without exact keyword
matches. Sparse retrieval finds chunks with exact term matches even when
semantic similarity is low. Hybrid combines both — it is better than either
alone for domains with specialised vocabulary, product codes, or identifiers
(policy numbers, invoice numbers, PO numbers).

The `acme-claims` capstone would benefit significantly from hybrid retrieval
for policy number lookups.

**Implementation:**
- BM25 index built alongside vector index at ingestion time
- Query produces two scored lists: dense scores and sparse scores
- Reciprocal Rank Fusion (RRF) combines the lists
- Top-K results from the fused list

**Tasks:**
- [ ] `BM25Index` class in `cafeai-rag` (in-memory for now)
- [ ] `HybridRetriever` implements `Retriever`
- [ ] `Retriever.hybrid(k)` factory method
- [ ] `.denseWeight()` and `.sparseWeight()` configuration
- [ ] Hybrid retrieval integration test vs semantic-only baseline
- [ ] `./gradlew :cafeai-rag:test` — all tests pass

---

## Phase 12 — 0.2.0 Release to Maven Central

**Goal:** Publish `cafeai` 0.2.0 to Maven Central. This is the first
externally consumable release — tagged, versioned, with stable API
guarantees and a migration guide from local Maven.

**What "stable API" means:**
- Methods in `CafeAI` interface will not be removed in 0.x releases
- New methods may be added (backward compatible)
- Breaking changes require a major version bump
- All deprecated methods carry a `@since` and `@deprecated` Javadoc tag

**Pre-release checklist:**
- [ ] All ROADMAP-17 phases complete
- [ ] `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
- [ ] `./gradlew javadoc` — zero warnings
- [ ] API surface reviewed for consistency (method names, parameter order, return types)
- [ ] `CHANGELOG.md` written covering changes from 0.1.0-SNAPSHOT
- [ ] `MIGRATION.md` written: local Maven → Maven Central
- [ ] Module `build.gradle` files updated: version `0.2.0`, `pom.xml` metadata complete
- [ ] Sonatype OSSRH account configured
- [ ] GPG signing key configured
- [ ] `./gradlew publishToMavenCentral` succeeds
- [ ] Artifacts visible at `search.maven.org`

**Post-release:**
- [ ] GitHub release tagged `v0.2.0` with release notes
- [ ] Blog post: "CafeAI 0.2.0 is on Maven Central"
- [ ] capstone `build.gradle` files updated from `mavenLocal()` to `mavenCentral()`

---

## What this roadmap does NOT cover

- **Image generation** (`app.image()` via DALL-E / Stable Diffusion) — separate
  modality, separate roadmap when genuine demand exists
- **Real-time audio** (live phone call transcription) — requires WebSocket audio
  streaming and a fundamentally different pipeline model
- **Multi-modal RAG** (embedding images and audio into the vector store) —
  requires multi-modal embedding models
- **Fine-tuning integration** — outside CafeAI's scope; use the provider's
  fine-tuning API directly and register the fine-tuned model as a custom provider
- **Kubernetes deployment guide** — operational documentation, not framework work
- **CafeAI Cloud** (hosted offering) — not in scope for this project
