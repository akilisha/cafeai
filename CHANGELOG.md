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
- **A session's history is no longer sent whole: the default is the newest 20 messages.** Every
  `app.prompt()`, `.vision()` and `.audio()` call that used `.session(...)` resent the entire
  conversation, so each call cost more than the last and the session eventually overflowed the
  model's context window. Set `cafeai.memory.window` (0 sends everything, as before) or choose a
  policy with `app.history(...)` (below). What is stored is unchanged; only what is sent is limited.
- **`ConversationContext` no longer trims itself.** `new ConversationContext(id, maxTokens)`,
  `maxTokens()` and `DEFAULT_MAX_TOKENS` are removed. The engine never enabled that trimming, and
  when enabled it cut the history to two messages and counted cumulative tokens rather than the
  size of the context; use a `HistoryPolicy` instead. Sessions stored with the old `maxTokens`
  field still load.

### Removed

- **Modules and dependencies.** The `cafeai-streaming` module (streaming lives in `cafeai-core`).
  Eight unused libraries no longer ship on consumers' runtime classpaths: `opennlp-tools`,
  `chronicle-map`, `helidon-security` and its JWT provider, `helidon-tracing`, its OpenTelemetry
  provider and `helidon-metrics`, and a duplicate `langchain4j-core`; `helidon-config-yaml` now
  lives only in `cafeai-config`. A consumer of the full stack goes from 373 to 293 runtime artifacts.
- **Guardrails.** `GuardRail.bias()`, `GuardRail.hallucination()` (and the same methods on
  `GuardRailProvider`), `PiiGuardRail.scrubbing()` and `StubGuardRail`.
- **Security.** `AiSecurity.ragDataLeakagePrevention()`, `AiSecurity.semanticCachePoisoningDetector()`,
  `SecurityEvent.DataLeakageAttempt`, `SecurityEvent.CachePoisoningAttempt` and
  `SecurityEvent.InjectionAttempt.source()`. CafeAI has no per-user document access control.
- **View engines.** `ResponseFormatter.markdown()` (there is no Markdown engine module) and
  `ViewEngineProvider.extensions()` (nothing called it). Delete the method from your own provider.
- **Observability.** `app.eval(...)` and `EvalHarness`; the `Attributes` constants `EVAL_SCORES`,
  `RAG_DOCUMENTS` and `RAG_CONTEXT`. The documents behind an answer are on
  `PromptResponse.ragDocuments()`.
- **Memory and connectivity.** `MemoryStrategy.chronicle()`; `CAFEAI_MCP_SERVERS` handling in
  `Connect.fromEnv()`; `Connection.ServiceType.MCP` and `EMBEDDING`.
- **HTTP.** `CookieOptions.signed(...)`, `req.signedCookies()`, `req.signedCookie(...)` and
  `req.range(...)`. Signing needs an application secret; sign and verify a cookie value yourself.
- **Declarations.** `TextGuardRail`; the settings `CASE_SENSITIVE_ROUTING`, `STRICT_ROUTING`, `ETAG`,
  `JSON_ESCAPE_HTML`, `QUERY_PARSER` and `VIEW_CACHE`; `AiProvider.ProviderType.AZURE_OPENAI` and
  `GOOGLE_VERTEX`; `returningType()` on the request types; `fromCache()` on `VisionResponse` and
  `AudioResponse`; `PodState.hasContainerTrouble()` and `hasWarningEvents()`.

### Added

- **Live tests for every provider.** OpenAI, Anthropic, Gemini and Ollama join NVIDIA and Jlama, all
  running one shared suite (plain, streamed and structured calls, system prompt, session memory,
  `HistoryPolicy.summarise()` and `lastMessages()`, `withMaxTokens` / `withTemperature` / `withTimeout`,
  vision), plus OpenAI's speech round trip and moderation guardrail, and an agent-with-a-tool test in
  `cafeai-agents`. They are opt-in (`./gradlew :cafeai-core:liveTest`, `:cafeai-agents:liveTest`), skip
  when a provider is unavailable, and are described in GETTING-STARTED.md.
