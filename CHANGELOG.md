# Changelog

All notable changes to CafeAI. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
versions are the Maven Central coordinates under `com.akilisha.oss`.

## [Unreleased]

### Removed — BREAKING

- **`CafeAIRegistry`, `CafeAIRegistryImpl`, and `CafeAIModule.register(...)`.**
  The registry was write-only: seven modules registered about 19 named
  capability factories into it, and no code — in any module, test, or the git
  history — ever read one back. (Some registrations were placeholders such as
  `registerMemoryStrategy("redis", () -> null)`.) Capabilities are wired through
  the provider SPIs (`GuardRailProvider`, `MemoryStrategyProvider`, `RagProvider`),
  so nothing that worked stops working. `CafeAIModule` is now just `name()` and
  `version()`; module discovery is unchanged and each module is still logged at
  startup. External modules must delete their `register` method — see
  `MIGRATION.md` and the amendment to ADR-006. The startup DEBUG lines
  `CafeAI registry: … registered` are gone.
- **Public API that did nothing.** A dead-code audit found declarations with no
  reader or caller anywhere in the repo. Removed:
  - `TextGuardRail` — an interface with no implementer and no caller; its
    Javadoc described a use "in `AgentRegistry`" that never existed.
  - Six `Setting` values that could be set but that nothing ever read:
    `CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`, `ETAG`, `JSON_ESCAPE_HTML`,
    `QUERY_PARSER`, `VIEW_CACHE`. Setting them silently changed nothing.
  - `AiProvider.ProviderType.AZURE_OPENAI` and `GOOGLE_VERTEX` — no provider
    produced them, and the bridge throws for them.
  - `Connection.ServiceType.MCP` and `EMBEDDING`.
  - `returningType()` on `PromptRequest`, `VisionRequest` and `AudioRequest`
    (see the fix below).
  - `PodState.hasContainerTrouble()` and `hasWarningEvents()`.

### Fixed

- **`.returning(Class)` was a no-op.** It stored the type in a field nothing
  read; the schema instruction reached the prompt only via `call(Class)`, so
  `returning(X.class).call()` sent no instruction, and the documented
  `.returning(X.class).call(X.class)` named the type twice for no reason.
  `returning()` now appends the type's JSON schema instruction to the prompt, so
  `.call()` returns JSON text in that shape; `.call(X.class)` is unchanged and
  needs no separate `returning()`. Applies to `prompt`, `vision` and `audio`.

### Housekeeping

- Deleted `StreamingProbe` (a scratch file left in `cafeai-core`'s main source),
  an unreachable `if (false)` branch in `CafeAIApp`, two unused loggers, three
  unused imports, and `TokenBudgetTracker.currentWindowTokens()`.
- `docs/adr/`: `ADR-010` and `ADR-008-connectivity-…` were byte-identical.
  Kept `ADR-010`, corrected its title, and repointed `ROADMAP-09` at it.

### Added

- **NVIDIA provider** — `io.cafeai.core.ai.Nvidia`, for models on NVIDIA's hosted
  API catalog (`Nvidia.of("moonshotai/kimi-k3")`, key from `$NVIDIA_API_KEY`).
  The endpoint is OpenAI-compatible, so it is wired through `ChatModelAccess`
  and `StreamingChatModelAccess` like `Gemini`; no new dependency. Uses a
  5-minute timeout rather than `cafeai.chat.timeout`, since hosted reasoning
  models can exceed 60 seconds before the first token.
  `Nvidia.of(id)` also takes `.withReasoningEffort("max")`, which is NVIDIA-specific.
  See `NvidiaVisionExample` in `cafeai-examples`.
- **`withTemperature(double)`, `withMaxTokens(int)` and `withTimeout(Duration)`
  on every provider** —
  `OpenAI`, `Anthropic`, `Gemini`, `Ollama`, `Jlama` and `Nvidia` were all
  `of(modelId)` and nothing more, so there was no way to set any of them. They are
  now on the `AiProvider` interface (plus `temperature()` / `maxTokens()` /
  `timeout()` accessors), each returning an immutable copy:
  `app.ai(Anthropic.of("claude-sonnet-4-5").withTemperature(0))`. Unset means the
  model's own default, exactly as before. A custom `AiProvider` that doesn't
  override them throws `UnsupportedOperationException` rather than ignoring the
  setting, and so does `ModelRouter` — set them on the models it routes between.
  `maxTokens` maps to the vendor's own parameter (`max_completion_tokens` for
  OpenAI, `num_predict` for Ollama, `maxOutputTokens` for Gemini).
  `withTimeout` overrides `cafeai.chat.timeout` (60s by default) for that one
  provider — the right granularity, since a classifier and a reasoning model that
  takes minutes to respond don't share a sensible limit. `Jlama` refuses it: it
  runs in-process, so there is no network call to time out.
