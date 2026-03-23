# ROADMAP-07: Gen AI Primitives — CafeAI-Native API

**Maps to:** No Express equivalent — these are CafeAI's original contribution  
**Modules:** `cafeai-core`, `cafeai-memory`, `cafeai-rag`, `cafeai-tools`, `cafeai-agents`, `cafeai-guardrails`, `cafeai-observability`, `cafeai-security`, `cafeai-streaming`  
**ADR Reference:** ADR-003, ADR-004, ADR-005 §9  
**Depends On:** ROADMAP-01 through ROADMAP-06 (full Express foundation complete)  
**Status:** 🔴 Not Started

---

## Objective

Implement all AI-native primitives in CafeAI. These have no Express equivalent —
they are the framework's original contribution to the Java AI ecosystem.
Each primitive follows the same design philosophy as the Express foundation:
a single, guessable method name, composable with everything else, explainable
without documentation.

---

## Phases

---

### Phase 1 — `app.ai()` — LLM Provider Registration

**Goal:** Provider-agnostic LLM integration. Swap models without changing application logic.

**Module:** `cafeai-core`

#### Tasks
- [ ] Define `AiProvider` interface: `name()`, `modelId()`, `type()`
- [ ] Implement `OpenAI` factory: `gpt4o()`, `gpt4oMini()`, `o1()`, `o1Mini()`, `of(modelId)`
- [ ] Implement `Anthropic` factory: `claude35Sonnet()`, `claude3Opus()`, `claude3Haiku()`, `of(modelId)`
- [ ] Implement `Ollama` factory: `llama3()`, `mistral()`, `phi3()`, `gemma2()`, `of(modelId)`, `at(url).model(id)`
- [ ] Implement `app.ai(AiProvider provider)` — registers provider on app
- [ ] Implement `ModelRouter` for smart routing:
  - `ModelRouter.smart().simple(provider).complex(provider)`
  - Routing heuristic: token count, presence of tools, explicit complexity hint
- [ ] Implement `app.ai(ModelRouter router)` — registers routing strategy
- [ ] Provider stored in `app.local(Locals.AI_PROVIDER)`
- [ ] Langchain4j `ChatLanguageModel` wired internally per provider

#### Output
```java
app.ai(OpenAI.gpt4o())                    // single provider
app.ai(ModelRouter.smart()                // cost-aware routing
    .simple(OpenAI.gpt4oMini())
    .complex(OpenAI.gpt4o()))
app.ai(Ollama.at("http://gpu-box:11434").model("llama3"))  // local
```

#### Acceptance Criteria
- [ ] OpenAI provider makes real API calls (integration test with API key)
- [ ] Anthropic provider makes real API calls
- [ ] Ollama provider calls local Ollama instance
- [ ] `ModelRouter` routes simple prompts to `simple` model
- [ ] `ModelRouter` routes tool-using prompts to `complex` model
- [ ] Swapping provider requires no application code changes
- [ ] Unit tests with mock providers
- [ ] Integration tests (annotated `@RequiresApiKey`, skipped in CI without key)

---

### Phase 2 — `app.system()` + `app.template()`

**Goal:** System prompt management and named prompt templates.

**Module:** `cafeai-core`

#### Tasks
- [ ] Implement `app.system(String systemPrompt)` — sets application-wide system prompt
- [ ] System prompt injected as the first message in every LLM call automatically
- [ ] System prompt stored in `app.local(Locals.SYSTEM_PROMPT)`
- [ ] Implement `app.template(String name, String template)` — register named template
- [ ] Template variable interpolation: `{{variableName}}` syntax
- [ ] Implement `app.template(name)` — retrieve a registered template
- [ ] Implement `Template.render(Map<String, Object> vars)` — render with values
- [ ] Template stored in `app.local(Locals.TEMPLATES)` registry

