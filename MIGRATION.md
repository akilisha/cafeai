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

**1. `EmbeddingModel` is renamed `EmbeddingProvider`.**

```java
// Before
app.embed(EmbeddingModel.local());

// After
app.embed(EmbeddingProvider.local());
```

**2. RAG and Connect types moved into `io.cafeai.core`, and are typed.** `VectorStore`,
`EmbeddingProvider`, `Retriever`, `Source` and `RagDocument` moved from `io.cafeai.rag` to
`io.cafeai.core.rag`; `Connection`, `HealthStatus` and `Fallback` moved from `io.cafeai.connect` to
`io.cafeai.core.connect`. Update imports. `app.vectordb()`, `.embed()`, `.ingest()`, `.rag()` and
`.connect()` take real types, and `PromptResponse`/`AudioResponse`/`VisionResponse.ragDocuments()`
return `List<RagDocument>`. `cafeai-rag` and `cafeai-connect` still supply everything that needs real
dependencies (Chroma, pgvector, Tika, Redis, Ollama); a fully custom `Connection` needs no
`cafeai-connect` on the classpath. See `docs/adr/ADR-011-rag-provider-abstraction.md` and
`docs/adr/ADR-012-application-config.md`.

**3. `EmbeddingProvider.openAi()` takes a model id.**

```java
// Before
app.embed(EmbeddingProvider.openAi());

// After
app.embed(EmbeddingProvider.openAi("text-embedding-3-large"));
// or set CAFEAI_EMBEDDING_MODEL and keep calling the zero-arg overload
```

**4. `CafeAIRegistry` and `CafeAIModule.register(...)` are removed.** Capabilities are wired through
the provider SPIs (`GuardRailProvider`, `MemoryStrategyProvider`, `RagProvider`). This affects you
only if you wrote your own `cafeai-*`-style module: `CafeAIModule` is `name()` and `version()`, so
delete your `register` method.

```java
// Before
public class PineconeModule implements CafeAIModule {
    @Override public String name()    { return "cafeai-pinecone"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
    @Override public void register(CafeAIRegistry registry) {
        registry.registerVectorStore("pinecone", PineconeVectorStore::new);
    }
}

// After — the module is still discovered and logged at startup
public class PineconeModule implements CafeAIModule {
    @Override public String name()    { return "cafeai-pinecone"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
}
```

See `docs/adr/ADR-006-di-cdi-service-loaders.md`.

**5. Unused declarations are removed.** Delete any reference to:

- `Setting.CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`, `ETAG`, `JSON_ESCAPE_HTML`, `QUERY_PARSER`,
  `VIEW_CACHE` (and the `app.set(...)` calls).
- `AiProvider.ProviderType.AZURE_OPENAI` and `GOOGLE_VERTEX`, and `Connection.ServiceType.MCP` and
  `EMBEDDING`.
- `TextGuardRail`.
- `PromptRequest`/`VisionRequest`/`AudioRequest.returningType()`.

**6. `.returning(X.class)` appends the schema instruction to the prompt.** `.call(X.class)` behaves as
before, and you can drop a redundant `.returning(X.class)` in front of it. `.returning(X.class).call()`
(no argument) now returns JSON-shaped text.

**7. The `cafeai-streaming` module is removed.** SSE and WebSocket streaming are in `cafeai-core`
(`res.stream(...)`, `PromptRequest.stream(...)`, `WsSession.streamTokens(...)`). Delete the dependency.

```groovy
// Before
implementation 'com.akilisha.oss:cafeai-streaming:0.3.2'

// After — nothing to add; streaming is in cafeai-core
```

**8. `Connect.fromEnv()` does not read `CAFEAI_MCP_SERVERS`.** Remove the variable from your
environment.

**9. `MemoryStrategy.chronicle()` is removed, and eight libraries are off your runtime classpath.**
`cafeai-guardrails`, `cafeai-security`, `cafeai-observability` and `cafeai-memory` no longer ship
`opennlp-tools`, `chronicle-map`, `helidon-security` (+ JWT provider), `helidon-tracing` (+ its
OpenTelemetry provider), `helidon-metrics` and a duplicate `langchain4j-core`. They were
`implementation` dependencies, so never on your compile classpath; declare one yourself only if your
code reached for it at runtime through CafeAI.

