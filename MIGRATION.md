# Migration Guide

## 0.1.3 → 0.2.0

### Coordinates

No `mavenLocal()` / `publishToMavenLocal` step any more — every module is on
Maven Central under `com.akilisha.oss`:

```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.2.0'
    implementation 'com.akilisha.oss:cafeai-agents:0.2.0'          // new
    implementation 'com.akilisha.oss:cafeai-rag:0.2.0'
    implementation 'com.akilisha.oss:cafeai-memory:0.2.0'
    implementation 'com.akilisha.oss:cafeai-guardrails:0.2.0'
    implementation 'com.akilisha.oss:cafeai-observability:0.2.0'
    implementation 'com.akilisha.oss:cafeai-security:0.2.0'
    implementation 'com.akilisha.oss:cafeai-streaming:0.2.0'
    implementation 'com.akilisha.oss:cafeai-connect:0.2.0'
    implementation 'com.akilisha.oss:cafeai-views-mustache:0.2.0'
}
```

Java 23 toolchain. No `--enable-preview`.

### Breaking changes

**1. `app.tool(...)` is gone. The `cafeai-tools` module is gone.**

Tools are agent-only now. A `@CafeAITool`-annotated class becomes a
`dev.langchain4j.agent.tool.@Tool` class registered on an agent:

```java
// Before
app.tool(new OrderLookup());
var reply = app.prompt(question).call().text();   // LLM could call the tool

// After
app.agent("support", SupportAgent.class)
   .system(SYSTEM_PROMPT)
   .tool(new OrderLookup());                       // OrderLookup uses @Tool now

var agent = app.agent("support", SupportAgent.class, req.header("X-Session-Id"));
var reply = agent.answer(question);
```

`SupportAgent` is a plain interface with LangChain4j annotations
(`@SystemMessage`, `@UserMessage`); `app.agent(...)` returns LangChain4j's own
`AiService` proxy. RAG (`app.rag(...)`) and session memory (`app.memory(...)`)
registered on the app are inherited by the agent; add `cafeai-agents` to the
classpath.

**2. OpenTelemetry span attributes renamed to the GenAI semantic conventions.**

Dashboards and alerts keyed on the old `cafeai.*` names need updating:

| Before | After |
|---|---|
| span name `cafeai.llm.call` / `.vision` / `.audio` | `chat` / `chat` / `transcribe` |
| span name `cafeai.agent.invoke` | `invoke_agent <name>` |
| `cafeai.model` | `gen_ai.response.model` |
| `cafeai.prompt_tokens` | `gen_ai.usage.input_tokens` |
| `cafeai.completion_tokens` | `gen_ai.usage.output_tokens` |
| `cafeai.total_tokens` | `cafeai.usage.total_tokens` (kept, not semconv) |
| `cafeai.error` | `error.type` (+ span status ERROR) |
| `cafeai.session_id` | `cafeai.session.id` |
| `cafeai.rag_docs_retrieved` | `cafeai.rag.documents_retrieved` |
| `cafeai.vision.mime_type` / `.content_bytes` | `cafeai.input.mime_type` / `.content_bytes` |

New: `gen_ai.operation.name` on every span, `gen_ai.system` (best-effort), and a
`retrieve` span for RAG retrieval (`db.system=vector_db`).

### Not breaking, but new

- `VectorStore.pgVector(PgVectorConfig.builder()...build())` — PostgreSQL/pgvector store
- `Retriever.hybrid(k).denseWeight(x).sparseWeight(y)` — the hybrid retriever now
  has a real BM25 and configurable weights (the old one was a stub)
- `AgentConfig.rag(retriever)` — per-agent RAG override
- Capstone apps moved into `capstones/` in the repo (they were a separate repo)