#### Output
```java
app.system("""
    You are a helpful customer service agent for Acme Corp.
    You are concise, accurate, and empathetic.
    Never discuss competitor products.
    """)

app.template("classify",
    "Classify the following customer message into one of: {{categories}}.\nMessage: {{message}}")

// In handler:
String prompt = app.template("classify")
    .render(Map.of("categories", "billing, shipping, returns", "message", userInput))
```

#### Acceptance Criteria
- [ ] System prompt prepended to every LLM call
- [ ] Template renders with all variables substituted
- [ ] Missing variable in template throws `TemplateException` with the variable name
- [ ] Templates retrieved by name after registration
- [ ] `app.system()` called multiple times — last call wins
- [ ] Unit tests for template rendering including edge cases (missing var, extra var)

---

### Phase 3 — `app.memory()` — Tiered Context Memory

**Goal:** Full tiered memory strategy implementation per ADR-003.

**Module:** `cafeai-memory`

#### Tasks

**Rung 1 — `MemoryStrategy.inMemory()`**
- [ ] `ConcurrentHashMap<String, ConversationContext>` backing store
- [ ] `ConversationContext`: session ID, list of `ChatMessage`, token count, timestamp

**Rung 2 — `MemoryStrategy.mapped()`**
- [ ] `java.lang.foreign.MemorySegment` + `Arena` for off-heap storage
- [ ] Memory-mapped file at configurable path (default: `${java.io.tmpdir}/cafeai/sessions/`)
- [ ] `ConversationContext` serialized to `FlatBuffers` for off-heap layout
- [ ] `MemoryLayout` for structured context storage
- [ ] OS page cache management (no manual flushing needed)
- [ ] Crash recovery: context survives JVM restart

**Rung 3 — `MemoryStrategy.chronicle()`**
- [ ] Chronicle Map backing store: `ChronicleMap<String, byte[]>`
- [ ] Serialization: FlatBuffers or Chronicle's own serializer
- [ ] Configurable max entries and average entry size

**Rung 4 — `MemoryStrategy.redis(RedisConfig)`**
- [ ] Lettuce reactive Redis client
- [ ] TTL per session (configurable)
- [ ] JSON serialization of `ConversationContext`

**Rung 5 — `MemoryStrategy.hybrid()`**
- [ ] Warm tier: `mapped()` or `chronicle()`
- [ ] Cold tier: Redis
- [ ] Promotion: hot sessions stay in warm tier
- [ ] Demotion: idle sessions evicted to cold tier after configurable TTL

**Common contract:**
- [ ] `store(String sessionId, ConversationContext context)`
- [ ] `retrieve(String sessionId)` — returns `null` for unknown sessions
- [ ] `evict(String sessionId)`
- [ ] `exists(String sessionId)`
- [ ] Automatic session ID from `X-Session-Id` request header or generated UUID
- [ ] Context window trimming: when context exceeds token limit, oldest messages pruned

#### Acceptance Criteria
- [ ] All five rungs implement `MemoryStrategy` contract
- [ ] `inMemory()` context does not survive restart
- [ ] `mapped()` context survives JVM restart (crash recovery test)
- [ ] `chronicle()` handles 10,000 concurrent sessions
- [ ] `redis()` stores and retrieves correctly
- [ ] `hybrid()` promotes/demotes sessions correctly
- [ ] Context window trimming preserves most recent messages
- [ ] FFM `Arena` closed on app shutdown (no off-heap leak)
- [ ] Unit + integration tests per rung
- [ ] Integration test: `mapped()` crash recovery

---

### Phase 4 — `app.vectordb()` + `app.embed()` + `app.ingest()` + `app.rag()`

**Goal:** Full RAG pipeline — ingestion, embedding, storage, and retrieval.

**Module:** `cafeai-rag`

#### Tasks

