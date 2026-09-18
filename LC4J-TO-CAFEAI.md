# From LangChain4j to CafeAI

> A field guide for developers who already know LangChain4j and want to know,
> precisely, what CafeAI adds — and what it doesn't.

CafeAI has spent its public-facing material hiding LangChain4j behind its own
vocabulary. That's the right default for a developer who has never touched
Gen AI. It is the wrong default for a developer who already knows LangChain4j
cold — for that reader, obscuring the substrate reads as evasion, not
polish. This document is the other half: for every CafeAI abstraction, what
LangChain4j type sits underneath it, why CafeAI exists at that point rather
than leaving you to call LangChain4j directly, and what you gain by using the
CafeAI name instead.

The method follows how anyone actually learns a framework: primitives first,
then how they compose, then the intent behind the composition, then the
intuition to move fast. Applied twice here — once to LangChain4j on its own
terms, then to CafeAI as a function of it.

Versions: LangChain4j 1.20.0, Helidon SE 4.5.5. Code references are to
`cafeai-core`, `cafeai-agents`, `cafeai-rag`, and `cafeai-sentinel` as they
exist on `main`.

---

## Part 1 — LangChain4j, compressed to what CafeAI touches

LangChain4j is a large library — 800+ integrations, a Python-parity
ambition. CafeAI uses a narrow slice of it well. This primer covers only that
slice: enough to read CafeAI's source and understand every design decision in
Part 2. It is not a LangChain4j tutorial; the project's own docs
(`docs.langchain4j.dev`) are authoritative for the parts left out.

### 1.1 `ChatModel` / `StreamingChatModel` — the model primitive

The lowest-level abstraction: one interface per provider capability.
`ChatModel.chat(List<ChatMessage>) → ChatResponse` (blocking);
`StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)`
(token-by-token, callback-driven). Every provider — `OpenAiChatModel`,
`AnthropicChatModel`, `OllamaChatModel`, `JlamaChatModel`,
`GoogleAiGeminiChatModel` — implements the same interface via its own
builder (`OpenAiChatModel.builder().apiKey(...).modelName(...).build()`).
Swap providers by swapping the builder; the calling code doesn't change.

Three builder facts matter later. The sampling and limit settings share
concepts but not names: every builder has `temperature`, but the token cap is
`maxTokens` on Anthropic and Jlama, `maxCompletionTokens` on OpenAI (newer
OpenAI models reject `max_tokens`), `numPredict` on Ollama, and
`maxOutputTokens` on Gemini. `OpenAiChatModel` also takes a `baseUrl(...)`,
which is how any OpenAI-compatible endpoint is reached without a new
integration. And a `StreamingChatResponseHandler` has an
`onPartialThinking(PartialThinking)` callback beside `onPartialResponse(String)`,
fed when the builder sets `returnThinking(true)` and the model sends a separate
reasoning channel.

This is the primitive you reach for when you want a single request/response
turn and full control over the message list. It knows nothing about
sessions, memory, or tools — those are all built on top, not inside it.

### 1.2 `ChatMessage` family — the conversation unit

`ChatMessage` is a sealed-ish hierarchy: `SystemMessage` (persona/instructions),
`UserMessage` (the human turn, possibly multimodal via `ImageContent`/
`AudioContent`/`PdfFileContent`), `AiMessage` (the model's turn, possibly
carrying `ToolExecutionRequest`s), `ToolExecutionResultMessage` (a tool's
return value fed back to the model). A conversation is just
`List<ChatMessage>`. This is the vocabulary every layer above ultimately
compiles down to.

### 1.3 `AiServices` — the declarative agent builder

The centerpiece for anything beyond a single call. You define a plain Java
interface:

```java
interface Assistant {
    String chat(String userMessage);
}
```

...and `AiServices.builder(Assistant.class).chatModel(model).build()` hands
you back a dynamic proxy. Calling `assistant.chat(...)` on that proxy runs
LangChain4j's own reasoning loop: assemble messages, call the model, detect
tool calls, execute them, feed results back, repeat until the model returns
a final answer. `AiServices` is *the* place tools, memory, guardrails, and
RAG retrieval all attach, because it's the one component that owns the whole
loop rather than a single turn:

| Builder method | What it wires in |
|---|---|
| `.chatModel(model)` | the underlying `ChatModel` |
| `.systemMessage(String)` / `@SystemMessage` on the interface | persona |
| `.tools(Object...)` | `@Tool`-annotated methods the model may call |
| `.chatMemory(ChatMemory)` / `.chatMemoryProvider(Function<Object,ChatMemory>)` | conversation history, per session |
| `.inputGuardrails(...)` / `.outputGuardrails(...)` | pre/post validation |
| `.contentRetriever(ContentRetriever)` / `.retrievalAugmentor(...)` | RAG |
| `.registerListeners(AiServiceListener...)` | lifecycle observability |

`AiServices` does not reimplement any of these — it is a *composition
point*. Each concern is its own SPI, described below.

### 1.4 `@Tool` / `@P` — the tool-calling protocol

Any plain Java method annotated `@Tool("description")` on an object passed
to `.tools(...)` becomes callable by the model; `@P("description")` documents
a parameter. LangChain4j generates the JSON tool schema from the method
signature, dispatches the model's tool-call request to the real method, and
feeds the return value back as a `ToolExecutionResultMessage`. You write a
Java method; the ReAct-style loop is LangChain4j's, not yours.

### 1.5 `ChatMemory` / `ChatMemoryStore` — the memory SPI

`ChatMemory` (commonly `MessageWindowChatMemory`, a sliding window of the
last N messages) is what `AiServices` reads and writes each turn.
`ChatMemoryStore` is the *persistence* SPI underneath it —
`getMessages(memoryId)` / `updateMessages(memoryId, messages)` /
`deleteMessages(memoryId)` — with an in-memory default. LangChain4j ships the
windowing policy; it does not ship a tiered-storage story (heap vs. SSD vs.
Redis) — that's a `ChatMemoryStore` implementation detail left to you.

### 1.6 RAG: `EmbeddingModel`, `EmbeddingStore`, `ContentRetriever`

