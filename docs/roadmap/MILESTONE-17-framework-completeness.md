# MILESTONE-17 — Framework Completeness

**Current Status:** 🔴 Not Started

> **Superseded (agent phases).** Phases 1–6 below describe a bespoke ReAct loop,
> `AgentDefinition` builder, and `app.orchestrate()` primitive. That design was
> abandoned — CafeAI binds LangChain4j `AiServices` instead of reimplementing the
> reasoning loop. The current plan is **ROADMAP-12 / MILESTONE-12**. Phases 7–12
> (PgVector, OTel, hybrid retrieval, 0.2.0 release) still stand.

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | ~~ReAct agent loop~~ | ⚫ Superseded by ROADMAP-12 |
| 2 | ~~`AgentDefinition` API~~ | ⚫ Superseded by ROADMAP-12 |
| 3 | ~~`app.agent()` entry point~~ (ReAct flavour) | ⚫ Superseded by ROADMAP-12 |
| 4 | ~~Multi-agent orchestration (Structured Concurrency)~~ | ⚫ Superseded by ROADMAP-12 |
| 5 | ~~`app.orchestrate()` entry point~~ | ⚫ Superseded by ROADMAP-12 |
| 6 | ~~nova-tutor agent integration~~ | ⚫ Superseded by ROADMAP-12 |
| 7 | PgVector implementation | 🔴 |
| 8 | PgVector integration test | 🔴 |
| 9 | Real OpenTelemetry spans | 🔴 |
| 10 | OTel semantic conventions for AI | 🔴 |
| 11 | Hybrid retrieval (BM25 + dense) | 🔴 |
| 12 | 0.2.0 release to Maven Central | 🔴 |

---

## Phases 1–6 — Agents & Orchestration &nbsp;⚫ Superseded by ROADMAP-12

The original plan here was a CafeAI-owned **ReAct loop** (`ReActAgent`,
`AgentResult.trace()`, `maxIterations`), an **`AgentDefinition.react()`** builder,
and an **`app.orchestrate()`** primitive with `StructuredTaskScope` fan-out.

That was abandoned. CafeAI does not reimplement the reasoning loop, tool dispatch,
or multi-agent orchestration — **LangChain4j `AiServices` owns all of it**. CafeAI
writes only the HTTP binding (typed agent interface → `AiService`, plus session
threading, guardrail pre-screening, an observability context). Multi-agent
workflows are supervisor-as-tool or a middleware chain, not a bespoke primitive.

Current plan: **[ROADMAP-12](ROADMAP-12-agents.md)** / **[MILESTONE-12](MILESTONE-12-agents.md)**.

*(nova-tutor's manual agent loop, formerly Phase 6, migrates to `app.agent()` once
ROADMAP-12 Phase 5 lands.)*

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
- [ ] Artifacts visible at `search.maven.org/artifact/com.akilisha.oss`
- [ ] GitHub release `v0.2.0` tagged with release notes
- [ ] capstone `build.gradle` files updated from `mavenLocal()` to `mavenCentral()`

### Maven Coordinates
```groovy
implementation 'com.akilisha.oss:cafeai-core:0.2.0'
implementation 'com.akilisha.oss:cafeai-memory:0.2.0'
implementation 'com.akilisha.oss:cafeai-rag:0.2.0'
implementation 'com.akilisha.oss:cafeai-guardrails:0.2.0'
implementation 'com.akilisha.oss:cafeai-observability:0.2.0'
implementation 'com.akilisha.oss:cafeai-security:0.2.0'
implementation 'com.akilisha.oss:cafeai-streaming:0.2.0'
implementation 'com.akilisha.oss:cafeai-connect:0.2.0'
implementation 'com.akilisha.oss:cafeai-views-mustache:0.2.0'
// cafeai-agents — added when ROADMAP-12 lands
```

### Notes
<!-- Add implementation notes here -->

---

## Completion Definition

MILESTONE-17 is **complete** when:

1. Phases 7–12 show ✅ Complete (phases 1–6 → tracked under MILESTONE-12)
2. Test count grows by the PgVector + OTel + hybrid-retrieval suites
3. `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
4. `./gradlew javadoc` — zero warnings
5. `cafeai` 0.2.0 visible on Maven Central

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

// Registered agents (see ROADMAP-12 — LangChain4j AiServices binding)
app.agent("tutor", TutorAgent.class)
    .tool(new WhiteboardTool()).tool(new LessonProgressTool())
    .model("tutor");

app.agent("assessor", AssessorAgent.class)
    .tool(new ComprehensionCheckTool())
    .model("tutor");
// Multi-agent: the tutor agent calls the assessor as a @Tool, or a
// middleware chain sequences them — no orchestrate() primitive.

// HTTP routes
app.filter(CafeAI.json());
app.post("/session", sessionHandler);
app.listen(8080);
```

Every line of that startup block is real, implemented, tested code.
That is what ROADMAP-17 delivers.