**Vector DB Registration:**
- [ ] Implement `app.vectordb(VectorStore store)` — registers store on app
- [ ] `PgVector.connect(PgVectorConfig)` — PostgreSQL with pgvector extension
- [ ] `Chroma.local()` / `Chroma.connect(url)` — Chroma vector store
- [ ] `VectorStore.inMemory()` — in-memory for testing

**Embedding Model:**
- [ ] Implement `app.embed(EmbeddingModel model)`
- [ ] `EmbeddingModel.local()` — ONNX via Langchain4j (no external API)
- [ ] `EmbeddingModel.openAi()` — OpenAI text-embedding-ada-002
- [ ] `EmbeddingModel.openAi(String modelId)` — specific model

**Ingestion:**
- [ ] Implement `app.ingest(Source source)` — ingests a knowledge source
- [ ] `Source.pdf(String path)` — Apache Tika PDF parsing
- [ ] `Source.url(String url)` — web page content
- [ ] `Source.directory(String path)` — all files in directory (recursive)
- [ ] `Source.text(String content, String id)` — raw text
- [ ] Chunking strategy: configurable chunk size and overlap
- [ ] Embedding: chunk → embedding vector via registered model
- [ ] Upsert: store chunks + vectors in registered vector store
- [ ] Idempotent: re-ingesting same source updates without duplication

**Retrieval:**
- [ ] Implement `app.rag(Retriever retriever)` — attaches retrieval pipeline
- [ ] `Retriever.semantic(int topK)` — dense cosine similarity search
- [ ] `Retriever.hybrid(int topK)` — dense + sparse (BM25) combined
- [ ] RAG middleware: on every prompt, retrieve top-K relevant chunks
- [ ] Retrieved chunks injected into LLM context before the user message
- [ ] Retrieved chunks stored in `req.attribute(Attributes.RAG_DOCUMENTS)`

#### Output
```java
app.vectordb(PgVector.connect(PgVectorConfig.of("jdbc:postgresql://localhost/cafeai")))
app.embed(EmbeddingModel.local())
app.ingest(Source.pdf("docs/handbook.pdf"))
app.ingest(Source.directory("knowledge/"))
app.rag(Retriever.semantic(5))

// Every /chat request now has 5 relevant context chunks injected
```

#### Acceptance Criteria
- [ ] PDF ingested, chunked, embedded, stored in vector DB
- [ ] Re-ingestion of same PDF does not duplicate chunks
- [ ] Semantic retrieval returns top-K most similar chunks
- [ ] Retrieved chunks injected into LLM prompt
- [ ] `req.attribute(Attributes.RAG_DOCUMENTS)` contains retrieved docs
- [ ] `VectorStore.inMemory()` works with zero infrastructure
- [ ] Integration test: ingest PDF → ask question → verify answer uses document content
- [ ] Integration test: PgVector with Testcontainers

---

### Phase 5 — `app.tool()` + `app.mcp()`

**Goal:** Tool use and MCP server connectivity.

**Module:** `cafeai-tools`

#### Tasks

**Java Tools:**
- [ ] Define `Tool` interface / annotation-based registration via Langchain4j `@Tool`
- [ ] Implement `app.tool(Tool tool)` — register a Java method as an LLM tool
- [ ] Implement `app.tools(Tool... tools)` — register multiple tools
- [ ] Tool descriptions extracted from Javadoc / `@Tool` annotations
- [ ] Tool invocation: LLM requests tool call → CafeAI invokes Java method → result returned to LLM
- [ ] Tool errors: exceptions caught and returned to LLM as error results (not thrown)

**MCP Integration:**
- [ ] Implement `app.mcp(McpServer server)` — register MCP server connection
- [ ] `McpServer.connect(String url)` — connect to any MCP server by URL (HTTP/SSE transport)
- [ ] MCP capability discovery: query server for available tools on connection
- [ ] MCP tools exposed to LLM alongside Java tools transparently
- [ ] Trust separation: MCP tools flagged as `EXTERNAL` trust level vs Java tools as `INTERNAL`
- [ ] `McpServer` is a protocol client — NO dependency on `langchain4j-mcp`
  Implements MCP JSON-RPC protocol directly via Helidon WebClient (per design philosophy)

