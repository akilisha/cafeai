# Changelog

All notable changes to CafeAI. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
versions are the Maven Central coordinates under `com.akilisha.oss`.

## [0.4.0] — unreleased

### Breaking changes

- **Guardrails are enforced by the engine on every call.** `app.prompt(...).call()`,
  `.stream()`, `.vision()` and `.audio()` apply every guardrail registered with `app.guard(...)`
  to the text the model sees (input) and to what it returns (output), through one shared path.
  `GuardRail.Action` is honoured: `BLOCK` throws the new `GuardRailViolationException` for input
  and replaces output with a refusal; `WARN` and `LOG` record the violation and continue. With no
  error handler that claims it, the default handler answers `400` with the guardrail's name only.
  `app.agent(...)` applies the same guardrails through LangChain4j's `AiServices`
  (`GuardrailAdapters`: honours `Action`, screens a multimodal message on its text, treats a `null`
  result as a pass, and names the guardrail without its reason).
- **`promptInjection()`, `regulatory()` and `topicBoundary()` run on the engine path**, like
  `pii()`, `jailbreak()` and `toxicity()`. Requests they refuse now get a `400` from `app.prompt()`.
  `regulatory()` screens input only and declares `Position.PRE_LLM`.
- **`GuardRail.pii()`, `.jailbreak()`, `.promptInjection()`, `.toxicity()`, `.regulatory()`,
  `.topicBoundary()` and `.secrets()` throw `GuardRailModuleNotFoundException`** when
  `cafeai-guardrails` is not on the classpath. The message names the Gradle and Maven coordinate to
  add. `GuardRail.RegulatoryGuardRail` and `GuardRail.TopicBoundaryGuardRail` are interfaces.
  `GuardRail.moderation(model)`, `GuardRail.promptLeak(prompt)` and your own guardrails need no module.
- **`topicBoundary()` matches phrases.** A denied topic blocks an input containing its words
  together and in order; an allowed topic needs all of its words (any order). A comma inside one
  argument separates topics.
- **Terser error bodies.** The default `500` is `{"error": "Internal Server Error"}`; a guardrail
  block on the HTTP path is `{error, guardrail}`. Messages and reasons are logged. Register
  `app.onError(...)` to put more on the wire.
- **RAG and Connect types moved into `io.cafeai.core`.** `VectorStore`, `EmbeddingProvider`,
  `Retriever`, `Source` and `RagDocument` moved from `io.cafeai.rag` to `io.cafeai.core.rag`;
  `Connection`, `HealthStatus` and `Fallback` moved from `io.cafeai.connect` to
  `io.cafeai.core.connect`. `app.vectordb()`, `.embed()`, `.ingest()`, `.rag()` and `.connect()` are
  typed, and `ragDocuments()` returns `List<RagDocument>`. `EmbeddingModel` is renamed
  `EmbeddingProvider`. `cafeai-rag` supplies the implementations that need real dependencies (Chroma,
  pgvector, Tika, an ONNX model) through the `io.cafeai.core.spi.RagProvider` SPI; the
  `RagPipeline` and `ConnectBridge` SPIs are gone. See ADR-011.
- **`EmbeddingProvider.openAi()` takes a model id** (`EmbeddingProvider.openAi("text-embedding-3-large")`),
  or reads `CAFEAI_EMBEDDING_MODEL` for the zero-argument overload. Dimensionality is measured from a
  real embedding call.
- **`CafeAIModule` is `name()` and `version()`.** `CafeAIRegistry`, `CafeAIRegistryImpl` and
  `CafeAIModule.register(...)` are removed; capabilities are wired through the provider SPIs
  (`GuardRailProvider`, `MemoryStrategyProvider`, `RagProvider`). External modules delete their
  `register` method. See ADR-006.
- **`.returning(X.class)` appends the type's JSON schema instruction to the prompt**, so
  `.returning(X.class).call()` returns JSON text in that shape. `.call(X.class)` is unchanged.
  Applies to `prompt`, `vision` and `audio`.

### Removed

- **Modules and dependencies.** The `cafeai-streaming` module (streaming lives in `cafeai-core`).
  Eight unused libraries no longer ship on consumers' runtime classpaths: `opennlp-tools`,
  `chronicle-map`, `helidon-security` and its JWT provider, `helidon-tracing`, its OpenTelemetry
  provider and `helidon-metrics`, and a duplicate `langchain4j-core`; `helidon-config-yaml` now
  lives only in `cafeai-config`. A consumer of the full stack goes from 373 to 293 runtime artifacts.
