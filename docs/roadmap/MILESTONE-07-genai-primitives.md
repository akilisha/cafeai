# MILESTONE-07: Gen AI Primitives

> **Note:** `cafeai-tools` was removed in session 9. The module relied on deprecated
> LangChain4j APIs (`dev.langchain4j.agent.tool`). Tool use and MCP integration
> will be rebuilt on the current API in ROADMAP-17. This document is preserved
> as a historical record.

**Roadmap:** ROADMAP-07
**Module:** `cafeai-core`, `cafeai-memory`, `cafeai-rag`, `cafeai-tools`
**Started:** March 2026
**Current Status:** 🟢 Complete (Phases 1–7, 9–10 done · Phase 8 deferred to separate roadmap)

---

## Progress Tracker

| Phase | Description | Module | Status | Completed |
|---|---|---|---|---|
| Phase 1 | `app.ai()` — LLM provider registration | `cafeai-core` | 🟢 Complete | March 2026 |
| Phase 2 | `app.system()` + `app.template()` | `cafeai-core` | 🟢 Complete | March 2026 |
| Phase 3 | `app.memory()` — tiered context memory | `cafeai-memory` | 🟢 Complete | March 2026 |
| Phase 4 | `app.vectordb()` + `app.embed()` + `app.ingest()` + `app.rag()` | `cafeai-rag` | 🟢 Complete | March 2026 |
| Phase 5 | `app.tool()` + `app.mcp()` | `cafeai-tools` | 🟢 Complete | March 2026 |
| Phase 6 | `app.chain()` — named composable pipelines | `cafeai-core` | 🟢 Complete | March 2026 |
| Phase 7 | `app.guard()` — guardrails as middleware | `cafeai-guardrails` | 🟢 Complete | March 2026 |
| Phase 8 | `app.agent()` + `app.orchestrate()` | `cafeai-agents` | 🔵 Deferred | Follows observability |
| Phase 9 | `app.observe()` + `app.eval()` | `cafeai-observability` | 🟢 Complete | March 2026 |
| Phase 10 | Security layer | `cafeai-security` | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — `app.ai()` (March 2026)**

- `AiProvider` interface — `name()`, `modelId()`, `type()`
- `OpenAI`, `Anthropic`, `Ollama` factories — all model variants
- `ModelRouter.smart().simple(provider).complex(provider)` — cost-aware routing
- `LangchainBridge` — internal `AiProvider` → `ChatLanguageModel` factory; model cache
- `LangchainBridge.ChatLanguageModelAccess` — test seam interface (public)
- `LangchainBridge.OllamaProviderAccess` — base URL accessor for Ollama (public)
- `app.ai(AiProvider)` / `app.ai(ModelRouter)` — stored in locals and private field

**Phase 2 — `app.system()` + `app.template()` (March 2026)**

- `app.system(String)` — sets application-wide system prompt; stored in locals
- `app.template(name, body)` — registers `{{variable}}` interpolation template
- `app.template(name)` — retrieves a `Template` instance
- `Template.render(Map)` — permissive; leaves missing vars as `{{var}}`
- `Template.renderStrict(Map)` — throws `TemplateException` on missing variable
- `app.prompt(String)` → `PromptRequest` — fluent builder
- `app.prompt(templateName, vars)` — renders template then creates `PromptRequest`
- `PromptRequest.session(id)` — attaches session for memory threading
- `PromptRequest.system(override)` — per-call system prompt override
- `PromptRequest.call()` — executes the full pipeline synchronously
- `PromptResponse` — text, token counts, modelId, fromCache, ragDocuments

**Phase 3 — `app.memory()` (March 2026)**

- `MemoryStrategy.inMemory()` — fully functional, instance-scoped map (not static)
- `MemoryStrategy.mapped()` — SSD-backed via Java 21 FFM; crash recovery; `cafeai-memory`
- `MemoryStrategy.redis(config)` — Lettuce sync API; TTL refresh on access; `cafeai-memory`
- `MemoryStrategy.hybrid()` — warm+cold tiering; `demoteIdleSessions()`; `cafeai-memory`
- `ConversationContext` — thread-safe; Jackson-annotated for serialisation; context window trimming
- `MemoryStrategyProvider` SPI — ServiceLoader, `cafeai-memory` registers via META-INF/services
- `RedisConfig` — builder with host, port, password, database, TTL, SSL

**Phase 4 — RAG Pipeline (March 2026)**

- `VectorStore` interface + `InMemoryVectorStore` — brute-force cosine similarity
- `EmbeddingModel` — `local()` (ONNX all-MiniLM, no API key), `openAi()`
- `Source` — `text()`, `file()`, `pdf()` (Apache Tika), `directory()`, `url()` (Java HttpClient)
- `Chunker` — sliding window with configurable size and overlap; deterministic chunk IDs
- `Retriever` — `semantic(topK)` and `hybrid(topK)` (dense + BM25 RRF)
- `RagDocument` — content, sourceId, score, chunkIndex; `toString()` returns content
- `RagPipeline` SPI — `cafeai-rag` provides ingest and retrieve; no circular dependency
- `CafeAIRagPipeline` — ServiceLoader registration
- RAG wired into `executePrompt()` — retrieves top-K, injects context block before user message
- `PromptResponse.ragDocuments()` — retrieved docs accessible after each call
- `Attributes.RAG_DOCUMENTS` — key for storing docs in request attributes

