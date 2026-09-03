# Changelog

All notable changes to CafeAI. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
versions are the Maven Central coordinates under `com.akilisha.oss`.

## [Unreleased] — targeting 0.2.0

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
- Capstone applications live in `capstones/` in the build (`support-desk`,
  `meridian-qualify`, `acme-claims`, `invoice-processor`), consuming the framework
  as `project(':cafeai-*')`.

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
