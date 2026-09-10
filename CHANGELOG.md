# Changelog

All notable changes to CafeAI. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
versions are the Maven Central coordinates under `com.akilisha.oss`.

## [Unreleased]

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