- **Guardrails.** `GuardRail.bias()`, `GuardRail.hallucination()` (and the same methods on
  `GuardRailProvider`), `PiiGuardRail.scrubbing()` and `StubGuardRail`. For scoring an answer against
  its sources use `EvalHarness`.
- **Security.** `AiSecurity.ragDataLeakagePrevention()`, `AiSecurity.semanticCachePoisoningDetector()`,
  `SecurityEvent.DataLeakageAttempt`, `SecurityEvent.CachePoisoningAttempt` and
  `SecurityEvent.InjectionAttempt.source()`. CafeAI has no per-user document access control.
- **Memory and connectivity.** `MemoryStrategy.chronicle()`; `CAFEAI_MCP_SERVERS` handling in
  `Connect.fromEnv()`; `Connection.ServiceType.MCP` and `EMBEDDING`.
- **HTTP.** `CookieOptions.signed(...)`, `req.signedCookies()`, `req.signedCookie(...)` and
  `req.range(...)`. Signing needs an application secret; sign and verify a cookie value yourself.
- **Declarations.** `TextGuardRail`; the settings `CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`, `ETAG`,
  `JSON_ESCAPE_HTML`, `QUERY_PARSER` and `VIEW_CACHE`; `AiProvider.ProviderType.AZURE_OPENAI` and
  `GOOGLE_VERTEX`; `returningType()` on the request types; `fromCache()` on `VisionResponse` and
  `AudioResponse`; `PodState.hasContainerTrouble()` and `hasWarningEvents()`.

### Added

- **Semantic cache: `app.cache(SemanticCache...)`.** Repeat `app.prompt()` questions are answered
  from a cache matched by meaning, skipping the model call. `response.fromCache()` and the
  `cafeai.cache_hit` span attribute report it. A cache shared between users can let one user's
  request decide what another is told, so the defences are part of the design: only **clean,
  prompt-only** answers are stored (nothing any guardrail flagged, even a `WARN`; nothing with a
  `session()` or with RAG configured; nothing over a size limit); entries are namespaced by model,
  its settings and the system prompt; a hit requires high embedding similarity **and** high word
  overlap **and** similar length, so a question with instructions appended does not match; every hit
  is **re-screened** by the current `POST_LLM` guardrails and evicted if it fails; entries expire and
  the cache is size-bounded. A request a guardrail blocks never reaches the cache, and a cache or
  embedding failure never fails the call. `.noCache()` opts a call out; vision and audio are never
  cached. `SemanticCache` is an interface to back with your own store; `SemanticCache.inMemory(embedder)`
  is per-process and scans linearly. See ADR-013 for the threat model and its limits.
- **`EmbeddingProvider.of(EmbeddingModel)`** — any LangChain4j embedding model as a CafeAI
  embedding provider.
- **`GuardRail.moderation(ModerationModel)`** — a guardrail backed by LangChain4j's own
  `ModerationModel`: a model, not a pattern list. CafeAI adds no wrapper; use any provider's model or
  `OpenAI.moderation("omni-moderation-latest")`. It applies to `prompt`, `vision`, `audio` and
  `app.agent(...)`, and is configured with `.at(Position)`, `.action(Action)`, `.named(...)` and
  `.failOpen()`. It fails closed: if the moderation call fails, the text is blocked (`failOpen()` opts
  out, logged at WARN). LangChain4j's agent hook works too: `configure(b -> b.moderationModel(m))`
  with `@Moderate` throws its `ModerationException`. See `LC4J-TO-CAFEAI.md` §2.11.
- **`GuardRail.promptLeak(systemPrompt)`** — flags a response that reproduces a run of the system
  prompt's words. Needs no module. Catches verbatim and near-verbatim disclosure, not paraphrase,
  translation or encoding.
- **`GuardRail.secrets()`** — API keys, tokens, private keys, JWTs and credentials in URLs, in what
  users send and in what the model says. Reports the kind, never the value;
  `SecretsGuardRail.scrub(text)` redacts.
- **`TextNormalizer`** — folds case, full-width letters, zero-width and other invisible characters,
  accents and Cyrillic/Greek look-alikes before any pattern-based guardrail matches. Public, for your
  own guardrails.
