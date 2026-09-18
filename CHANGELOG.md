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
- **The `cafeai-streaming` module.** It never contained a source file: every
  version published to Maven Central (0.1.0 – 0.3.2) is a jar holding only a
  manifest. The SSE / WebSocket streaming its docs advertised lives in
  `cafeai-core`. Removed from the build, `cafeai-examples`, and the README,
  GETTING-STARTED, DEVELOPER_GUIDE and SPEC; the artifacts already on Central
  can't be deleted, but nothing new will be published.
- **`CAFEAI_MCP_SERVERS` handling in `Connect.fromEnv()`.** It parsed the
  variable and then did nothing with it (an empty loop body). `McpEndpoint` was
  never built. Also dropped "MCP" from the `cafeai-connect` description and the
  docs that claimed it.
- **`MemoryStrategy.chronicle()`**, a stub that threw "not yet implemented" while
  the README advertised `app.memory(MemoryStrategy.chronicle())` as a working
  option. Its Chronicle Map dependency (an early-access build, imported by
  nothing) rode along on every `cafeai-memory` consumer's runtime classpath.
  ADR-003 is amended: rungs 3 (Chronicle Map) and 5 (Memcached — which had no
  code at all) were never built.
- **Eight unused dependencies**, none referenced by any source or resource file,
  all `implementation` scope and so shipped to consumers' runtime classpaths:
  `opennlp-tools` (`cafeai-guardrails` — PII detection is regex), `chronicle-map`
  (`cafeai-memory`), `helidon-security` and its JWT provider (`cafeai-security`),
  `helidon-tracing`, its OpenTelemetry provider and `helidon-metrics`
  (`cafeai-observability` — it calls the OpenTelemetry API directly), plus a
  redundant `langchain4j-core` in guardrails and security. A consumer of the full
  stack goes from 373 to 293 runtime artifacts.
- **Documentation claims with nothing behind them**, corrected in the README,
  GETTING-STARTED, SPEC, EXTENDING and the LC4J guide: `MemoryStrategy.chronicle()`,
  Memcached, "PII detection: Apache OpenNLP", observability "metrics" and "prompt
  versioning" (neither exists), and stale Helidon / LangChain4j versions and
  provider list in the README's technology table.
- **Signed cookies, and `req.range()`.** `req.signedCookies()` / `signedCookie()` always
  returned empty/`null`, and `CookieOptions.signed(true)` was accepted and **never
  honoured — `res.cookie(...)` sent an unsigned cookie**, so anything relying on it for
  tamper-protection had none. Signing needs an application secret that CafeAI has no
  setting for, so the API is removed instead of implying protection it didn't give.
  `req.range(size)` was declared to return an untyped `Object` and always returned
  `null`. ADR-005 marks all of these "Omitted".
- **`GuardRail.bias()` and `GuardRail.hallucination()`** (and the same two methods on
  the `GuardRailProvider` SPI). They were pass-through stubs even with
  `cafeai-guardrails` on the classpath: `app.guard(GuardRail.bias())` returned a guardrail
  that let everything through, so the app looked protected when it was not. Bias
  detection needs a trained model that was never bundled; hallucination *scoring* exists
  as `EvalHarness`'s heuristic faithfulness / relevance / groundedness scores, which score
  rather than block.
- **The semantic-cache remnants.** No semantic cache was ever built, yet:
  `PromptResponse`/`VisionResponse`/`AudioResponse.fromCache()` was hard-wired to `false`
  (and `PromptResponse.Builder.fromCache(boolean)`), observability branched on it and wrote
  a `cafeai.cache_hit` span attribute that could only ever be `false`, and the security
  module shipped `AiSecurity.semanticCachePoisoningDetector()` with a
  `SecurityEvent.CachePoisoningAttempt` type. That detector was not inert: it answered
  `400 "Potential cache poisoning attempt detected"` to any prompt under 200 characters
  containing four of `always`, `never`, `respond`, `say`, `output`, `return`, `answer`,
  `tell`, `write`, `pretend` — an ordinary short instruction — to protect a cache that does
  not exist. All removed. `SecurityEvent` is a sealed interface, so an exhaustive `switch`
  over it must drop its `CachePoisoningAttempt` case.
- **Wrong module descriptions**, which are published to Maven Central: `cafeai-security`
  claimed jailbreak detection, PII scrubbing and token-budget enforcement (none are in that
  module; it has `promptInjectionDetector`, `ragDataLeakagePrevention` and `onEvent`),
  `cafeai-guardrails` claimed bias and hallucination detection and "NLP" (it is all
  pattern-based), and `cafeai-observability` claimed metrics and prompt versioning.
  The README, GETTING-STARTED, SPEC and LC4J guide are corrected, and the SPEC's
  `EvalStrategy.faithfulness()` (no such class) now reads `EvalHarness.defaults()`.

### Fixed

- **Guardrails were not enforced on `app.prompt()` — security.** `app.prompt(...).call()` and
  `.stream()` ran no guardrail at all: PRE_LLM and POST_LLM checks existed only on `vision`
  and `audio`. The HTTP-middleware form of a guardrail could not fill the gap — it runs after
  the route handler, which has already sent the response, so it could never stop an output —
  and it did nothing for programmatic (non-HTTP) calls. An app that registered
  `app.guard(GuardRail.pii())` and called `app.prompt(userText)` had no guardrail on that call.
  The engine now applies every registered guardrail to `prompt`, streamed `prompt`, `vision` and
  `audio` through one shared path, on the text the model actually sees.