**Phase 5 — Tools + MCP (March 2026)**

- `@CafeAITool("description")` — annotation for Java tool methods
- `ToolDefinition` — wraps method with schema, invocation, trust level (INTERNAL/EXTERNAL)
- `ToolRegistry` — scans instances for `@CafeAITool`; manages ReAct tool loop via Langchain4j
- `McpServer.connect(url)` — MCP JSON-RPC client; `discoverTools()`, `invokeTool()`
  - Implements MCP protocol directly via Java HttpClient — no third-party MCP library
- `ToolBridge` SPI — `cafeai-tools` provides tool execution without circular dependency
- `CafeAIToolBridge` — ServiceLoader registration; bridges `app.tool()` to `ToolRegistry`
- `app.tool(instance)` / `app.tools(instances...)` — registers Java tool providers
- `app.mcp(McpServer)` — discovers and registers MCP tools as EXTERNAL trust level
- `executePrompt()` — routes through `toolBridge.executeWithTools()` when tools registered
- Tool loop — ReAct pattern: send specs → LLM requests tool → invoke → return result → repeat
- Tool errors caught and returned as `"ERROR: ..."` strings — never propagate to LLM

---

## Decisions & Design Updates

**March 2026 — SPI pattern for cross-module AI features (ADR extension)**

`cafeai-rag` and `cafeai-tools` both depend on `cafeai-core`. To avoid circular
dependencies, all typed cross-module calls use `Object` parameters with SPI bridges
discovered via `ServiceLoader`. Pattern: `RagPipeline` SPI for RAG, `ToolBridge` SPI
for tools. Same pattern as `MemoryStrategyProvider` and `ViewEngineProvider`. Adding
the JAR activates the feature. Missing module throws with exact dependency coordinates.

**March 2026 — MCP via Java HttpClient, not Helidon WebClient**

The roadmap specifies Helidon WebClient for MCP. Implemented with Java 21's built-in
`HttpClient` instead — synchronous, zero extra dependency, identical semantics on virtual
threads. Helidon WebClient is reactive/async, which adds complexity without benefit when
`HttpClient.send()` parks the virtual thread correctly. The principle (no third-party MCP
library) is honoured.

**March 2026 — RAG context injection as UserMessage**

Retrieved chunks are injected as a `UserMessage` immediately before the user's question,
not as a `SystemMessage`. This is the standard RAG prompt construction: system message
sets the persona, then context appears in the conversation flow, then the question.
The context block clearly labels itself: "Relevant context from the knowledge base: [1] ..."

**March 2026 — Token counting in tool loop**

When tools are registered, the full token count is not accumulated across the tool loop
iterations. `promptTokens` and `outputTokens` are 0 in tool-augmented responses.
This is a known limitation — Phase 9 (observability) will add proper token tracking
across tool invocations.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phases 1–2 complete | — | March 2026 | LLM invocation + templates working |
| Phase 3 complete | — | March 2026 | All memory tiers functional |
| Phase 4 complete | — | March 2026 | Full RAG pipeline wired |
| Phase 5 complete | — | March 2026 | Tools + MCP registered and firing |
| Phases 6–10 | — | — | Chains, guardrails, agents, observability, security |

---

## Design Pivot — The Connect Architecture (March 2026)

During Phase 5 implementation, a more fundamental architectural question
surfaced: what is the right boundary between CafeAI itself and the services
it uses?

The answer became `cafeai-connect` — a new module category that formalises
the distinction between in-process extensions and out-of-process connections.
This is captured fully in **ADR-008**.

**What changed:**
- `cafeai-connect` added as a new optional module
- `app.connect(Connection)` added to the `CafeAI` interface
- `ConnectBridge` SPI added to `cafeai-core`
- `Locals.CONNECTIONS` key added for health check registry

**What did NOT change:**
- All existing in-process module APIs remain exactly as designed
- `cafeai-core` has zero knowledge of `cafeai-connect`
- All existing tests continue to pass

**The module boundary clarified:**
```
cafeai-core          HTTP framework + thin AI primitives       (always in-process)
cafeai-memory        Memory tiers incl. Redis client           (in-process optional)
cafeai-rag           RAG pipeline incl. embedding              (in-process optional)
cafeai-tools         Tool execution + MCP client               (in-process optional)
cafeai-connect       Out-of-process service connections        (NEW category)
```

See ADR-008 and Developer Guide Section 17 for full documentation.

---

**Phase 6 — `app.chain()` (March 2026)**