- **RAG documents are screened.** `GuardRail.checkRetrieved(String)` lets a guardrail vet each
  retrieved document before it enters the model's context; `promptInjection()` uses it, and a
  document a `BLOCK` guardrail flags is dropped. `app.prompt()` only; an agent's retrieval belongs to
  LangChain4j.
- **`cafeai-config`** — file-based application configuration. A `ConfigKey` declares a value (dotted
  name, type, default, description) where it is used; `AppConfig.load()` resolves it from system
  properties, environment variables, an external file (`CAFEAI_CONFIG_FILE`, e.g. a Kubernetes
  ConfigMap volume) and `application.properties`/`.yaml` with profile overlays, built on Helidon
  Config. `cafeai-core` resolves only the coded default. Keys: `cafeai.chat.timeout` (chat timeout,
  every provider), `cafeai.agent.memory.window` (agent chat-memory window) and
  `cafeai.sentinel.webhook.timeout` / `.max_attempts`. See ADR-012.
- **NVIDIA provider** — `Nvidia.of("moonshotai/kimi-k3")`, key from `$NVIDIA_API_KEY`, for models on
  NVIDIA's hosted API catalog. OpenAI-compatible, wired through `ChatModelAccess` and
  `StreamingChatModelAccess` like `Gemini`; no new dependency. Uses a 5-minute timeout, since hosted
  reasoning models can take more than 60 seconds to the first token. `.withReasoningEffort("max")` is
  NVIDIA-specific. See `NvidiaVisionExample`.
- **`withTemperature(double)`, `withMaxTokens(int)` and `withTimeout(Duration)`** on every provider,
  with `temperature()` / `maxTokens()` / `timeout()` accessors on `AiProvider`. Each returns an
  immutable copy: `Anthropic.of("claude-sonnet-4-5").withTemperature(0)`. Unset means the model's own
  default. `maxTokens` maps to the vendor's parameter (`max_completion_tokens` for OpenAI,
  `num_predict` for Ollama, `maxOutputTokens` for Gemini). `withTimeout` overrides
  `cafeai.chat.timeout` for one provider. A custom `AiProvider` that does not override them, and
  `ModelRouter`, throw `UnsupportedOperationException`; `Jlama` refuses a timeout, since it runs
  in-process.
- **`.onThinking(Consumer<String>)`** on `PromptRequest` and `VisionRequest` — receives a reasoning
  model's thinking tokens during `.stream(...)`, apart from the answer text, session memory and
  guardrails. `Nvidia` enables it.
- **`CafeAIModule.versionOf(Class)`** reads a module's version from its JAR manifest
  (`Implementation-Version`), used by every module and the OpenTelemetry tracer.
- **HTTP.** `req.cookies()` / `req.cookie(name)` parse the `Cookie` header. `req.accepts()`,
  `acceptsCharsets()`, `acceptsEncodings()` and `acceptsLanguages()` negotiate on `q`-values,
  wildcard specificity and `q=0`. `req.fresh()` / `req.stale()` compare `If-None-Match` to `ETag`
  (weakly) and `If-Modified-Since` to `Last-Modified`, per RFC 9110. `res.format()` negotiates on
  `Accept` and answers 406 when nothing matches. `res.render()` renders through the registered
  view engine (locals layer as `app.locals()` < `res.locals()` < those you pass). `res.request()`,
  `req.response()` and `res.app()` return the live objects. `CookieOptions.expires(...)` writes an
  `Expires` attribute.

### Fixed

- The chat-model cache is keyed on the provider itself (a value-comparing record) rather than
  `name:modelId`, so two `Ollama.at(...)` providers on different base URLs no longer share a client.
- `GuardRail.jailbreak()` matches `DAN` as a word, not as a substring of "Daniel" or "abundant".
- `TopicBoundaryGuardRailImpl` no longer returns application-specific text as its reason.

### Housekeeping

- Deleted `StreamingProbe`, an unreachable `if (false)` branch in `CafeAIApp`, two unused loggers,
  three unused imports and `TokenBudgetTracker.currentWindowTokens()`.
- `docs/adr/`: `ADR-010` and `ADR-008-connectivity-…` were byte-identical; kept `ADR-010`, corrected
  its title, and repointed `ROADMAP-09` at it.

## [0.3.2] — 2026-09

### Added