- **`GuardRail.Action` is honoured by the engine.** `BLOCK` / `WARN` / `LOG` worked on the
  HTTP path but the engine ignored it and always blocked, so a `LOG`-only guardrail still
  blocked a vision call. `WARN` and `LOG` now record the violation and let the call proceed.
- **Agents (`app.agent(...)`) get the same treatment.** `GuardrailAdapters`, which applies a
  CafeAI guardrail through LangChain4j's `AiServices`, had no tests and ignored `Action` (every
  violation was a hard failure), threw on a multimodal user message (`singleText()`), would NPE on
  a guardrail that returned `null`, and put the guardrail's *reason* into the exception
  LangChain4j throws to the caller. It now honours `Action`, screens a multimodal message on its
  text, treats `null` as a pass, and names the guardrail without the reason (which is logged).
  The default error handler maps LangChain4j's `InputGuardrailException` to `400` and
  `OutputGuardrailException` to `500`, with a generic body.
- **A blocked request is a typed exception and a 400, not a 500.** Blocked input throws the new
  `GuardRailViolationException` (a `RuntimeException`, so existing catches still work) instead of
  a bare `RuntimeException`. With no error handler that claims it, the default handler answers
  `400` with the guardrail's *name only*; the reason (a matched pattern, a moderation verdict)
  stays in the logs, because it tells an attacker how the detector works. The old path returned
  a 500 that echoed the exception message.
- **The request/response pair was never wired.** `res.request()`, `req.response()`
  and `res.app()` returned `null` (nothing set them), so `res.format()` never saw the
  request's `Accept` header and always chose the first handler. They are now paired
  where `CafeAIApp` builds the context, and `res.format()` negotiates properly (and
  answers 406 when nothing matches).
- **`res.render()` always threw** `UnsupportedOperationException` ("requires
  app.engine() registration -- ROADMAP-02 Phase 8") even with `app.engine(...)` and a
  view engine registered. It now renders through the app's engine and sends `text/html`;
  locals layer as `app.locals()` < `res.locals()` < the locals you pass.
- **`req.cookies()` / `req.cookie(name)`** returned an empty map / `null` whatever the
  client sent — the "cookieParser middleware" their Javadoc required never existed. They
  now parse the `Cookie` header; no middleware is needed.
- **`req.accepts()`** used substring matching, ignoring `q`-values (`Accept:
  text/html;q=0.1, application/json;q=0.9` selected HTML). It, and
  `acceptsCharsets/Encodings/Languages()` — which returned the first offer regardless of
  the header — now do real negotiation (`q`, wildcard specificity, `q=0` refusal,
  `en` → `en-US`).
- **`req.fresh()` / `req.stale()`** were hard-wired to `false` / `true`. They now compare
  `If-None-Match` to the response's `ETag` (weakly) or `If-Modified-Since` to its
  `Last-Modified`, per RFC 9110.
- **`CookieOptions.expires(...)`** was accepted and never written. `res.cookie(...)` now
  emits an `Expires` attribute in HTTP-date form.

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

- **Semantic cache, with cache-poisoning defences: `app.cache(SemanticCache...)`.** Repeat
  `app.prompt()` questions are answered from a cache matched by meaning, skipping the model call;
  `response.fromCache()` (and the `cafeai.cache_hit` span attribute) report it, truthfully this
  time. A cache shared between users is an attack surface — one user's request can decide what
  another is told — so the defences are the design: only **clean, prompt-only** answers are stored
  (nothing any guardrail flagged, even a `WARN`; nothing with a `session()` or with RAG configured;
  nothing over a size limit); entries are namespaced by model, its settings and the system prompt; a
  hit requires high embedding similarity **and** high word overlap **and** similar length, so a
  victim's question with instructions appended does not match it; every hit is **re-screened** by the
  current `POST_LLM` guardrails and evicted if it fails; and entries expire and are size-bounded. A
  request blocked by a guardrail never reaches the cache, and a cache or embedding failure never
  fails the call. `.noCache()` opts a call out; vision and audio are never cached. `SemanticCache` is
  an interface to back with your own store; `SemanticCache.inMemory(embedder)` is per-process and
  scans linearly. The end-to-end test runs the attack itself and shows the victim is not served the
  poisoned entry; a control test shows it *would* be with the word-overlap and length guards off.
  See ADR-013 for the threat model and its limits. This replaces the removed
  `semanticCachePoisoningDetector()`, which guarded a cache that did not exist.
- **`EmbeddingProvider.of(EmbeddingModel)`** — any LangChain4j embedding model as a CafeAI
  embedding provider, without an adapter of yours.
- **Content moderation by a model: `GuardRail.moderation(ModerationModel)`.** A guardrail
  backed by LangChain4j's own `ModerationModel` — a model, not a pattern list, so it catches what
  keyword rules cannot. CafeAI adds no wrapper: pass any provider's model, or use
  `OpenAI.moderation("omni-moderation-latest")`, which returns the LangChain4j type. It applies to
  `prompt`, `vision` and `audio` and, through the adapters, to `app.agent(...)`. Configure it with
  `.at(Position)`, `.action(Action)`, `.named(...)` and `.failOpen()`. **It fails closed**: if the
  moderation call itself fails, the text is blocked, because a safety control that quietly passes
  everything when its dependency is down only looks protective (`failOpen()` opts out, logged at
  WARN). LangChain4j's portable `Moderation` carries only a verdict, so that is all it reports.
  LangChain4j's own agent hook is tested too: `configure(b -> b.moderationModel(m))` with
  `@Moderate` throws its `ModerationException`. See `LC4J-TO-CAFEAI.md` §2.11 for every seam where
  CafeAI accepts a LangChain4j type directly.
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