**10. Signed cookies and `req.range()` are removed.** `CookieOptions.signed(boolean)`,
`req.signedCookies()`, `req.signedCookie(String)` and `req.range(long)` are gone. `signed(true)` did
not sign the cookie: `res.cookie(...)` sent it unsigned. If you set it for tamper-protection, sign and
verify the value yourself (an HMAC over the value with a secret you manage) or use a session mechanism.

```java
// Before — the cookie was sent unsigned
res.cookie("session", id, CookieOptions.builder().signed(true).build());

// After — protect the value yourself if you need integrity
res.cookie("session", signedValue, CookieOptions.builder().httpOnly(true).secure(true).build());
```

**11. Request and response helpers are implemented.** `req.cookies()` / `req.cookie()` return the
client's cookies; `req.accepts*()` honour `q`-values; `req.fresh()` / `stale()` evaluate the
conditional headers; `res.format()` picks by `Accept` and returns 406 when nothing matches;
`res.render()` renders through the registered view engine; `res.request()`, `req.response()` and
`res.app()` return the live objects. If you worked around any of these — reading the raw `Cookie`
header, catching an exception from `res.render()` — you can delete the workaround.

**12. Guardrails are enforced by the engine on every call.** `app.guard(...)` applies the guardrail
to `app.prompt()` (plain and streamed), `.vision()` and `.audio()`, and to `app.agent(...)`. A request
a guardrail blocks throws `GuardRailViolationException` (a `RuntimeException`); in an HTTP route with
no error handler for it, CafeAI answers `400` naming the guardrail — not its reason. A response a
guardrail blocks is replaced with `[Response blocked by guardrail: …]`. Catch the exception with
`app.onError(...)` to shape the reply. `GuardRail.Action` is honoured: `WARN` and `LOG` record and
continue. `promptInjection()`, `regulatory()` and `topicBoundary()` are enforced like the rest, and
`regulatory()` declares `Position.PRE_LLM` (it screens input). Before upgrading production, run your
real prompts against your guardrails and review their thresholds and `Action`s for false positives.

**13. `GuardRail.pii()` / `jailbreak()` / `promptInjection()` / `toxicity()` / `regulatory()` /
`topicBoundary()` / `secrets()` require `cafeai-guardrails`.** Without it they throw
`GuardRailModuleNotFoundException` at startup, naming the dependency:
`implementation 'com.akilisha.oss:cafeai-guardrails'`. `GuardRail.RegulatoryGuardRail` and
`GuardRail.TopicBoundaryGuardRail` are interfaces (`implements`, not `extends`); `StubGuardRail` is
removed. If you implement `GuardRailProvider` yourself, add `secrets()` and drop `bias()` and
`hallucination()`.

**14. `GuardRail.bias()` and `GuardRail.hallucination()` are removed.** Delete the `app.guard(...)`
calls; they had no effect.

**15. The semantic-cache surface changed.** `AiSecurity.semanticCachePoisoningDetector()` and
`SecurityEvent.CachePoisoningAttempt` are removed, as is `fromCache()` on `VisionResponse` and
`AudioResponse` (vision and audio are never cached). `PromptResponse.fromCache()` and the
`cafeai.cache_hit` span attribute report whether `app.cache(SemanticCache...)` answered — see
`docs/adr/ADR-013-semantic-cache-and-poisoning-defences.md`. `SecurityEvent` is a sealed interface:
delete any `CachePoisoningAttempt` case from an exhaustive `switch (event)`.

**16. `PiiGuardRail.scrubbing()` is removed.** A guardrail cannot rewrite text in flight; use
`PiiGuardRail.scrub(text)` on text you log or forward. Tuning methods (`threshold`, `action`) are on
the concrete classes — `new JailbreakGuardRail().threshold(0.9)`,
`new ToxicityGuardRail().action(Action.WARN)` — not on what `GuardRail.xxx()` returns.

**17. Error bodies are terser.** The default `500` is `{"error": "Internal Server Error"}` (no
`"message"`), and a guardrail `400` on the HTTP path has no `"reason"`. If a client parsed either,
register `app.onError(...)` and say what you want on the wire.

