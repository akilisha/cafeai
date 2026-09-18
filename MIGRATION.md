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

**5. Declarations that nothing ever read are removed.** All of these could be
referenced without doing anything, so removing them changes behaviour only if you
were relying on a no-op:

- `Setting.CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`, `ETAG`, `JSON_ESCAPE_HTML`,
  `QUERY_PARSER`, `VIEW_CACHE` — settable, never honoured. Delete the
  `app.set(...)` calls.
- `AiProvider.ProviderType.AZURE_OPENAI` and `GOOGLE_VERTEX`, and
  `Connection.ServiceType.MCP` and `EMBEDDING` — no code produced or consumed them.
- `TextGuardRail` — no implementer, no caller.
- `PromptRequest`/`VisionRequest`/`AudioRequest.returningType()`.

**6. `.returning(X.class)` now does something.** It previously stored the type and
did nothing, so structured output worked only through `.call(X.class)`. It now
appends the schema instruction to the prompt. `.call(X.class)` behaves exactly as
before, and you can drop the redundant `.returning(X.class)` in front of it. If
you call `.returning(X.class).call()` (no argument) you now get JSON-shaped text
where you previously got unconstrained text.

**7. The `cafeai-streaming` module is gone.** It was an empty artifact: every
published version (0.1.0 – 0.3.2) is a jar holding only a manifest, and the module
never had source files. The SSE / WebSocket streaming its docs advertised has
always lived in `cafeai-core` (`res.stream(...)`, `PromptRequest.stream(...)`,
`WsSession.streamTokens(...)`). Delete the dependency; nothing is lost.

```groovy
// Before
implementation 'com.akilisha.oss:cafeai-streaming:0.3.2'   // an empty jar

// After — nothing to add; streaming is in cafeai-core
```

**8. `Connect.fromEnv()` no longer reads `CAFEAI_MCP_SERVERS`.** It documented the
variable, parsed it, then did nothing with the URLs. `McpEndpoint` was never built,
so there is nothing to connect to; remove the variable from your environment.

**9. `MemoryStrategy.chronicle()` is removed, and eight unused libraries are no
longer on your runtime classpath.** `chronicle()` always threw
`UnsupportedOperationException`, so no working code calls it. Separately,
`cafeai-guardrails`, `cafeai-security`, `cafeai-observability` and `cafeai-memory`
stopped shipping libraries none of their code used: `opennlp-tools`,
`chronicle-map`, `helidon-security` (+ JWT provider), `helidon-tracing` (+ its
OpenTelemetry provider), `helidon-metrics`, and a duplicate `langchain4j-core`.
They were `implementation` dependencies, so they were never on your *compile*
classpath; only if your own code reached for one at runtime through CafeAI's
transitive dependencies do you now need to declare it yourself.

**10. Signed cookies and `req.range()` are removed — check any use of `signed(true)`.**
`CookieOptions.signed(boolean)`, `req.signedCookies()`, `req.signedCookie(String)` and
`req.range(long)` are gone. **`signed(true)` never did anything:** `res.cookie(...)` sent
a plain, unsigned cookie, and the request side always returned nothing. If you set it
expecting tamper-protection, you never had it — sign and verify the value yourself (an
HMAC over the value with a secret you manage), or use a session mechanism. `req.range()`
always returned `null`.

```java
// Before — compiled, but the cookie was NOT signed
res.cookie("session", id, CookieOptions.builder().signed(true).build());

// After — remove signed(true); protect the value yourself if you need integrity
res.cookie("session", signedValue, CookieOptions.builder().httpOnly(true).secure(true).build());
```

**11. Behaviour fixes you may notice** (each previously returned a fixed value):
`req.cookies()` / `req.cookie()` now return the client's cookies; `req.accepts*()` now
honour `q`-values and the actual header; `req.fresh()` / `stale()` now evaluate the
conditional headers; `res.format()` now picks by `Accept` and returns 406 when nothing
matches, where it used to always run the first handler; `res.render()` now works instead
of throwing; and `res.request()`, `req.response()`, `res.app()` now return the live
objects instead of `null`. If you had written around any of these — for example
reading cookies from the raw `Cookie` header, or catching the `UnsupportedOperationException`
from `res.render()` — you can delete the workaround.

**11a. Guardrails now actually run on `app.prompt()` — expect blocks you did not see before.**
If you registered guardrails (`app.guard(GuardRail.pii())`, `.jailbreak()`, …) and called
`app.prompt(...)`, those guardrails were **not applied to that call** (only `vision`/`audio`
enforced them). They are now, so a request that was silently let through can be blocked, and a
response that was silently let through can be replaced with `[Response blocked by guardrail: …]`.
Blocked input throws `GuardRailViolationException` (a `RuntimeException`); in an HTTP route with no
error handler for it, CafeAI answers `400` naming the guardrail — not its reason. Catch it with
`app.onError(...)` to shape the reply. Audit anything that relied on the old, unguarded behaviour,
and review your guardrail `Action`s: `WARN` and `LOG` now proceed on `vision` and `audio` too,
where they previously always blocked.

**12. `GuardRail.bias()` and `GuardRail.hallucination()` are removed — they never
guarded anything.** Both returned a pass-through guardrail, so a request was never
blocked or flagged by them. Remove the `app.guard(...)` calls; nothing about your
app's behaviour changes, but do not assume bias or hallucination is being checked,
because it never was. For hallucination *scoring* (not blocking) use
`app.eval(EvalHarness.defaults())`. If you implement `GuardRailProvider` yourself,
delete the two `@Override` methods.

**13. `AiSecurity.semanticCachePoisoningDetector()`, and `fromCache()` on `VisionResponse` and
`AudioResponse`, are removed — and a real semantic cache replaces them.** They were built for a
cache that did not exist: `fromCache()` always returned `false`. `PromptResponse.fromCache()` and
the `cafeai.cache_hit` span attribute **come back, and are now true when they say so**, because
`app.cache(SemanticCache...)` is real (see `docs/adr/ADR-013-semantic-cache-and-poisoning-defences.md`).
Vision and audio are never cached, so their `fromCache()` stays gone. If you registered
`semanticCachePoisoningDetector()`, remove it: besides guarding nothing, it rejected
short prompts containing several imperative words with a 400. `SecurityEvent` is a
sealed interface whose `CachePoisoningAttempt` subtype is removed, so delete that case
from any exhaustive `switch (event)`.

**14. `GuardRail.pii()` / `jailbreak()` / `promptInjection()` / `toxicity()` / `regulatory()` /
`topicBoundary()` now throw `GuardRailModuleNotFoundException` without `cafeai-guardrails` — they
no longer return a pass-through guardrail.** If your app called them without the module, it was
running unprotected and logging one warning; it now fails at startup, naming the dependency:
`implementation 'com.akilisha.oss:cafeai-guardrails'`. Nothing else changes once the module is
present. If you referenced `GuardRail.RegulatoryGuardRail` or `GuardRail.TopicBoundaryGuardRail` as
classes (to extend them), they are now interfaces — `implements`, not `extends`. `StubGuardRail`
is removed.

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