- `Chain` — named, immutable middleware pipeline; implements `Middleware` directly
- `ChainStep` — functional interface alias for `Middleware`; semantic marker for pipeline steps
- `Steps.prompt(templateName)` — renders a template, calls LLM, stores `PromptResponse` in attributes
- `Steps.prompt(Function<Request,String>)` — inline prompt builder variant
- `Steps.guard(GuardRail...)` — wraps one or more guardrails as a single chain step
- `Steps.branch(Predicate, trueBranch, falseBranch)` — conditional routing on request state
- `Steps.when(Predicate, step)` — one-sided conditional; skips step if predicate fails
- `Steps.chain(name)` — lazy forward reference to another named chain
- `Steps.transform(Function<String,String>)` — post-processes last LLM response text
- `Steps.rag()` — semantic marker for explicit RAG retrieval step
- `app.chain(name, steps...)` — registers a named chain; immutable after registration
- `app.chain(name)` — retrieves a registered chain by name; returns `null` if absent
- `Attributes.PROMPT_RESPONSE` — stores `PromptResponse` from last `Steps.prompt()` call
- `Attributes.LAST_RESPONSE_TEXT` — stores plain text from last LLM response; accessible to all downstream steps

**Phase 7 — `app.guard()` real implementations (March 2026)**

- `AbstractGuardRail` — base class; handles PRE/POST/BOTH positioning; subclasses override `checkInput()` / `checkOutput()`
- `PiiGuardRail` — five compiled regex patterns (email, phone, SSN, credit card, IPv4); `scrub()` static method for redaction mode
- `JailbreakGuardRail` — seventeen weighted patterns across role-play bypass, system prompt extraction, obfuscation, hypothetical framing; configurable confidence threshold
- `PromptInjectionGuardRail` — eight patterns; uniquely inspects both user input AND RAG-retrieved documents (indirect injection vector)
- `ToxicityGuardRail` — five weighted patterns; configurable action (BLOCK/WARN/LOG)
- `TopicBoundaryGuardRailImpl` — keyword-based allow/deny scoring; tokenises input for word-level matching
- `RegulatoryGuardRailImpl` — additive GDPR, HIPAA, FCRA, CCPA rule sets; each adds its own pattern set
- `GuardRailProvider` SPI — in `cafeai-core`; routes `GuardRail.pii()` etc. to real implementations when `cafeai-guardrails` is on classpath
- `GuardRailProviderImpl` — ServiceLoader registration; `bias()` and `hallucination()` remain stubs pending trained model integration
- `StubGuardRail.of()` made `public` — accessible from `cafeai-guardrails` for fallback stub creation
- `AbstractGuardRail.handle()` is non-final — `PromptInjectionGuardRail` overrides it to inspect RAG documents mid-pipeline

**Timeline update:**

| Milestone Event | Target | Actual | Notes |
|---|---|---|---|
| Phases 1–2 complete | — | March 2026 | LLM invocation + templates |
| Phase 3 complete | — | March 2026 | All memory tiers functional |
| Phase 4 complete | — | March 2026 | Full RAG pipeline wired |
| Phase 5 complete | — | March 2026 | Tools + MCP |
| Phase 6 complete | — | March 2026 | Named composable chains |
| Phase 7 complete | — | March 2026 | Real guardrail implementations |
| cafeai-connect pivot | — | March 2026 | Architecture decision — see ADR-008 |
| Phases 8–10 | TBD | — | Agents, observability, security |

---

**Phase 9 — Observability (March 2026)**

- `ObserveBridge` SPI in `cafeai-core` — minimal before/after intercept; opaque context object carries strategy state
- `app.observe(Object)` and `app.eval(Object)` on `CafeAI` interface and `CafeAIApp`
- `executePrompt()` instrumented with try/finally — `beforePrompt()` before LLM call, `afterPrompt()` in finally on both success and error paths
- `ObserveStrategy` — public API interface with two factory methods: `console()` and `otel()`
- `ConsoleObserveStrategy` — structured per-call output: model, tokens, latency, RAG docs retrieved, cache hit
- `OtelObserveStrategy` — OpenTelemetry span per LLM call; attributes: `cafeai.model`, `cafeai.prompt_tokens`, `cafeai.completion_tokens`, `cafeai.total_tokens`, `cafeai.latency_ms`, `cafeai.session_id`, `cafeai.rag_docs_retrieved`, `cafeai.cache_hit`, `cafeai.error`
- `ObserveBridgeImpl` — ServiceLoader implementation dispatching to the registered strategy
- `EvalHarness.defaults()` — three heuristic scores per RAG response: faithfulness, relevance, groundedness; word-overlap based, zero-cost, continuous
- `CafeAIObservabilityModule` — self-registration; `ObserveBridgeImpl` registered in `META-INF/services`
- OTel uses global `OpenTelemetry` instance — CafeAI does not manage SDK lifecycle; configure exporter externally