Three separable pieces. `EmbeddingModel.embed(text) → Embedding` (a vector) —
implementations range from in-process ONNX (`AllMiniLmL6V2...Model`) to
provider APIs (`OpenAiEmbeddingModel`). `EmbeddingStore<TextSegment>` holds
vectors and answers similarity search (`ChromaEmbeddingStore`,
`PgVectorEmbeddingStore`, dozens more). `ContentRetriever` is the SPI
`AiServices.contentRetriever(...)` expects — `retrieve(Query) →
List<Content>` — LangChain4j's default implementation embeds the query and
searches an `EmbeddingStore`, but the interface is retrieval-strategy-
agnostic; anything answering that one method works, including hand-written
hybrid or reranking logic. `RetrievalAugmentor` sits one level above for
query transformation, routing, and re-ranking pipelines — CafeAI does not
use this piece.

### 1.7 `InputGuardrail` / `OutputGuardrail` — the validation SPI

`InputGuardrail.validate(UserMessage) → InputGuardrailResult` runs before the
model is called; a failure short-circuits the whole `AiServices` invocation
without spending a token. `OutputGuardrail.validate(AiMessage) →
OutputGuardrailResult` runs on the final response. LangChain4j defines the
*hook*; it ships none of the actual checks — no PII detector, no jailbreak
classifier, no regulatory catalog. Every guardrail is something you (or a
library on top) write.

### 1.8 `AiServiceListener` — lifecycle observability

`AiServiceStartedEvent` / `AiServiceCompletedEvent` / `AiServiceErrorEvent`,
delivered synchronously on the calling thread via
`.registerListeners(listener)`. This is LangChain4j's only built-in
observability primitive at the `AiServices` level — no tracing, no metrics,
no OpenTelemetry integration out of the box; those are your responsibility
to attach via a listener.

### 1.9 What LangChain4j deliberately does not give you

This is the gap analysis that motivates Part 2:

- **No HTTP server, no routing, no session/request identity.** `AiServices`
  gives you a proxy; wiring it to an endpoint, threading a session ID
  through it, and returning it as JSON is entirely your code.
- **No tiered memory infrastructure.** `ChatMemoryStore` is an SPI with an
  in-memory default; SSD-backed, off-heap, or Redis-backed storage is a
  library you bring or write.
- **No application configuration story.** No config-key catalog, no
  environment/profile resolution — construction is all explicit Java.
- **No guardrail catalog.** PII, jailbreak, GDPR/HIPAA/FCRA/CCPA,
  topic-boundary enforcement — none of it ships; you get the hook, not the
  checks.
- **No model routing.** Picking a cheap model for simple prompts and an
  expensive one for hard ones is application logic you write yourself.
- **Provider parity varies**, and with 800+ integrations, picking *which*
  five or six a Java shop should actually standardize on is itself a design
  decision LangChain4j leaves unmade.

Every one of those bullets is a section in Part 2.

---

## Part 2 — CafeAI's abstractions, mapped to what's underneath

### 2.1 Providers — `app.ai(...)`

```java
app.ai(OpenAI.of("gpt-4o"));
app.ai(Anthropic.of("claude-sonnet-4-5"));
app.ai(Ollama.of("llama3.3"));
app.ai(Jlama.of("tjake/Qwen2.5-0.5B-Instruct-JQ4"));
app.ai(Nvidia.of("moonshotai/kimi-k3").withReasoningEffort("max"));
app.ai(Anthropic.of("claude-sonnet-4-5").withTemperature(0).withMaxTokens(1024));
app.ai(ModelRouter.smart().simple(OpenAI.of("gpt-4o-mini")).complex(OpenAI.of("gpt-4o")));
```

Underneath: `io.cafeai.core.internal.LangchainBridge` (internal, never
referenced by application code) is a `switch` over `AiProvider.type()` that
calls the matching LangChain4j builder — `OpenAiChatModel.builder()`,
`AnthropicChatModel.builder()`, `OllamaChatModel.builder()`,
`JlamaChatModel.builder()` (plus the `Streaming*` variant of each). Nothing
more happens in the `default` case; unsupported providers throw and tell you
to "implement `AiProvider` and wire Langchain4j manually" — the door is
explicitly left open rather than papered over.

`Gemini` and `Nvidia` are not in that switch. Both reach LangChain4j through
the `ChatModelAccess` seam described below: `Gemini` builds a
`GoogleAiGeminiChatModel`, and `Nvidia` builds an `OpenAiChatModel` /
`OpenAiStreamingChatModel` with `baseUrl("https://integrate.api.nvidia.com/v1")`,
because NVIDIA's hosted catalog speaks the OpenAI wire protocol. That is the
whole integration — no new dependency, no new LangChain4j module. Both are
tagged `ProviderType.CUSTOM`, a value the bridge never sees because the seam
intercepts first.

**Why the indirection exists, beyond "use LangChain4j directly":**

- **One `AiProvider` vocabulary across six backends** (`OpenAI`,
  `Anthropic`, `Ollama`, `Jlama`, and `Gemini` and `Nvidia` via
  `ChatModelAccess`), so application code names a model once and swaps
  providers by changing one line — this is genuinely what LangChain4j already
  gives you at the builder level; CafeAI's contribution is collapsing six
  different builder shapes into one factory-method shape (`Provider.of(id)`).
- **Actionable failure on a missing API key.** `resolveApiKey` throws a
  message naming the exact environment variable and offering the two local
  no-key alternatives (Ollama, Jlama) — LangChain4j's builders throw
  whatever the HTTP client throws.
- **Caching per provider.** `modelFor()` caches the built model keyed on the
  provider *itself*, since LangChain4j model objects are thread-safe but
  non-trivial to construct — a concrete, unglamorous but load-bearing
  addition. The built-in providers are records, so two providers compare equal
  only if every field does: the same model at two temperatures, on two base
  URLs, or with two timeouts are distinct cache entries.
- **`ModelRouter`** — cost-based routing by input length — **has no
  LangChain4j equivalent at all.** `ModelRouter implements AiProvider` and
  presents as its `complexModel` for capability checks; the actual routing
  decision happens in `CafeAIApp` when it detects a `ModelRouter` instance.
  This is pure CafeAI, filling a gap LangChain4j leaves open by design.