- **`cafeai-sentinel` is now published to Maven Central** (ROADMAP-18 Phase 7)
  — the AI Kubernetes/OpenShift incident pipeline introduced reactor-only in
  0.3.0 clears its publish gate: all 4 demo scenarios (crashloop, bad-image,
  oom, missing-config) validated end-to-end on a real OpenShift cluster, not
  just minikube, confirming the "runs identically on Kubernetes and
  OpenShift" claim. Ships with `deploy/rbac.yaml` — a least-privilege
  `cluster-sentinel` ServiceAccount (namespaced `Role` for pods/logs/events/
  quota/deployments/replicasets, `ClusterRole` for nodes only) for running
  sentinel outside the cluster it watches via `ClusterConnection.token(...)`,
  instead of a personal user's OAuth token.
- **Gemini provider** — `io.cafeai.core.ai.Gemini`, wired entirely through
  `ChatModelAccess` alongside `Anthropic`/`OpenAI`/`Ollama`/`Jlama`.

## [0.3.1] — 2026-09

### Added
- `.github/workflows/release-capstones.yml` — the repo's first CI. On a
  `v*` tag push, builds every `capstones/*` reference app's distribution
  (`./gradlew distZip`) and attaches each one as a zip asset on that
  tag's GitHub Release — a download-and-run alternative to cloning the
  repo, for the capstones only (they are not, and will not be, published
  to Maven Central).

No library code changed. No published module's content differs from 0.3.0.

## [0.3.0] — 2026-09

### Changed — BREAKING

- **AI provider factories take a model id; the named model constants are gone.**
  `Anthropic.claude35Sonnet()` / `claude3Opus()` / `claude3Haiku()`,
  `OpenAI.gpt4o()` / `gpt4oMini()` / `o1()` / `o1Mini()`,
  `Ollama.llama3()` / `mistral()` / `phi3()` / `gemma2()` / `llava()`, and
  `Jlama.llama3()` / `tinyLlama()` / `mistral()` / `gemma2()` / `qwen2()` are
  removed. Model ids are provider data — they get retired — and a framework that
  hardcodes `claude-3-5-sonnet-20241022` ships a runtime landmine. Use
  `Anthropic.of("claude-sonnet-4-5")`, `OpenAI.of("gpt-4o")`,
  `Ollama.of("llama3.3")`, `Jlama.of("tjake/…")`; the provider's API is the
  source of truth for what's valid.
  - `Ollama.llava()` → `Ollama.vision("llava")` (or `Ollama.at(url).visionModel(id)`).
  - `OpenAI.tts()` / `OpenAI.tts(voice, format)` / `OpenAI.whisper()` stay — they
    are dedicated non-chat endpoints with fixed models, not a model choice.
- **`AiProvider.supportsVision()` is now a coarse hint, not per-model.**
  `OpenAI.of(...)` and `Anthropic.of(...)` return `true` (modern chat models are
  multimodal; the API rejects the exception). `OpenAI`'s internal `VISION_MODELS`
  / `AUDIO_MODELS` id sets are gone. `Ollama.vision(...)` opts in; `Ollama.of(...)`
  / `Jlama.of(...)` return `false`.

### Added

- **`cafeai-sentinel`** (ROADMAP-18, reactor-only — not published until it clears
  OpenShift validation): an AI Kubernetes/OpenShift incident pipeline —
  `ClusterWatch` (dual fabric8 informer), `ClusterConnection` (ambient / context
  / token / basic-auth), rules-only `TriageRules`, `IncidentTracker` (coalesce by
  workload, best-effort resolve, token-budgeted async investigation with a
  failure cap and reason-family grouping, debounced `UPDATED`), `KubeTools`
  (read-only `@Tool` bundle), `ClusterInvestigator` agent → structured
  `Investigation`, `Redactor` (secrets/PII scrub), and `IncidentSink` /
  `LogSink` / `WebhookSink` / `SsePublisher` / `IncidentJson`. Run end-to-end
  against live minikube. Companion capstone: `capstones/cluster-sentinel`.

## [0.2.1] — 2026-09

### Added
- `WsSession.streamTokens(Flow.Publisher<String>)` — pipe `app.prompt(...).stream()`
  straight to a WebSocket client (one text frame per token, `[DONE]` sentinel on
  completion; custom/`null` sentinel via the two-arg overload).