- **`.onThinking(Consumer<String>)`** on `PromptRequest` and `VisionRequest` —
  receives a reasoning model's thinking tokens during `.stream(...)`, as a side
  channel that never reaches the answer text, session memory or guardrails.
  Providers that don't emit reasoning are unaffected. `Nvidia` enables it; the
  built-in OpenAI/Anthropic/Ollama/Jlama providers don't request thinking yet.

### Fixed

- **Chat-model cache shared models it shouldn't have.** `LangchainBridge` cached
  by `name:modelId`, so two `Ollama.at(...)` providers on different base URLs
  serving the same model id shared one client. It is now keyed on the provider
  itself (a value-comparing record), which the new temperature / max-token
  settings also require.

## [0.4.0] — 2026-09

### Changed — BREAKING

- **`VectorStore`, `EmbeddingProvider`, `Retriever`, `Source`, `RagDocument`
  moved from `io.cafeai.rag` to `io.cafeai.core.rag`.** `app.vectordb()`,
  `.embed()`, `.ingest()`, `.rag()` are now properly typed (were `Object`,
  checked only at runtime). `PromptResponse`/`AudioResponse`/
  `VisionResponse.ragDocuments()` now return `List<RagDocument>` (was
  `List<Object>`, with one carrying a comment admitting it was "typed as
  Object to avoid dep"). The old `io.cafeai.core.spi.RagPipeline` SPI is
  deleted outright — no longer needed once the types are core-owned.
  `EmbeddingModel` is renamed `EmbeddingProvider`, closing the one real name
  collision against LangChain4j's own `EmbeddingModel` in the framework.
  `cafeai-rag` still supplies everything needing real dependencies (Chroma,
  pgvector, Tika, an ONNX model) via the new `io.cafeai.core.spi.RagProvider`
  SPI. Full rationale in `docs/adr/ADR-011-rag-provider-abstraction.md`.
- **`Connection`, `HealthStatus`, `Fallback` moved from `io.cafeai.connect`
  to `io.cafeai.core.connect`.** `app.connect()` is now typed
  `CafeAI.connect(Connection)` (was `Object`). The old
  `io.cafeai.core.spi.ConnectBridge` SPI is deleted — a `Connection` is
  self-contained and calls back into already-typed `app.vectordb()`/
  `.memory()`/`.ai()`, so there was nothing for a provider SPI to supply. A
  fully custom `Connection` implementation no longer needs
  `cafeai-connect` on the classpath at all.
- **`EmbeddingProvider.openAi()` has no default model id.** The old
  zero-arg factory silently defaulted to the retired
  `text-embedding-ada-002`. Pass a model id explicitly
  (`EmbeddingProvider.openAi("text-embedding-3-large")`), or set
  `CAFEAI_EMBEDDING_MODEL` and call the zero-arg overload. Dimensionality is
  now measured from a real embedding call, not guessed from the model id
  string.

### Added

- **`cafeai-config`** — file-based application configuration. A
  `io.cafeai.core.config.ConfigKey` declares a value (dotted name, type,
  default, description) right where it's used; `AppConfig.load()` resolves
  it. `cafeai-core` resolves only the coded default; `cafeai-config`
  resolves everything else — system properties, environment variables, an
  external file (`CAFEAI_CONFIG_FILE`, e.g. a Kubernetes ConfigMap volume),
  and `application.properties`/`.yaml` with profile overlays — built on
  Helidon Config, not a hand-rolled merger. No module that declares a key
  needs a new dependency to do so. Full rationale in
  `docs/adr/ADR-012-application-config.md`.
- Three previously hardcoded, non-overridable constants are now `ConfigKey`s:
  `LangchainBridge`'s 60-second chat timeout (`cafeai.chat.timeout`, applied
  to every provider, every call site), `AgentRegistry`'s 20-message chat
  memory window (`cafeai.agent.memory.window`, previously fixed regardless
  of the configured `MemoryStrategy`), and `WebhookSink`'s timeout/retry
  count (`cafeai.sentinel.webhook.timeout`/`.max_attempts`).
- `CafeAIModule.versionOf(Class)` reads a module's real version from its JAR
  manifest (`Implementation-Version`, stamped by the build from
  `project.version`) instead of a hardcoded literal — every module's
  `version()` was returning `"0.1.0"` regardless of the actual released
  version. The OTel tracer in `cafeai-observability` had the identical bug
  independently and is fixed the same way.

### Removed

- `helidon-config-yaml` from `cafeai-core` — a dependency every application
  carried with zero actual usage anywhere in the module's source, almost
  certainly added in anticipation of the `cafeai-config` work above and
  never wired up. Now lives only in `cafeai-config`, where it's used.

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