- **Two seams** (`ChatModelAccess`, `StreamingChatModelAccess`) let a
  provider hand back a pre-built model directly, bypassing environment
  lookups. They are how CafeAI's own test suite (and yours) mocks a model
  without touching real credentials, and — not just a test hook — how `Gemini`
  and `Nvidia` reach LangChain4j at all. `Nvidia` implements both, because a
  streamed vision call asks the bridge for a `StreamingChatModel`.
- **Per-provider tuning: `withTemperature`, `withMaxTokens`, `withTimeout`.**
  Each returns an immutable copy of the provider
  (`Anthropic.of(id).withTemperature(0)`), and lives on the `AiProvider`
  interface with a default that throws `UnsupportedOperationException` — so a
  custom provider that doesn't implement it fails loudly instead of silently
  ignoring your setting, and so does `ModelRouter` (set them on the models it
  routes between). An unset value is never passed to the builder, so a provider
  you don't tune builds exactly as it always did. What CafeAI adds over the
  builders is one name for what they spell differently:

  | Provider | `withTemperature` | `withMaxTokens` | `withTimeout` |
  |---|---|---|---|
  | `OpenAI` | `.temperature(Double)` | `.maxCompletionTokens(Integer)` | `.timeout(Duration)` |
  | `Anthropic` | `.temperature(Double)` | `.maxTokens(Integer)` | `.timeout(Duration)` |
  | `Ollama` | `.temperature(Double)` | `.numPredict(Integer)` | `.timeout(Duration)` |
  | `Gemini` | `.temperature(Double)` | `.maxOutputTokens(Integer)` | `.timeout(Duration)` |
  | `Nvidia` | `.temperature(Double)` | `.maxCompletionTokens(Integer)` | `.timeout(Duration)` |
  | `Jlama` | `.temperature(Float)` | `.maxTokens(Integer)` | refused — in-process, no call to time out |

  `withTimeout` overrides `cafeai.chat.timeout` (§2.8) for that one provider,
  which is the right granularity: a classifier and a reasoning model that takes
  minutes to respond do not share a sensible limit. For a streamed call the
  limit covers the wait for the response to *start*, not the gap between tokens
  once it has: LangChain4j's JDK HTTP client sends a streamed request with
  `sendAsync` and an `InputStream` body handler, so the JDK's request timeout
  fires when the response headers haven't arrived. That is exactly how the
  60-second default failed on a slow reasoning model before this existed. `Nvidia` alone falls back to five minutes when unset rather than
  to `cafeai.chat.timeout` — a hosted reasoning model took about two minutes to
  begin responding in `NvidiaVisionExample`, well past the 60-second default.
- **Provider-specific settings stay provider-specific.** `Nvidia` also has
  `withReasoningEffort("max")`, which maps to `.reasoningEffort(String)` on the
  OpenAI builder; Anthropic expresses "how hard to think" as
  `thinkingType(...)` plus `thinkingBudgetTokens(...)`, a different mechanism,
  so it is not on `AiProvider`. Its return type is the
  public `Nvidia.NvidiaProvider` rather than `AiProvider`, and it overrides the
  three shared `with...` methods covariantly, so they chain in any order.

### 2.2 The plain call — `app.prompt()` / `.vision()` / `.audio()` / `.synthesise()`

```java
String answer = app.prompt("...").call().text();
InvoiceData d  = app.vision("Extract invoice data.", pdfBytes, "application/pdf")
                     .returning(InvoiceData.class).call(InvoiceData.class);
```

Underneath: `CafeAIApp.executePrompt()` (and its vision/audio siblings) build
a `List<ChatMessage>` by hand (`SystemMessage`/`UserMessage`, with
`VisionMessageBuilder`/`AudioMessageBuilder` assembling multimodal content
objects) and call `ChatModel.chat(...)` or `StreamingChatModel.chat(...)`
**directly — not through `AiServices`.** This is a deliberate, load-bearing
choice, not an oversight: `AiServices` is shaped for a declarative,
interface-defined agent with a persistent identity; a one-off `app.prompt()`
call has neither. Reaching for the low-level `ChatModel` primitive here is
the same judgment call an LC4J-fluent developer would make by hand — CafeAI
just makes it consistently and wraps the plumbing around it.

**What that plumbing is, concretely** — every one of these runs on *every*
`app.prompt()`/`.vision()`/`.audio()` call, hand-orchestrated in
`CafeAIApp`, against CafeAI's own interfaces (not LangChain4j's):

1. `GuardRail`s at `PRE_LLM`/`BOTH` position checked against the raw prompt text.
2. Session history loaded from the registered `MemoryStrategy`, if a session id is present.
3. RAG retrieval run — `Retriever.retrieve(query, embeddingModel, vectorStore)` — and the result spliced into context, bracketed by `ObserveBridge.beforeRetrieval`/`afterRetrieval`.
4. The LLM call itself, bracketed by `ObserveBridge.beforePrompt`/`afterPrompt`.
5. `GuardRail`s at `POST_LLM`/`BOTH` checked against the assembled response text.
6. The exchange persisted back to `MemoryStrategy`, if a session id is present.

Steps 1 and 5 are enforced *by the engine*, on the text the model actually sees — not by
HTTP middleware. (An earlier version of this guide, and the code, applied them on `vision` and
`audio` but not on `app.prompt()`; the middleware form of a guardrail runs after the route
handler has already responded, so it could never stop an output.) A guardrail's `Action`
decides the outcome: `BLOCK` throws `GuardRailViolationException` before any model call is made
(and replaces a bad *output* with a refusal), `WARN`/`LOG` record and continue. A streamed
response can't be retracted once tokens are sent, so for `.stream()` step 5 gates what is
remembered, not what the client already received.

None of steps 1, 2, 3, or 6 exist in LangChain4j at the `ChatModel` level —
they're `AiServices`-only concerns there. CafeAI's value here is applying
them to a *single free-form call*, which is most of what a production prompt
pipeline actually is (see the middleware diagram in `README.md`).