> **MCP Note:** CafeAI implements the MCP client protocol directly using
> Helidon's WebClient. This avoids dependency on any third-party MCP library
> and keeps CafeAI aligned with the protocol spec rather than any library's
> opinionated interpretation of it. See project philosophy in SPEC.md.

#### Output
```java
// Java tool
public class OrderLookupTool {
    @Tool("Look up an order by ID and return its status")
    public OrderStatus lookup(String orderId) {
        return orderService.find(orderId).status();
    }
}
app.tool(new OrderLookupTool(orderService))

// MCP server
app.mcp(McpServer.connect("http://github-mcp-server:3000"))
// → discovers available tools from the MCP server automatically
```

#### Acceptance Criteria
- [ ] Java `@Tool` method invoked when LLM requests it
- [ ] Tool result returned to LLM correctly
- [ ] Tool exception returned to LLM as error (no uncaught exception)
- [ ] MCP server capabilities discovered on connection
- [ ] MCP tools visible to LLM alongside Java tools
- [ ] MCP call made via Helidon WebClient (no third-party MCP library)
- [ ] `EXTERNAL` trust level logged/observable for MCP tool invocations
- [ ] Unit tests with mock LLM + mock tool
- [ ] Integration test: real MCP server (mock MCP server in tests)

---

### Phase 6 — `app.chain()` — Named Composable Pipelines

**Goal:** Named, reusable, middleware-composable AI processing pipelines.

**Module:** `cafeai-core`

#### Tasks
- [ ] Implement `app.chain(String name, ChainStep... steps)` — register a named chain
- [ ] Define `ChainStep` as a `Middleware` alias with semantic intent
- [ ] `app.chain(name)` — retrieve registered chain
- [ ] Chains accept middleware via `.use(Middleware)` — chains are middleware-composable
- [ ] Chains invocable from handlers: `app.chain("classify").run(req, res, next)`
- [ ] Chains composable with each other: a chain step can be another chain
- [ ] Built-in chain steps:
  - `Steps.prompt(String templateName)` — run a named template prompt
  - `Steps.rag()` — run retrieval (uses registered retriever)
  - `Steps.guard(GuardRail...)` — apply guardrails within chain
  - `Steps.branch(Predicate, ChainStep, ChainStep)` — conditional routing

#### Output
```java
app.chain("triage",
    Steps.guard(GuardRail.pii()),
    Steps.prompt("classify"),
    Steps.branch(
        ctx -> ctx.classification().equals("billing"),
        Steps.chain("billing-handler"),
        Steps.chain("general-handler")
    ))

app.post("/support", (req, res, next) ->
    app.chain("triage").run(req, res, next))
```

#### Acceptance Criteria
- [ ] Named chain registered and retrievable
- [ ] Chain steps execute in order
- [ ] Chain accepts middleware via `.use()`
- [ ] Chains composable: chain step is another chain
- [ ] `Steps.branch()` routes based on predicate
- [ ] Unit + integration tests

---

### Phase 7 — `app.guard()` — Guardrails as Middleware

**Goal:** Full guardrail suite — ethical, regulatory, and AI-safety concerns.

**Module:** `cafeai-guardrails`

#### Tasks
- [ ] `GuardRail.pii()` — Apache OpenNLP entity detection, mask/scrub pre and post LLM
  Entities: names, emails, phone numbers, SSNs, credit cards, addresses
- [ ] `GuardRail.jailbreak()` — classifier-based adversarial prompt detection
  Strategy: LLM self-check + pattern matching on known jailbreak patterns
