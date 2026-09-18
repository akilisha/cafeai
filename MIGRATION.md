# Migration Guide

## 0.3.2 → 0.4.0

### Coordinates

```groovy
dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.4.0'
    implementation 'com.akilisha.oss:cafeai-config:0.4.0'          // new
    implementation 'com.akilisha.oss:cafeai-agents:0.4.0'
    implementation 'com.akilisha.oss:cafeai-rag:0.4.0'
    implementation 'com.akilisha.oss:cafeai-memory:0.4.0'
    implementation 'com.akilisha.oss:cafeai-guardrails:0.4.0'
    implementation 'com.akilisha.oss:cafeai-observability:0.4.0'
    implementation 'com.akilisha.oss:cafeai-security:0.4.0'
    implementation 'com.akilisha.oss:cafeai-streaming:0.4.0'
    implementation 'com.akilisha.oss:cafeai-connect:0.4.0'
    implementation 'com.akilisha.oss:cafeai-views-mustache:0.4.0'
    implementation 'com.akilisha.oss:cafeai-sentinel:0.4.0'
}
```

### Breaking changes

**1. `EmbeddingModel` is renamed `EmbeddingProvider`** — closes a real name
collision against LangChain4j's own `EmbeddingModel`.

```java
// Before
app.embed(EmbeddingModel.local());

// After
app.embed(EmbeddingProvider.local());
```

**2. RAG and Connect types moved into `io.cafeai.core`, and are now really
typed.** `VectorStore`, `EmbeddingProvider`, `Retriever`, `Source`,
`RagDocument` moved from `io.cafeai.rag` to `io.cafeai.core.rag`; `Connection`,
`HealthStatus`, `Fallback` moved from `io.cafeai.connect` to
`io.cafeai.core.connect`. Update imports. `app.vectordb()`, `.embed()`,
`.ingest()`, `.rag()`, and `.connect()` are now properly typed (were `Object`,
checked only at runtime) — if you were passing something that only
duck-typed its way past a runtime check, the compiler will now catch it.
`PromptResponse`/`AudioResponse`/`VisionResponse.ragDocuments()` now return
`List<RagDocument>` (was `List<Object>`). `cafeai-rag` and `cafeai-connect`
still supply everything needing real dependencies (Chroma, pgvector, Tika,
Redis, Ollama) — nothing to change there beyond the import paths. A fully
custom `Connection` implementation no longer needs `cafeai-connect` on the
classpath at all. Full rationale: `docs/adr/ADR-011-rag-provider-abstraction.md`,
`docs/adr/ADR-012-application-config.md`.

**3. `EmbeddingProvider.openAi()` has no default model id.** The old zero-arg
factory silently defaulted to the retired `text-embedding-ada-002`.

```java
// Before
app.embed(EmbeddingProvider.openAi());          // silently used a retired model

// After
app.embed(EmbeddingProvider.openAi("text-embedding-3-large"));
// or set CAFEAI_EMBEDDING_MODEL and keep calling the zero-arg overload
```

**4. `CafeAIRegistry` and `CafeAIModule.register(...)` are removed.** The
registry was write-only: modules registered named capability factories into
it, and nothing ever read them back. Capabilities are wired through the
provider SPIs (`GuardRailProvider`, `MemoryStrategyProvider`, `RagProvider`),
not the registry, so nothing that worked before stops working. This only
affects you if you wrote your own `cafeai-*`-style module: `CafeAIModule` is
now just `name()` and `version()`, so delete your `register` method.

```java
// Before
public class PineconeModule implements CafeAIModule {
    @Override public String name()    { return "cafeai-pinecone"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
    @Override public void register(CafeAIRegistry registry) {
        registry.registerVectorStore("pinecone", PineconeVectorStore::new);
    }
}

// After — delete register(); the module is still discovered and logged at startup
public class PineconeModule implements CafeAIModule {
    @Override public String name()    { return "cafeai-pinecone"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
}
```

See `docs/adr/ADR-006-di-cdi-service-loaders.md` (Amendment).

### Not breaking, but new

- **`cafeai-config`** — an optional module for real application configuration.
  A `ConfigKey` declares a tunable value (dotted name, type, default,
  description) right where it's used; `AppConfig.load().get(key)` resolves
  it. Without `cafeai-config` on the classpath, every key just resolves to
  its own coded default — nothing breaks if you don't add it. Add it to
  override values via system property, environment variable, an external
  file, or `application.yaml`/`.properties` with profile overlays, all
  resolved by Helidon Config. Three previously hardcoded, non-overridable
  constants are now `ConfigKey`s worth knowing about:
  `cafeai.chat.timeout` (was a fixed 60s in `LangchainBridge`, every
  provider), `cafeai.agent.memory.window` (was a fixed 20 messages in
  `AgentRegistry`), and `cafeai.sentinel.webhook.timeout` /
  `.max_attempts` (in `cafeai-sentinel`'s `WebhookSink`). See
  `docs/adr/ADR-012-application-config.md` and DEVELOPER_GUIDE.md §17.
- `CafeAIModule.versionOf(Class)` now reads a module's real version from its
  JAR manifest instead of every module previously reporting a hardcoded
  `"0.1.0"` from `version()`.

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