**The semantic cache** (`app.cache(...)`) has no LangChain4j equivalent either. It builds on
CafeAI's own `EmbeddingProvider` (so any LangChain4j `EmbeddingModel` works through
`EmbeddingProvider.of(model)`), and sits in the pipeline *after* step 1 and *before* RAG: a request a
guardrail blocked never reaches it, only clean, prompt-only answers (no session, no RAG) are stored,
and a hit is re-screened by step 5 before it is served. The reasoning, and what it does not
protect against, is in `docs/adr/ADR-013-semantic-cache-and-poisoning-defences.md`.

Also here, and genuinely without an LC4J equivalent: **structured output**
via `.returning(Class)` — a JSON-schema hint built from the target record
(`SchemaHintBuilder`) is appended to the prompt, and the response is
deserialized (`ResponseDeserializer`) — LangChain4j has no analogous
"structured output for a raw `ChatModel` call" feature; its structured
output story is tied to `AiServices` return types.

**Reasoning tokens — `.onThinking(...)`.** A reasoning model can stream its
thinking in a channel separate from its answer, and for a long request that is
the only sign of life. `PromptRequest` and `VisionRequest` take
`.onThinking(Consumer<String>)`:

```java
app.vision("What is in this image?", bytes, "image/jpeg")
   .onThinking(System.err::print)
   .stream(System.out::print);
```

Underneath, this is the LangChain4j hook from §1.1 and nothing more: the two
streaming executors (`executePromptStream`, `executeVisionStream`) override
`StreamingChatResponseHandler.onPartialThinking(PartialThinking)` beside
`onPartialResponse` and forward `thinking.text()` to your consumer. It is a
side channel *by construction* — the assembled text that feeds POST_LLM
guardrails, session memory, and `LLM_RESPONSE_TEXT` is built only from
`onPartialResponse`, so reasoning can't leak into any of them. The
`stream(Consumer<String>)` and `Flow.Publisher<String>` signatures are
unchanged, which is why it is a fluent setter and not a second consumer
argument. Two limits: only `Nvidia` builds its streaming model with
`returnThinking(true)`, so the callback never fires for the other built-ins
(their builders support it; CafeAI simply doesn't request it yet), and it
applies to `.stream(...)` only, not `.call()`.

### 2.3 Agents — `app.agent(...)`

```java
app.agent("loan", LoanAgent.class)
   .system("...")
   .tool(new CreditCheckTool())
   .memory(MemoryStrategy.inMemory())
   .guard(GuardRail.jailbreak())
   .configure(b -> b /* full AiServices.Builder escape hatch */);

var agent = app.agent("loan", LoanAgent.class, sessionId);
```

Underneath: `cafeai-agents`' `AgentRegistry.build()` (the sole implementation
of the `AgentBridge` SPI `cafeai-core` calls through) is, almost line for
line, an `AiServices.builder()` call. This is the one place in CafeAI that
hands full control to LangChain4j's own reasoning loop — no re-implementation
of ReAct, tool dispatch, or the MCP client exists anywhere in the codebase.

| `AgentConfig` / app-level setting | `AiServices` call | Adapter class |
|---|---|---|
| `.model(provider)` or `app.ai(...)` default | `.chatModel(model)` | `LangchainBridge.modelFor()` (§2.1) |
| `.system(prompt)` | `.systemMessage(...)` | — |
| `.tool(obj)` | `.tools(...)` | — |
| `.guard(rail)`, split by `GuardRail.Position` | `.inputGuardrails(...)` / `.outputGuardrails(...)` | `GuardrailAdapters.asInput/asOutput` |
| `.rag(retriever)` (or app-level `app.rag(...)`) + `app.vectordb(...)`/`app.embed(...)` | `.contentRetriever(...)` | `CafeAiContentRetriever` |
| `.memory(strategy)` (or app-level `app.memory(...)`) | `.chatMemory(MessageWindowChatMemory...)` | `CafeAiChatMemoryStore` |
| `app.observe(...)` | `.registerListeners(...)` | `AgentObserveListener.forAgent()` |
| `.configure(consumer)` | runs last, raw access to the still-open builder | — |

Each adapter is small and single-purpose — none exceeds ~45 lines, none adds
state beyond what it's bridging:

- **`CafeAiChatMemoryStore implements ChatMemoryStore`** — `getMessages` calls
  `MemoryStrategy.retrieve()` and maps CafeAI's role strings to
  `SystemMessage`/`AiMessage`/`UserMessage`; `updateMessages` does the
  reverse into `MemoryStrategy.store()`; `deleteMessages` calls
  `MemoryStrategy.evict()`. Three methods, three calls.
- **`GuardrailAdapters.asInput/asOutput`** — each wraps a `GuardRail` in an
  anonymous `InputGuardrail`/`OutputGuardrail` whose `validate()` calls
  `GuardRail.checkInput()`/`checkOutput()` and translates
  `OutputCheckResult` to `success()`/`failure(reason)`.
- **`CafeAiContentRetriever implements ContentRetriever`** — `retrieve(Query)`
  calls `Retriever.retrieve(query.text(), embeddingModel, vectorStore)` (the
  exact same call `app.prompt()` makes in §2.2, step 3) and wraps each
  `RagDocument` as a LangChain4j `Content`.
- **`AgentObserveListener.forAgent()`** — three `AiServiceListener`s
  (started/completed/error) that log and, if an `ObserveBridge` is present,
  call `beforeAgent`/`afterAgent` — the same `ObserveBridge` SPI §2.2 uses,
  correlated via a `ThreadLocal` since the listener and the calling method
  run synchronously on the same thread.