- [ ] `GuardRail.promptInjection()` — detect injection in user input AND RAG-retrieved content
- [ ] `GuardRail.bias()` — bias detection in model outputs (demographic, gender, racial)
- [ ] `GuardRail.hallucination()` — factual grounding score vs RAG corpus
  Score attached to `req.attribute(Attributes.GUARDRAIL_SCORE)`
- [ ] `GuardRail.toxicity()` — toxic content detection and filtering
- [ ] `GuardRail.regulatory()` builder: `.gdpr()`, `.hipaa()`, `.fcra()`, `.ccpa()`
- [ ] `GuardRail.topicBoundary()` builder: `.allow(String...)`, `.deny(String...)`
- [ ] Each guardrail implements `Middleware` — composable with all other middleware
- [ ] Each guardrail has a `Position`: `PRE_LLM`, `POST_LLM`, or `BOTH`
- [ ] Guardrail violations: configurable action — `BLOCK`, `WARN`, `LOG`

#### Acceptance Criteria
- [ ] PII scrubbed from prompt before LLM call
- [ ] PII masked in response after LLM call
- [ ] Jailbreak attempt blocked with 400 response
- [ ] Injection in RAG document detected and flagged
- [ ] Hallucination score attached to response metadata
- [ ] Regulatory guardrail blocks HIPAA-violating content
- [ ] Topic boundary blocks off-topic requests
- [ ] Each guardrail independently testable in isolation
- [ ] Unit + integration tests per guardrail
- [ ] Integration test: full guardrail stack in sequence

---

### Phase 8 — `app.agent()` + `app.orchestrate()`

**Goal:** Agentic patterns powered by Java 21 Structured Concurrency.

**Module:** `cafeai-agents`

#### Tasks
- [ ] Define `AgentDefinition` interface
- [ ] Implement `AgentDefinition.react()` — ReAct (Reasoning + Acting) loop
  - LLM reasons → decides tool to call → calls tool → observes result → repeats
  - `maxIterations(int)` — prevents infinite loops
  - `tools(Tool...)` — tools available to this agent
- [ ] Implement `app.agent(String name, AgentDefinition def)` — register agent
- [ ] Agent execution runs in its own `StructuredTaskScope`
  - Failure in agent does not crash the parent
  - Timeout per agent (configurable)
- [ ] Implement `app.orchestrate(String name, String... agentNames)` — multi-agent topology
  - Orchestrator agent delegates to specialist agents
  - Specialist agents run in parallel via `StructuredTaskScope.ShutdownOnFailure`
  - Results joined and returned to orchestrator
- [ ] Human-in-the-loop: `AgentDefinition.withHumanApproval(ApprovalGate)`
  - Agent pauses at gate, sends approval request, waits for human confirmation
  - Timeout on approval → configurable fallback

#### Output
```java
app.agent("classifier", AgentDefinition.react()
    .tools(classifyTool)
    .maxIterations(3))

app.agent("retriever", AgentDefinition.react()
    .tools(searchTool, ragTool)
    .maxIterations(5))

app.agent("responder", AgentDefinition.react()
    .tools(draftTool)
    .maxIterations(3)
    .withHumanApproval(ApprovalGate.email("supervisor@acme.com")))

app.orchestrate("support-pipeline", "classifier", "retriever", "responder")
```

#### Acceptance Criteria
- [ ] ReAct agent executes Reason → Act → Observe loop
- [ ] `maxIterations` prevents infinite loops
- [ ] Agent failure isolated — does not crash orchestrator
- [ ] Multi-agent orchestration runs specialists in parallel
- [ ] Results joined correctly from parallel specialist agents
- [ ] Human-in-the-loop gate pauses execution
- [ ] Gate timeout triggers fallback
- [ ] Structured Concurrency used (verify with JFR/JVM tooling, not just tests)
- [ ] Unit tests with mock LLM
- [ ] Integration tests: full orchestration pipeline

---

### Phase 9 — `app.observe()` + `app.eval()`

**Goal:** Production-grade observability — every LLM call is a traced, measured, scored event.