**18. `AiSecurity.ragDataLeakagePrevention()` and `SecurityEvent.DataLeakageAttempt` are removed.**
Delete the line that registers it. CafeAI does not provide per-user document access control; to keep
one user's documents from another, enforce it where documents are stored or retrieved (a separate
index per tenant, or filtering on an owner id at query time). `SecurityEvent` has one permit,
`InjectionAttempt`, which no longer has `source()`; drop the `DataLeakageAttempt` case from any
exhaustive `switch`. `AiSecurity.promptInjectionDetector()` answers `400` with `{error, eventId}`
and is HTTP-only — pair it with `app.guard(GuardRail.promptInjection())`, which the engine enforces
on every call.

**19. `topicBoundary()` matches phrases.** A denied topic blocks only when its words appear together
and in order. An allowed multi-word topic requires all of its words, so `allow("customer service")`
is no longer satisfied by "customer" alone; list the words separately
(`allow("customer", "service")`) to keep that leniency. Single-word topics are unchanged.

**20. `app.eval(...)`, `EvalHarness`, and three `Attributes` constants are removed.** `app.eval(harness)`
stored its argument and nothing used it, so no scores were ever computed, and no `Attributes.EVAL_SCORES`
value or `cafeai.eval.*` span attribute was ever produced; delete the `app.eval(...)` call. The
constants `Attributes.EVAL_SCORES`, `Attributes.RAG_DOCUMENTS` and `Attributes.RAG_CONTEXT` are removed
for the same reason: nothing set them, so a handler that read them got `null`. To cite the documents
behind an answer, use `PromptResponse.ragDocuments()`.

**21. `ResponseFormatter.markdown()` and `ViewEngineProvider.extensions()` are removed.** `markdown()` loaded an
engine from a `cafeai-views-markdown` module that does not exist, so it could only throw. `extensions()` was
never called by anything: delete the override from any `ViewEngineProvider` you wrote. Separately,
`res.render(name)` now refuses a view that resolves outside `Setting.VIEWS` (`../x.html`, an absolute path)
with a `RenderException`; keep view files under the views directory.

**22. A session's history is windowed, and `ConversationContext` no longer trims.** Calls that use
`.session(...)` now send the newest 20 messages instead of the whole history. If your prompts depend
on older turns, choose a policy that keeps them: `app.history(HistoryPolicy.all())` restores the old
behaviour, `HistoryPolicy.summarise()` keeps their substance in fewer tokens, and
`cafeai.memory.window=0` does the same from configuration. If you called
`new ConversationContext(id, maxTokens)`, `maxTokens()` or used `ConversationContext.DEFAULT_MAX_TOKENS`,
remove those; they trimmed nothing in the engine.

### Not breaking, but new

- **`app.history(HistoryPolicy...)`** — `lastMessages`, `tokenBudget`, `summarise` and `all`; see
  `CHANGELOG.md`. Keys: `cafeai.memory.window`, `.budget`, `.summary.after`, `.summary.keep`, `.summary.words`.
- **More of the framework is configurable.** Timeouts, retry counts, cache and chunk sizes, guardrail
  thresholds and the sentinel's caps are settings with today's values as defaults; nothing changes
  unless you set one. The full list is DEVELOPER_GUIDE.md §17.6.
- **`cafeai-config`** — an optional module for application configuration. A `ConfigKey` declares a
  tunable value where it is used; `AppConfig.load().get(key)` resolves it from a system property, an
  environment variable, an external file, or `application.yaml`/`.properties` with profile overlays.
  Without the module every key resolves to its coded default. Keys: `cafeai.chat.timeout`,
  `cafeai.agent.memory.window`, `cafeai.sentinel.webhook.timeout` / `.max_attempts`, and the rest of
  the list in DEVELOPER_GUIDE.md §17.6. See
  `docs/adr/ADR-012-application-config.md` and DEVELOPER_GUIDE.md §17.
- **Semantic cache** (`app.cache(...)`), **`GuardRail.moderation(model)`**,
  **`GuardRail.promptLeak(prompt)`**, **`GuardRail.secrets()`**, the **NVIDIA provider**,
  **`withTemperature` / `withMaxTokens` / `withTimeout`** on every provider, and
  **`.onThinking(...)`** — see `CHANGELOG.md`.
- `CafeAIModule.versionOf(Class)` reads a module's version from its JAR manifest.

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