- `GuardRail.checkInput(String)` — PRE_LLM screening as a first-class method
  alongside `checkOutput`. Agents' input guardrails and `CafeAIApp`'s prompt/
  vision/audio PRE_LLM checks now call it instead of reusing `checkOutput`.

### Removed
- `AgentConfig.mcp(String)` and the `ToolSource` sealed type — a stub that only
  threw. `AgentConfig` tools are a `List<Object>` again. MCP-as-tool-source
  returns with the (unbuilt, demand-driven) `cafeai-connect` `McpEndpoint`
  connector; for a one-off use `.configure(b -> b.toolProvider(...))`.

### Changed
- `AbstractGuardRail`'s protected input hook renamed `checkInput` → `screenInput`
  (frees `checkInput` for the interface method). Concrete guardrails that extend
  `AbstractGuardRail` override `screenInput`.
- **`cafeai-core` POM**: the dependencies that leak through the public API
  (`langchain4j-core` → `ChatModel`, `langchain4j` → `AiServices`/`@Tool`,
  `jackson-annotations` → `@JsonCreator`/`@JsonProperty` on `ConversationContext`)
  are now `api`, so they land on a consumer's **compile** classpath — previously
  `implementation`, so they published as `runtime`-scoped and a project compiling
  against those types had to declare them itself. Helidon, the provider modules,
  and `jackson-databind`/`-jsr310` remain `implementation` (not exposed). The
  `langchain4j-bom` is now imported on `api` so the versionless `api` deps
  resolve for consumers.

## [0.2.0] — 2026-09

The project version is `0.2.0` on `main`; the artifacts publish to Maven Central
when ROADMAP-17 Phase 12 runs. Consumers on 0.1.3: see `MIGRATION.md`.

### Added
- **`cafeai-agents`** — `app.agent(name, Interface.class)` binds a LangChain4j
  `AiService` and gives it an HTTP identity (session threading, guardrail
  screening, an observability context). `AgentConfig` fluent API:
  `.system` / `.model` / `.memory` / `.guard` / `.tool` / `.rag` / `.mcp` /
  `.configure`. No wrapper proxy — `resolve()` returns LangChain4j's own proxy.
  (ROADMAP-12)
- **`VectorStore.pgVector(PgVectorConfig)`** — PostgreSQL/pgvector store over a
  HikariCP pool; DDL auto-migration (chunk table + ivfflat cosine index) on first
  connection; idempotent upsert; `deleteBySource` via metadata filter. (ROADMAP-17 P7–8)
- `ObserveBridge` gains `beforeAgent`/`afterAgent` and `beforeRetrieval`/`afterRetrieval`
  hooks; agent invocations and RAG retrieval now produce spans / console lines.
- **`Retriever.hybrid(k).denseWeight(x).sparseWeight(y)`** — dense semantic score
  fused with a BM25 term-frequency keyword score (re-ranks the dense candidate
  pool; works with every `VectorStore`). The former stub had a broken BM25 and
  fixed weights. (ROADMAP-17 P11)
- `AgentConfig.rag(retriever)` — per-agent RAG; an agent otherwise inherits the
  app-level `app.rag(...)`. Adapted to a LangChain4j `ContentRetriever`.
- Capstone applications live in `capstones/` in the build (`support-desk`,
  `meridian-qualify`, `acme-claims`, `invoice-processor`), consuming the framework
  as `project(':cafeai-*')`. `invoice-processor` was the standalone `atlas-inbox`
  (package `io.meridian.invoice`).

### Changed
- **Tools are agent-only.** `app.tool(...)` and the `cafeai-tools` module were
  removed; register `@Tool` classes on an agent via `app.agent(...).tool(...)`.
- **OpenTelemetry span attributes renamed to the GenAI semantic conventions**:
  `cafeai.model` → `gen_ai.response.model`, `cafeai.prompt_tokens` →
  `gen_ai.usage.input_tokens`, `cafeai.completion_tokens` →
  `gen_ai.usage.output_tokens`, `cafeai.error` → `error.type`; spans are named by
  `gen_ai.operation.name` (`chat` / `transcribe` / `invoke_agent` / `retrieve`).
  `cafeai.latency_ms`, `cafeai.cache_hit`, `cafeai.rag.documents_retrieved`,
  `cafeai.session.id` remain as CafeAI extensions. (ROADMAP-17 P9–10)

### Notes
- The PgVector integration test needs Docker; it self-skips otherwise.