**Module:** `cafeai-observability`

#### Tasks
- [ ] Implement `app.observe(ObserveStrategy strategy)`
- [ ] `ObserveStrategy.otel()` — OpenTelemetry trace per LLM call
  Span attributes: `model`, `prompt_tokens`, `completion_tokens`, `latency_ms`,
  `guardrail_triggered`, `cache_hit`, `rag_documents_retrieved`
- [ ] `ObserveStrategy.console()` — structured console output (development)
- [ ] Prompt versioning: each prompt tagged with a version hash
- [ ] Implement `app.eval(EvalHarness harness)`
- [ ] `EvalHarness.defaults()` includes:
  - Retrieval precision: are retrieved docs relevant?
  - Answer faithfulness: does answer match retrieved docs?
  - Answer relevance: does answer address the question?
  - Hallucination score: from `GuardRail.hallucination()`
- [ ] Eval scores attached to OTel span attributes
- [ ] Eval scores stored in `req.attribute(Attributes.EVAL_SCORES)`

#### Acceptance Criteria
- [ ] Every LLM call produces an OTel span with required attributes
- [ ] Prompt tokens and completion tokens recorded
- [ ] Guardrail triggers recorded on span
- [ ] Cache hits recorded on span
- [ ] `console()` strategy produces readable output per request
- [ ] Eval scores computed and attached to spans
- [ ] Integration test: verify OTel spans with an in-process OTLP collector
- [ ] Integration test: eval scores computed on a sample RAG pipeline

---

### Phase 10 — Security Layer

**Goal:** AI-specific security beyond guardrails.

**Module:** `cafeai-security`

#### Tasks
- [ ] `Middleware.promptInjectionDetector()` — standalone injection detector middleware
  (different from `GuardRail.promptInjection()` — this is the security layer version
  with stricter enforcement and audit logging)
- [ ] `Middleware.ragDataLeakagePrevention()` — prevents RAG from retrieving and
  exposing documents the requesting user is not authorised to see
  Requires integration with auth principal from `req.attribute(Attributes.AUTH_PRINCIPAL)`
- [ ] `Middleware.semanticCachePoisoningDetector()` — detects attempts to poison
  the semantic cache with adversarial prompts designed to corrupt future cached responses
- [ ] Audit logging: all security events logged with request ID, timestamp, event type
- [ ] `SecurityEvent` type hierarchy: `InjectionAttempt`, `DataLeakageAttempt`, `CachePoisoningAttempt`

#### Acceptance Criteria
- [ ] Injection attempt detected and blocked, security event logged
- [ ] Unauthorised RAG document access blocked
- [ ] Cache poisoning attempt detected and rejected
- [ ] All security events include request ID for traceability
- [ ] Unit tests: adversarial input examples for each detector
- [ ] Integration test: full security middleware stack

---

## Phase Dependencies

```
Phase 1  (app.ai)
    └── Phase 2  (system + templates)
    └── Phase 3  (memory)          ← independent of Phase 2
    └── Phase 4  (RAG)             ← depends on Phase 1 for embedding model
    └── Phase 5  (tools + MCP)     ← depends on Phase 1 for LLM
    └── Phase 6  (chains)          ← depends on Phases 2, 4, 7
    └── Phase 7  (guardrails)      ← depends on Phase 1 for LLM-based guards
    └── Phase 8  (agents)          ← depends on Phases 1, 5, 7
    └── Phase 9  (observability)   ← depends on all prior phases
    └── Phase 10 (security)        ← depends on Phase 4, 7
```

---

## Definition of Done

- [ ] All ten phases complete
- [ ] All acceptance criteria passing
- [ ] The `HelloCafeAI.java` example runs end-to-end with real LLM calls
- [ ] Zero Checkstyle violations across all AI modules
- [ ] Javadoc on all public AI-native API members
- [ ] MILESTONE-07.md updated to reflect completion