- **Tunable values are settings.** Constants an operator might reasonably need to change are now
  `ConfigKey`s whose default is the value the constant had, so nothing changes until something sets
  them; the fluent setters (`RetryPolicy.maxAttempts`, `.threshold(...)`, the cache builder,
  `RedisConfig.sessionTtl`, ...) still win. New: `cafeai.retry.attempts` / `.backoff`,
  `cafeai.nvidia.timeout`, `cafeai.cache.*` (threshold, overlap, length ratio, ttl, entries, response
  chars), `cafeai.http.body.limit`, `cafeai.http.file.block`, `cafeai.rag.chunk.size` / `.overlap`,
  `cafeai.guardrails.jailbreak.threshold`, `.toxicity.threshold`, `.promptleak.window`,
  `cafeai.connect.ollama.probe.timeout`, `.redis.probe.timeout`, `cafeai.memory.redis.ttl`,
  `.hybrid.demote`, `cafeai.voice.chunk.min`, and for `cafeai-sentinel` `cafeai.sentinel.evidence.max`,
  `.investigation.workers` / `.tokens` / `.failures`, `.sweep.interval`, `.resolve.after`,
  `.update.debounce`, `.events.per.pod`, `.sync.timeout`, `.probe.failures`, `.tool.log.lines` /
  `.events` / `.replicasets`. A value a class cannot use is refused where it is read, naming the
  setting (`AppConfig.apply`, `positive`, `positiveLong`, `positiveDuration`). DEVELOPER_GUIDE.md
  §17.6 lists all 43 settings with their defaults.
- **History policies: `app.history(HistoryPolicy...)`.** Three ways to keep a long conversation
  affordable, and one to turn the limit off:
  `HistoryPolicy.lastMessages(n)` sends the newest `n` messages;
  `HistoryPolicy.tokenBudget(n)` sends as many of the newest as fit in `n` estimated tokens (pass
  your own counter, such as a LangChain4j `TokenCountEstimator`, as the second argument);
  `HistoryPolicy.summarise().keepRecent(6).after(20)` folds the older turns into a running summary
  that is sent with the system prompt, written by the model that answered or by one you name with
  `.model(...)`; `HistoryPolicy.all()` sends everything. The first two only choose what to send.
  Summarising rewrites the stored history and costs an extra model call on the turn that triggers
  it; if that call fails, or an input guardrail blocks the summary, the history is left as it is and
  the user's call still succeeds. The policy applies to prompt, vision and audio calls, streamed or
  not, but not to agents (their memory is a window of `cafeai.agent.memory.window` messages).
  Settings: `cafeai.memory.window` (20), `cafeai.memory.budget` (4000), `cafeai.memory.summary.after`
  (20) and `cafeai.memory.summary.keep` (6). They supply numbers only; a setting never switches a
  policy on.
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
- **Byte-range requests in `CafeAI.serveStatic(...)`.** The server answers `Range: bytes=...` with `206 Partial
  Content` (open-ended and suffix ranges, an end past the file clamped, `416` with `Content-Range: bytes */N`
  for a range past the end, `If-Range` honoured), so browsers can seek in audio and video. One range per
  request; several, or a malformed one, get the whole file. `StaticOptions.acceptRanges(false)` turns it off.
  The static server now has behaviour tests (files, dotfiles, traversal, caching headers, conditional
  requests and ranges).
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
- **Live provider tests: `./gradlew :cafeai-core:liveTest`.** Tests tagged `@Tag("live")` call a real
  provider with your own key from the environment (`NVIDIA_API_KEY`), are excluded from `test`, and
  skip themselves when the key is absent. `NvidiaLiveTest` covers a plain and a streamed call,
  structured output, a system prompt, session memory, and the `withMaxTokens` / `withTemperature` /
  `withTimeout` settings, plus opt-in thinking-stream and vision tests. Apply
  `gradle/live-tests.gradle` in a module to add more. See GETTING-STARTED.md.
- **Jlama is tested against a real model** (`JlamaLiveTest`, opt-in with `JLAMA_LIVE_MODEL`; plus
  `JlamaMappingTest` for the settings mapping, which needs no model). Jlama's `withMaxTokens` limits the
  prompt *and* the answer, unlike the other providers; a limit smaller than the prompt fails with
  `Prompt exceeds max tokens`. This is documented on `AiProvider.withMaxTokens` and `Jlama`.
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

- **Gemini can stream.** `app.prompt(...).stream(...)` and `app.vision(...).stream(...)` with
  `Gemini.of(...)` failed with `Streaming not supported for provider type: CUSTOM`, because the provider
  supplied a chat model but no streaming one. It now supplies both.
- **A provider that reports no token count no longer crashes the call.** Gemini leaves the output-token
  count out when it stops at a token limit, and LangChain4j then returns `null`; the engine unboxed it, so
  a good answer ended in a `NullPointerException`. A missing count is now zero.
- **A model that answers with no text gives an empty answer, not `null`.** A thinking model whose
  `withMaxTokens` limit is spent on thinking returns no text; `PromptResponse.text()` was `null` and the
  `null` reached guardrails and session memory. It is now an empty string.
- **An agent with tools and session memory crashed once it called a tool.** A tool call is an assistant
  message with no text and its result is a message of its own; the memory adapter stored the first as
  `null` and dropped the second, then failed rebuilding the history (`text cannot be null`) and would
  have sent the model a tool call with no result. Such messages are now stored as LangChain4j's own JSON
  under the role `langchain4j` and come back as they went in; plain user and assistant turns are stored
  as text, as before. Found by a live test against a real model; the tests that missed it never called a
  tool.
