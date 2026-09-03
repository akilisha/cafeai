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
| 7 | PgVector implementation | 🟢 (`9886803`) |
| 8 | PgVector integration test | 🟢 (`9886803`) — Testcontainers, runs where Docker is present |
| 9 | Real OpenTelemetry spans | 🟢 — spans were already real; this aligned attribute names |
| 10 | OTel semantic conventions for AI | 🟢 — `gen_ai.*` on all spans + a `retrieve` span |
| 11 | Hybrid retrieval (BM25 + dense) | 🟢 (`HybridRetriever` rewritten + tests) |
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

## Phase 7 — PgVector Implementation &nbsp;🟢

### Acceptance Criteria
- [x] `PgVectorConfig` builder in `cafeai-rag`
- [x] `PgVectorStoreAdapter` implements `VectorStore` (wraps LangChain4j `PgVectorEmbeddingStore`)
- [x] `VectorStore.pgVector(config)` factory method
- [x] DDL auto-migration on first connection (`createTable(true)` + `useIndex(true)` → table + ivfflat cosine index)
- [x] `upsert()` → `INSERT ... ON CONFLICT (embedding_id) DO UPDATE` (chunk id → deterministic UUID PK)
- [x] `search()` → cosine (`embedding <=> $1`) via `EmbeddingSearchRequest`
- [x] `deleteBySource()` → `removeAll(metadataKey("sourceId").isEqualTo(...))`
- [x] HikariCP connection pool; `exists()` / `count()` are JDBC on the pool
- [x] `./gradlew :cafeai-rag:compileJava` passes

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

## Phase 8 — PgVector Integration Test &nbsp;🟢

### Acceptance Criteria
- [x] Testcontainers `pgvector/pgvector:pg16` (`@Testcontainers(disabledWithoutDocker = true)`)
- [x] Full RAG pipeline test: ingest → search → delete by source → re-ingest
- [x] Cosine similarity results ordered correctly (asserted `isSortedAccordingTo` desc)
- [x] Connection pool closed after the test (`@AfterAll store.close()`)
- [x] `PgVectorStoreIntegrationTest` in `:cafeai-rag:test` — runs where Docker is present, skipped otherwise

### Notes
Docker was not available in the dev environment where this was written — the test
compiles and self-skips; run `./gradlew :cafeai-rag:test` on a Docker host to
exercise it (part of the pre-release validation).

---

## Phase 9 — Real OpenTelemetry Spans

**Status:** 🟢 — the spans were already real (`GlobalOpenTelemetry` tracer, started
in `before*` / ended in `after*`). This phase aligned the attribute names.

### Acceptance Criteria
- [x] `ObserveStrategy.otel()` produces real spans (already did)
- [x] `gen_ai.system` set best-effort from the model id (openai / anthropic / ollama; omitted when unknown)
- [x] `gen_ai.operation.name` per call type — `chat` (prompt + vision), `transcribe`, `invoke_agent`, `retrieve`
- [x] `gen_ai.response.model` set from the response
- [x] `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` set
- [x] Span lifecycle: started in `before*`, ended in `after*`
- [x] Vision/audio spans carry `cafeai.input.content_bytes` + `cafeai.input.mime_type`
- [ ] Tool-call sub-spans nested under the LLM span — LangChain4j owns the agent loop; not exposed. Deferred.

### Notes
`gen_ai.request.model` is not set at span start (the resolved model isn't known
until the response). `error.type` + span status ERROR on failure.

---

## Phase 10 — OTel Semantic Conventions &nbsp;🟢

### Acceptance Criteria
- [x] Span attribute constants centralised (`ObserveBridgeImpl.Sem`) on semconv names
- [x] RAG retrieval span `retrieve` with `db.system=vector_db`, `cafeai.rag.documents_retrieved`
- [x] Agent span `invoke_agent <name>` with `gen_ai.agent.name` (`gen_ai.agent.iterations` not available — LC4j owns the loop)
- [x] `CHANGELOG.md` documents the attribute rename
- note: `Attributes.java` holds *HTTP request* keys, not telemetry — left as-is

---

## Phase 11 — Hybrid Retrieval &nbsp;🟢

### Acceptance Criteria
- [x] `Retriever.hybrid(k)` factory (returns `HybridRetriever` for chaining)
- [x] `.denseWeight(double)` / `.sparseWeight(double)` configuration (relative, need not sum to 1)
- [x] Sparse scoring: BM25 term-frequency (k1=1.5, b=0.75) computed by re-ranking the
      top `4 × topK` dense candidates — no separate keyword index, works with every `VectorStore`
- [x] Fusion: both score sets min-max normalised over the candidate pool, then weighted-sum
- [x] `HybridRetrieverTest` — 4 cases: semantic-only vs hybrid on an exact-identifier query,
      no-keyword fallback to dense order, negative-weight rejection
- [x] `./gradlew :cafeai-rag:test` passes

### Notes
The previous `HybridRetriever` was a stub with a broken BM25 (counted the first
character of each term) and hardcoded weights — rewritten here. A BM25 index
built at ingestion (with real IDF) and delegating to `PgVectorEmbeddingStore`'s
native `SearchMode.HYBRID` are future optimisations, not needed for the common case.

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