**Why this layer exists, beyond calling `AiServices.builder()` yourself:**
not to add capability LangChain4j lacks — it deliberately adds none — but to
let an agent inherit the **same `GuardRail`/`MemoryStrategy`/`Retriever`/
`ObserveStrategy` instances already configured on the app**, and to give the
resolved proxy an **HTTP session identity** (`app.agent(name, Type.class,
sessionId)` resolves — and caches — per `name + "::" + sessionId`, so a route
handler doesn't manage `AiServices` proxy lifetime by hand). The `.configure()`
escape hatch exists precisely because CafeAI's own javadoc is candid about
what it *doesn't* abstract: per-session memory providers, RAG augmentors,
moderation models, dynamic system-prompt providers, output parsers — for all
of that, you get the live `AiServices<T>` builder, post-CafeAI-config,
pre-`.build()`.

### 2.4 Memory — `app.memory(...)`

```java
app.memory(MemoryStrategy.inMemory());   // Rung 1 — zero deps
app.memory(MemoryStrategy.mapped());     // Rung 2 — SSD-backed FFM (cafeai-memory)
app.memory(MemoryStrategy.redis(cfg));   // Rung 3 — Lettuce (cafeai-memory)
```

`MemoryStrategy` (`store`/`retrieve`/`evict`/`exists` against a
`ConversationContext`) has **zero LangChain4j imports anywhere in its
implementation** — `cafeai-memory`'s FFM/Redis/hybrid tiers are
entirely bespoke, because LangChain4j has no tiered-storage story to
delegate to (§1.9). LangChain4j only enters the picture when a
`MemoryStrategy` is handed to an *agent* — at that point `cafeai-agents`
backs a `MessageWindowChatMemory` with a `CafeAiChatMemoryStore` wrapping it
(§2.3). The plain `app.prompt()` path (§2.2) calls `MemoryStrategy` directly
and never touches `ChatMemory`/`ChatMemoryStore` at all.

**Why it exists:** the tiered ladder (heap → FFM/SSD → Redis →
hybrid) is a genuine capability gap in LangChain4j, not a rename of
something it already provides. The one thing CafeAI guarantees is that
*whichever tier you pick, both a plain prompt and an agent see the same
conversation* — same `MemoryStrategy` instance, two different runtime paths.

### 2.5 RAG — `app.vectordb()` / `.embed()` / `.rag()` / `.ingest()`

```java
app.embed(EmbeddingProvider.local());               // ONNX, in-process
app.vectordb(VectorStore.inMemory());                // or .chroma(...) / .pgVector(...)
app.rag(Retriever.semantic(5));                       // or .hybrid(5)
app.ingest(Source.pdf(Path.of("handbook.pdf")));
```

Split cleanly by whether LangChain4j has the piece:

- **`EmbeddingProvider.local()`** wraps LangChain4j's in-process
  `AllMiniLmL6V2QuantizedEmbeddingModel` (ONNX, no API key). **`.openAi(id)`**
  wraps `OpenAiEmbeddingModel`. This is a thin rename for ergonomics — the
  real value is CafeAI's refusal to bake in a default OpenAI embedding model
  id, since OpenAI has already retired one lineup
  (`text-embedding-ada-002`) and a framework-supplied constant becomes a
  landmine the day the next one retires (see `[[no-named-model-constants]]`
  in the project's own convention — the same reasoning that killed
  `claude35Sonnet()`-style factories applies here).
- **`VectorStore.chroma(...)`/`.pgVector(...)`** wrap LangChain4j's
  `ChromaEmbeddingStore`/`PgVectorEmbeddingStore` behind CafeAI's own
  `VectorStore` interface (`upsert`/`search`/`exists`/`deleteBySource`/`count`).
  **`VectorStore.inMemory()`** is bespoke — brute-force cosine similarity,
  zero dependencies, explicitly capped at "appropriate up to ~10,000 chunks"
  in its own javadoc.
- **`Retriever.semantic(k)`** is a one-line wrap of `embed` + `search` — the
  same shape as LangChain4j's default `ContentRetriever` behavior.
  **`Retriever.hybrid(k)`** is **entirely bespoke** — dense cosine fused with
  a hand-written BM25-style sparse score over the top `4×k` dense
  candidates, min-max normalized and weighted (`denseWeight`/`sparseWeight`).
  LangChain4j has no built-in hybrid retriever; this is real, non-trivial
  CafeAI code, not a rename.
- **Only inside `app.agent(...)`**, `CafeAiContentRetriever` (§2.3) adapts
  whichever `Retriever` is active into LangChain4j's `ContentRetriever` SPI.
  The plain `app.prompt()` path calls `Retriever.retrieve()` directly.

### 2.6 Guardrails — `app.guard(...)`

```java
app.guard(GuardRail.pii());
app.guard(GuardRail.jailbreak());
app.guard(GuardRail.regulatory().gdpr().hipaa());
app.guard(GuardRail.topicBoundary().allow("insurance").deny("competitor pricing"));
```

`GuardRail` (`cafeai-guardrails`: PII via regex patterns, jailbreak,
prompt-injection, secrets/credentials, toxicity, a GDPR/HIPAA/FCRA/CCPA/
ECOA/fair-housing catalog, topic-boundary allow/deny) has **zero
LangChain4j imports** — none of this exists in LangChain4j at all (§1.9);
`InputGuardrail`/`OutputGuardrail` are an empty hook there. Two additional
things worth knowing:

- **The engine enforces, through `checkInput`/`checkOutput`.** A guardrail
  registered once is applied by `CafeAIApp` to every `app.prompt()`, `.vision()`
  and `.audio()` call (§2.2), on the text the model actually sees. `GuardRail`
  also `extends Middleware`, so it is registered as a Helidon HTTP filter too —
  but that form runs *after* the route handler and only sees the request body,
  so it cannot stop an output; the engine path is what enforces.
- **What CafeAI adds that LangChain4j has no counterpart for:** text is
  normalised (`TextNormalizer`) before a pattern sees it, so full-width,
  zero-width, homoglyph and accent tricks do not hide a phrase;
  `GuardRail.promptLeak(system)` checks the model's *response* for its own
  system prompt (needs no module); `GuardRail.secrets()` finds credentials in
  and out; and `promptInjection()` also screens each **retrieved RAG document**
  before it enters the context — a hit is dropped, not the whole request. That
  last one is `app.prompt()` only: an agent's retrieval belongs to
  LangChain4j's `AiServices`, which CafeAI does not intercept.
- **Inside `app.agent(...)`**, `GuardrailAdapters` (§2.3) wraps a `GuardRail` as
  LangChain4j's `InputGuardrail`/`OutputGuardrail` so `AiServices` applies it
  natively, before its own reasoning loop starts. Both paths honour the
  guardrail's `Action` (`BLOCK` fails the call; `WARN`/`LOG` record and go on).
- **One guardrail is a LangChain4j type on purpose.** `GuardRail.moderation(model)`
  takes LangChain4j's own `ModerationModel` — any provider's — and adds no
  wrapper: `OpenAI.moderation(id)` returns the LangChain4j type itself. A
  moderation *model* is the answer to "the pattern list is always one rephrasing
  behind", and it is the one place §2.6's "zero LangChain4j imports" is
  deliberately not true. It fails closed if its API call fails, and reports only
  what `Moderation` carries — flagged or not; no categories or scores.
- **Absence fails loudly:** every pattern-based `GuardRail.xxx()` factory checks
  `ServiceLoader` for a `GuardRailProvider` (from `cafeai-guardrails`) and
  throws `GuardRailModuleNotFoundException` if the module isn't on the
  classpath, naming the coordinate to add. It does not fall back to a
  pass-through guardrail — that would look like protection and be none. See §3.1.

### 2.7 Observability — `app.observe(...)`

```java
app.observe(ObserveStrategy.console());
app.observe(ObserveStrategy.otel());
```

`ObserveStrategy` (console logging, OpenTelemetry) is bespoke — zero
LangChain4j imports. It's bridged to `CafeAIApp`'s call sites via the
`ObserveBridge` SPI, whose `before*`/`after*` methods bracket every
prompt/vision/audio/synthesis/retrieval call (§2.2) *and*, separately, every
agent invocation via `AgentObserveListener`'s three `AiServiceListener`s
(§2.3) — the only point where CafeAI touches LangChain4j's own
observability primitive at all. One `ObserveStrategy`, two wiring paths,
same as memory and guardrails.

### 2.8 Configuration — `ConfigKey` / `AppConfig`

```java
static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
    "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60), "...");
Duration timeout = AppConfig.load().get(CHAT_TIMEOUT);
```

No LangChain4j equivalent exists — LangChain4j's builders take explicit
Java values; there is no key catalog, no environment/profile resolution
layer. This is CafeAI infrastructure end to end, resolved via Helidon Config
when `cafeai-config` is present and coded defaults otherwise. Notably, this
*is* how `LangchainBridge.CHAT_TIMEOUT` and `AgentRegistry.MEMORY_WINDOW`
make the LangChain4j builder calls in §2.1/§2.3 configurable instead of
hardcoded. A provider's own `withTimeout(Duration)` (§2.1) wins over
`CHAT_TIMEOUT` for that model; the key is the application-wide fallback.

### 2.9 The HTTP layer — `app.get/.post/.use/.listen`

No LangChain4j equivalent — LangChain4j is not a server library. This is the
single largest thing CafeAI adds that has nothing to do with wrapping
LangChain4j at all: Express-shaped routing and middleware over Helidon SE.
It matters for this document only insofar as it's *where* the AI primitives
above get invoked from — a route handler calling `app.prompt(...)` or
`app.agent(...)` inside `(req, res, next) -> {...}`.

### 2.10 `cafeai-sentinel` — the one place LangChain4j is used nearly raw

```java
interface ClusterInvestigator {
    @SystemMessage(SYSTEM_PROMPT)
    Investigation investigate(@UserMessage String incidentBrief);
}

class KubeTools {
    @Tool("...") public String getPodLogs(@P("...") String pod, ...) { ... }
}
```

`ClusterInvestigator` is a LangChain4j `AiServices` interface using its
*declarative* annotation style (`@SystemMessage`/`@UserMessage` from
`dev.langchain4j.service`, distinct from the `SystemMessage`/`UserMessage`
*data* types in §1.2) almost without CafeAI mediation. It has two supported
wiring paths, both intentional:

- **Inside a CafeAI app:** `app.agent("cluster-investigator",
  ClusterInvestigator.class).tool(new KubeTools(...))` — the normal §2.3
  path, through `AgentRegistry`.
- **Standalone, no CafeAI app at all:** `Investigator.using(ChatModel,
  tools...)` builds `AiServices.builder(ClusterInvestigator.class)` directly.
  This is documented as the path "for callers not running a CafeAI app" —
  not an inconsistency, but the same "graceful, undiminished standalone
  mode" pattern described in §3.2, applied to a whole capstone-adjacent
  module rather than one config value.

### 2.11 Reaching LangChain4j directly

CafeAI does not try to re-expose LangChain4j's surface — it is large, it moves,
and you are better served by its own documentation. Where a LangChain4j type is
the natural currency, CafeAI accepts *that type* instead of a wrapper. Every
place that happens:

| You want to... | LangChain4j type | How |
|---|---|---|
| Use a model CafeAI has no provider for | `ChatModel`, `StreamingChatModel` | Implement `AiProvider` plus `LangchainBridge.ChatModelAccess` (and `StreamingChatModelAccess`). `Gemini` and `Nvidia` are built exactly this way, in one file each |
| Embed with any model (for RAG, or the semantic cache) | `EmbeddingModel` | `EmbeddingProvider.of(model)` wraps any LangChain4j embedding model — Ollama, Bedrock, Vertex, ONNX — without an adapter of yours |
| Moderate content with a model | `ModerationModel` | `GuardRail.moderation(model)` accepts any provider's; `OpenAI.moderation(id)` returns the LangChain4j type |
| Tune an agent's builder | `AiServices<T>` | `app.agent(...).configure(b -> b.moderationModel(m))` hands you the live builder: `moderationModel`, `toolProvider`, and the rest of it |
| Use the agent itself | LangChain4j's `AiService` proxy | `app.agent(...)` returns it unwrapped — no CafeAI type in between |
| Run an investigation with no CafeAI app | `ChatModel` | `Investigator.using(chatModel, tools...)` |

The agent-side moderation hook is tested, not just documented: LangChain4j's
`@Moderate` on an agent method, with `configure(b -> b.moderationModel(m))`,
throws LangChain4j's own `ModerationException` on a flagged input.

---

## Part 3 — Two patterns that repeat everywhere

Once you've read Part 2 end to end, two shapes recur often enough to name
explicitly — recognizing them is most of what makes the rest of CafeAI
predictable.

### 3.1 Graceful degradation via `ServiceLoader`

`GuardRail.pii()`, `MemoryStrategy.mapped()`, `VectorStore.chroma(...)`,
`EmbeddingProvider.local()` — every optional capability is *declared* in
`cafeai-core` with no dependency on its real implementation, and *resolved*
at call time via `ServiceLoader.load(XProvider.class).findFirst()`. Absent
the implementing module, you get one honest outcome: an
`XModuleNotFoundException` naming the exact Gradle/Maven coordinate to add
(guardrails, memory rungs 2–4, Chroma/PgVector, embeddings — none has a safe
silent fallback; a guardrail that passes everything through is worse than none).
**Application code compiles and reads identically
whether or not the module is present** — you write against the full
vocabulary from day one and add jars only when you need real enforcement.
This is the mechanism behind the "incremental adoption ladder" in
`README.md`, not just a marketing table.

### 3.2 The standalone escape hatch, at every layer

Three independent instances of the same idea: `ConfigKey`s resolve to their
coded default with `cafeai-config` absent, live config with it present
(§2.8). `Investigator.using(ChatModel, tools...)` builds the exact same
agent with zero CafeAI app around it (§2.10). `AgentConfig.configure(Consumer
<AiServices<T>>)` gives you the live builder inside a CafeAI app for
whatever CafeAI chose not to abstract (§2.3). None of these are back doors
bolted on for edge cases — they're declared alongside the primary path in
the same javadoc, because the design premise is that CafeAI adds to
LangChain4j, never gates it.

### 3.3 The central fact: two engines, one interface, for four concerns

This is the one thing worth internalizing above all else. Memory,
guardrails, RAG retrieval, and observability each have exactly **one**
CafeAI interface (`MemoryStrategy`, `GuardRail`, `Retriever`,
`ObserveStrategy`/`ObserveBridge`) — and **two** runtime engines that honor
it:

```
                     app.prompt() / .vision() / .audio()
                              (CafeAIApp — imperative)
                                        │
   MemoryStrategy ◄────────┬───────────┼───────────┬────────► ObserveBridge
   .retrieve()/.store()    │      direct calls      │      .before*/.after*
                            │                         │
   Retriever.retrieve() ◄──┘                         └──► GuardRail
                                                          .checkInput/Output

                          ── vs. ──

                          app.agent(name, T.class)
                        (AgentRegistry → AiServices)
                                        │
   MemoryStrategy ◄── CafeAiChatMemoryStore ──► ChatMemoryStore (LC4J)
   Retriever      ◄── CafeAiContentRetriever ──► ContentRetriever (LC4J)
   GuardRail      ◄── GuardrailAdapters ──► InputGuardrail/OutputGuardrail (LC4J)
   ObserveBridge  ◄── AgentObserveListener ──► AiServiceListener (LC4J)
```

The imperative path exists because `AiServices`'s single request/response
shape cannot express what a raw prompt call needs: RAG context injected
before message assembly, `POST_LLM` guardrails wrapping the *final* text of
a tool-calling loop, streaming tokens gated into memory only after the
guardrail has seen the assembled whole. The delegated path exists because
reimplementing `AiServices`'s reasoning loop by hand would be strictly worse
than adapting into it. **This is the actual value proposition** — not "a
LangChain4j reskin," but "configure a guardrail, a memory tier, a retriever,
or an observer exactly once, and get it honored whether you called
`app.prompt()` directly or handed a typed interface to `app.agent()`."

---

## Part 4 — Rapid-productivity playbook

### 4.1 Decision: `app.prompt()` vs. `app.agent()`

The same judgment call LangChain4j asks you to make between a raw
`ChatModel` call and an `AiServices` proxy, phrased in CafeAI's terms:

| Use `app.prompt()`/`.vision()`/`.audio()` when... | Use `app.agent(name, Interface.class)` when... |
|---|---|
| One free-form turn, no persistent tool-use loop | The model needs to decide, itself, whether/which tools to call |
| You want `.returning(Class)` structured output on a raw call | You have a stable, typed operation worth naming as an interface |
| Streaming a single response to an HTTP client | Multi-step reasoning where LangChain4j's loop should own iteration |

Both share the same `GuardRail`/`MemoryStrategy`/`Retriever`/`ObserveStrategy`
configuration (§3.3) — switching between them mid-project changes *how* a
concern is enforced, never *whether* it is.

### 4.2 Rosetta stone — LangChain4j idiom → CafeAI equivalent

| If you'd write this in bare LangChain4j... | ...write this in CafeAI |
|---|---|
| `OpenAiChatModel.builder().apiKey(k).modelName(id).build()` | `app.ai(OpenAI.of(id))` |
| `OpenAiChatModel.builder().baseUrl("https://integrate.api.nvidia.com/v1").apiKey(k)...` (any OpenAI-compatible endpoint) | `app.ai(Nvidia.of("vendor/model"))` for NVIDIA's catalog |
| `.temperature(0.2).maxTokens(1024).timeout(d)` — names differ per provider (§2.1 table) | `provider.withTemperature(0.2).withMaxTokens(1024).withTimeout(d)`, same names everywhere |
| Overriding `onPartialThinking` on a `StreamingChatResponseHandler` | `.onThinking(consumer)` on `app.prompt(...)` / `app.vision(...)`, before `.stream(...)` |
| `AiServices.builder(X.class).chatModel(m).build()` | `app.agent("x", X.class).model(provider)` then `app.agent("x", X.class, sessionId)` |
| `.chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))` | `app.agent(...).memory(MemoryStrategy.inMemory())` (or `.mapped()`/`.redis(cfg)` for persistence LC4J doesn't offer) |
| Writing your own `InputGuardrail` for PII | `app.guard(GuardRail.pii())` (real enforcement needs `cafeai-guardrails`) |
| `.contentRetriever(myRetriever)` backed by an `EmbeddingStore` you built | `app.vectordb(...); app.embed(...); app.rag(Retriever.semantic(5))`, inherited by every agent automatically |
| `.registerListeners(myOtelListener)` | `app.observe(ObserveStrategy.otel())`, applied to agents *and* plain prompts |
| Hand-rolling a Spark/Javalin/Helidon route that calls the proxy | `app.post(path, (req, res, next) -> { var agent = app.agent(...); res.json(...); })` |
| `McpToolProvider` wired into the builder | `.configure(b -> b.toolProvider(mcpProvider))` — no first-class CafeAI wrapper yet, see `ROADMAP-12` |

### 4.3 What buys you what, per adoption-ladder rung

Tied to the ladder in `README.md` — read right-to-left as "what LangChain4j
alone does *not* hand you at this rung":

| Rung | CafeAI capability | Gap filled beyond bare LangChain4j |
|---|---|---|
| 1–2 | Plain call, templates | HTTP identity, config-driven timeouts, actionable key errors |
| 3 | Memory | Tiered storage (FFM/Redis) — no LC4J equivalent |
| 4 | RAG | `VectorStore`/`EmbeddingProvider` factory ergonomics + bespoke hybrid retrieval |
| 5–7 | Tools, guardrails, agents | A guardrail *catalog*; one config honored by both call styles (§3.3) |
| 8 | Observability + evals | `ObserveBridge` dual-wiring; `EvalHarness` (no LC4J equivalent) |
| 9–10 | Streaming, security | SSE/WebSocket backpressure; injection checks (no LC4J equivalent) |

---

## Part 5 — Where's the fat? (the audit)

The premise going in was explicit: if this exercise surfaced unnecessary or
convoluted CafeAI abstraction, trim it. Having traced every LangChain4j
touchpoint in the codebase (Part 2) end to end, here's the honest result.

**The agent-binding layer (`cafeai-agents`) is already minimal.** Four
adapter classes, 20–45 lines each, each translating exactly one CafeAI
interface method to exactly one LangChain4j SPI method, with no
intermediate state, no extra indirection layer, and no wrapper type around
the returned `AiService` proxy (`AgentBridge.resolve()`'s own javadoc: *"No
wrapper. `resolve` returns LangChain4j's own `AiService` proxy."*). There is
nothing here worth cutting — cutting further would mean not bridging the
concern at all.

**Nothing found in Part 2 is a rename masquerading as an abstraction.**
Every CafeAI type either (a) wraps a LangChain4j primitive to unify a
vocabulary across providers with a genuine, named payoff (§2.1's provider
factories, §2.5's `Chroma`/`PgVector` wraps), or (b) fills a documented gap
LangChain4j leaves open with real, non-trivial code (tiered memory, the
guardrail catalog, hybrid retrieval, model routing, config), or (c) is
infrastructure LangChain4j was never going to have an opinion on (the HTTP
layer). None of the three sentinel-module paths (§2.10), the two guardrail
execution surfaces (§2.6), or the ServiceLoader-degradation pattern (§3.1)
turned out to be accidental duplication on closer reading — each is
documented, at the point of definition, as a deliberate choice.

**One qualification, added with §2.1's `with...` methods:** they are partly a
rename layer, and it is worth being exact about which part.
`withTemperature` and `withTimeout` are near pass-throughs — every builder
already spells them `temperature` and `timeout(Duration)` (Jlama takes a
`Float` and has no timeout at all). Only `withMaxTokens` unifies real naming
drift (`maxTokens`, `maxCompletionTokens`, `numPredict`, `maxOutputTokens`).
If vocabulary were the whole case, two of the three would be cut. They stay
for reasons that aren't naming. CafeAI never exposes the builder, so before
them a provider could not be tuned at all — `of(modelId)` and nothing more.
An unset value is never passed to the builder, so an untuned provider builds
exactly as before. And `withTimeout` is a per-model override of an
application-wide key, which no single builder call expresses. The payoff is
*access* and precedence, not vocabulary. The cost is boilerplate — three
overrides in each of six provider records — plus an interface default that
throws, which turns "this provider ignores your setting" into a runtime
`UnsupportedOperationException` where a compile error would have been better.

**Two things worth a standing decision, not a cut. The first:** the duality in §3.3
means the four cross-cutting concerns each have their behavior expressed
twice — once in `CafeAIApp`'s imperative pipeline, once in an
`AiServices`-facing adapter. A semantic change to, say, what `POST_LLM`
guardrail position means has to be reasoned about at both
`CafeAIApp.applyPostLlmGuardrails()` *and* `GuardrailAdapters.asOutput()`
together, or the two calling styles drift apart in behavior. That's not fat
— removing either engine would remove a capability (§3.3 explains why
neither can absorb the other) — but it is a place in this codebase where
"did I update both sides" deserves to be a checklist item, not an
assumption. If a future audit finds drift, it will be here.

**The second:** the provider knobs. Adding a fourth (say `withTopP`) means
touching every provider record, both `LangchainBridge` switches
(`createModel` and `createStreamingModel`), and the two providers that build
their own model outside the bridge (`Gemini`, `Nvidia`) — the same "did I
update every side" shape as the duality above, with no compiler help, since
the interface default compiles fine when a provider is skipped.
`ProviderOptionsTest` loops over every built-in provider, so extending it for
a new knob is one assertion and will catch a provider that was missed.
`ProviderMappingTest` covers what each provider does with the settings: it
builds every model offline (placeholder keys, set for the `cafeai-core` test JVM
only, since building a model makes no network call) and reads the result back
through `defaultRequestParameters()`, including that OpenAI's `maxTokens` lands
on `max_completion_tokens` and not `max_tokens`. `withTimeout` isn't readable off
a model, so it is proven on the wire against a local server standing in for
Ollama, the one provider with a configurable base URL; the other providers share
the bridge's one-line `timeout(provider)` call but are not independently timed.
Both were mutation-checked — breaking the OpenAI mapping, or ignoring the
per-provider timeout, fails the tests. One gap remains: **`Jlama`**. Building a
Jlama model downloads it, so its mapping, including the `Double`→`Float`
temperature conversion, has no offline test and has not been run.