- **A history summary is worded so the model knows it is about the user.** The summary is written as
  notes ("The user's name is ...") and introduced as the model's memory of the conversation, where it
  had been narrated in the third person and labelled "not instructions"; a small model did not connect
  it to a question about the user.
- **`serveStatic` streams files from disk** instead of reading each one into memory, so a large
  video or download no longer costs its size in heap per request; byte-range responses stream the
  requested slice the same way. `res.sendFile` and `res.download` stream too, and a new
  `res.sendFile(path, offset, length)` sends part of a file. A response streamed from middleware
  (`res.stream(...)` or a streamed file under `app.use`) is now accepted by Helidon's filter check;
  before, it could end with the client waiting on a stream that was never finished.
- **Agents apply the guardrails registered with `app.guard(...)`.** Only guardrails added with an
  agent's own `.guard(...)` reached it, though the documentation said the app's did too. An agent now
  applies both. As with `app.prompt()`, retrieved documents on the agent path are not screened.
- **`OpenAI.of(...)` supports `app.audio()` again.** An earlier capability check made every
  `OpenAI` provider refuse audio; audio goes to OpenAI's transcription endpoint whatever the chat
  model is.
- **`app.prompt(...).call(Type.class)` no longer overflows the stack on a self-referencing type**
  (a `Node` with a `Node` field, or two records that reference each other). The schema hint stops
  descending at a type it is already inside.
- The chat-model cache is keyed on the provider itself (a value-comparing record) rather than
  `name:modelId`, so two `Ollama.at(...)` providers on different base URLs no longer share a client.
- **A failed prompt, vision or audio call is now traced.** When the model call threw, the engine
  rethrew before telling the observe bridge, so an OpenTelemetry span was started and never ended
  (never exported) and the console strategy logged nothing. The call now ends its span with `ERROR`
  status and `error.type`, or logs the error. The documented span attributes are corrected to the names
  recorded (`gen_ai.*`, `cafeai.session.id`, `cafeai.rag.documents_retrieved`, `error.type`); the
  listed `cafeai.guardrail_triggered` was never recorded.
- **`cafeai-views-mustache` (now tested)** rendered nothing on Windows: Mustache.java treated the absolute
  template path (`D:\views\page.html`) as a URI and failed. Templates are now compiled by file name against
  their own directory, which also makes `{{>partial}}` resolve next to the including template on every
  OS. A template edited on disk is recompiled on its next render, without a restart.
- **`res.render()` / `app.render()` refuse a view outside the views directory.** A name such as
  `../secrets.html`, or an absolute path, was joined onto the views directory and read; it now fails with a
  `RenderException`. This matters when a view name comes from a request.
- **`cafeai-connect`** (now tested: unit tests for every connector and `Connect.fromEnv()`, and
  Testcontainers tests against a real Redis and pgvector):
  - Credentials no longer reach logs or `/health`: `PgVector.name()` embedded the whole JDBC URL
    (including `?user=…&password=…`), `Ollama.name()` its userinfo, and `Connect.fromEnv()` logged
    `REDIS_URL` in full, at warn level when it could not be parsed.
  - `REDIS_URL=redis://:secret@host` (and `user:secret@host`) used `:secret` as the password; the
    password is now the part after the colon. `rediss://` turns TLS on and a `/N` path selects the database.
  - Ollama's probe matched the model id as a substring of the whole `/api/tags` response, so `llama3` was
    satisfied by `llama3.1:8b`. It now matches an installed model exactly (an untagged id means `:latest`).
  - `PgVector` registered a store without the credentials that its probe had used from the URL query.
  - Probes time out (Ollama 5s, pgvector 3s) instead of waiting on a dead host, and Ollama's probe closes its
    HTTP client. A bad `REDIS_PORT` fails with a message that names the variable.
  - `Connect.fromEnv()`'s Javadoc listed values it never handled (`openai`, `anthropic`, `inmemory`, `mapped`).
- `GuardRail.jailbreak()` matches `DAN` as a word, not as a substring of "Daniel" or "abundant".
- `TopicBoundaryGuardRailImpl` no longer returns application-specific text as its reason.
- **`PgVectorConfig` searches exactly by default** (`useIndex` is `false`). The `ivfflat` index it
  used to create on an empty table with 100 lists could return fewer results than exist on a small
  corpus. Set `useIndex(true)` for a very large corpus, and build the index after loading it; see
  the `PgVectorConfig` Javadoc.

### Housekeeping

- Testcontainers is 1.21.4 (was 1.20.2): older releases cannot talk to Docker Engine 29, so the
  container-based tests were skipped on a machine running it.
- CI: `.github/workflows/ci.yml` builds and tests every module, and compiles every capstone, on each
  push to `main` and each pull request. It needs no API keys; live provider tests are excluded.
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
