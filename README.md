# ☕ CafeAI

> *A foundational and composable framework for Gen AI in Java.*

**CafeAI is not an invention of anything new.** It is a deliberate re-orientation of familiar, battle-tested patterns and paradigms — Java's robustness, Express's composability, Langchain's AI primitives — unified into a foundational and composable framework for the AI age. Built for Java developers who refuse to trade understanding for convenience.

---

## Why CafeAI?

The Java ecosystem deserves a serious Gen AI story. Not one hidden behind Spring Boot abstractions, but one built on first principles — where every layer is explainable, every concern is composable, and every design decision has a reason you can articulate and defend with confidence.

| Problem                                 | CafeAI's Answer                                            |
|-----------------------------------------|------------------------------------------------------------|
| Spring AI abstracts too aggressively    | Helidon SE — you see the plumbing                          |
| Python has Langchain. Java has...?      | Langchain4j, with full API parity                          |
| Node devs know Express. Java devs don't | CafeAI mirrors Express pound-for-pound                     |
| AI pipelines are magic black boxes      | Everything is a middleware. Everything is explainable.     |
| "Just use Redis" for everything         | Tiered memory — FFM/SSD first, Redis only when you need it |

---

## The Name

**Cafe** → instantly recognizable as a coffee shop — *Java*  
**AI** → the technology we're introducing  
**CafeAI** → sounds like *"ka-fay-i"* — a natural coming together of Java and AI

---

## The Three Lineages

CafeAI stands deliberately on the shoulders of three proven traditions:

| Lineage               | Contribution                                             | Why It Matters                               |
|-----------------------|----------------------------------------------------------|----------------------------------------------|
| **Java / JVM**        | Robustness, FFM, Structured Concurrency, Virtual Threads | Enterprise systems are already here          |
| **Express.js / Node** | Middleware composability, ergonomic API                  | Zero mental model ramp-up for Java devs      |
| **Python Langchain**  | AI primitives vocabulary, RAG, agents                    | Parity for AI practitioners across languages |

---

## Quick Start

```java
var app = CafeAI.create();

// Infrastructure
app.ai(OpenAI.gpt4o());
app.memory(MemoryStrategy.mapped());      // SSD-backed via Java FFM — no Redis needed
app.observe(ObserveStrategy.otel());

// Safety
app.guard(GuardRail.pii());
app.guard(GuardRail.jailbreak());

// Persona
app.system("You are a helpful customer service agent for Acme Corp...");

// Routes
app.use(Middleware.json());
app.use(Middleware.rateLimit(60));

app.get("/health", (req, res) -> res.json(Map.of("status", "ok")));

app.post("/chat", (req, res) -> {
    res.stream(app.prompt(req.body("message")));
});

app.listen(8080, () -> System.out.println("☕ CafeAI is brewing on :8080"));
```

Read that out loud. A Java developer who has never touched Gen AI understands every line. An Express developer who has never touched Java understands the structure. A Python LangChain developer recognizes the concepts. That's three audiences, zero confusion.

---

## The Middleware Pipeline

Everything in CafeAI is middleware. HTTP concerns, AI concerns, security, observability, guardrails — all composable, all testable, all replaceable. This is the lesson Express taught us. CafeAI carries it to the AI age.

```
Incoming Request
   ↓
[ auth / JWT ]                  ← standard HTTP middleware
   ↓
[ rate limiter ]                ← standard HTTP middleware
   ↓
[ PII scrubber ]                ← security middleware
   ↓
[ jailbreak detector ]          ← security middleware
   ↓
[ prompt injection guard ]      ← security middleware
   ↓
[ guardrails PRE ]              ← ethical / regulatory middleware
   ↓
[ token budget enforcer ]       ← cost middleware
   ↓
[ semantic cache lookup ]       ← memory middleware
   ↓
[ RAG retrieval ]               ← rag middleware
   ↓
[ LLM call / model router ]     ← ai middleware
   ↓
[ guardrails POST ]             ← ethical / regulatory middleware
   ↓
[ hallucination scorer ]        ← guardrail middleware
   ↓
[ observability / OTel trace ]  ← observe middleware
   ↓
[ memory write ]                ← memory middleware
   ↓
[ streaming response ]          ← streaming middleware (SSE / WebSocket)
```

Every hard problem in Gen AI is a middleware concern. You can teach any one of those layers in isolation, then snap it into the pipeline. The pipeline is the curriculum.

---

## The API Vocabulary

CafeAI introduces a deliberate vocabulary for AI-native Java development. Every name is guessable before you look it up.

### Express-Parity HTTP (pound-for-pound)
```java
app.get(path, handler)       // GET route
app.post(path, handler)      // POST route
app.put(path, handler)       // PUT route
app.delete(path, handler)    // DELETE route
app.use(middleware)          // global middleware
app.use(path, middleware)    // path-scoped middleware
app.listen(port)             // start the server
```

### AI Infrastructure
```java
app.ai(OpenAI.gpt4o())                    // register LLM provider
app.ai(Anthropic.claude35Sonnet())        // swap providers freely
app.ai(Ollama.llama3())                   // local model, no data leaves your infra
app.ai(ModelRouter.smart()                // smart routing — cheap vs expensive
        .simple(OpenAI.gpt4oMini())
        .complex(OpenAI.gpt4o()))
app.system("You are...")                  // system prompt — the AI's persona
app.template("name", "{{variable}}")     // named prompt templates
```

### Memory
```java
app.memory(MemoryStrategy.inMemory())     // Rung 1: JVM HashMap — prototype
app.memory(MemoryStrategy.mapped())      // Rung 2: SSD-backed via Java FFM
app.memory(MemoryStrategy.chronicle())   // Rung 3: Chronicle Map off-heap
app.memory(MemoryStrategy.redis(config)) // Rung 4: Redis — the escape valve
app.memory(MemoryStrategy.hybrid())      // Rung 5: warm SSD + cold Redis
```

### RAG
```java
app.vectordb(PgVector.connect(config))   // vector store
app.embed(EmbeddingModel.local())        // embedding model (ONNX via FFM)
app.ingest(Source.pdf("handbook.pdf"))   // ingest knowledge
app.ingest(Source.url("https://..."))
app.ingest(Source.directory("docs/"))
app.rag(Retriever.semantic(5))           // attach retrieval pipeline
app.rag(Retriever.hybrid(5))             // dense + sparse retrieval
```

### Tools and MCP
```java
app.tool(OrderLookupTool.create())       // Java function — you own trust + lifecycle
app.tools(tool1, tool2, tool3)           // tool suite
app.mcp(McpServer.github())              // external MCP server — different trust level
app.mcp(McpServer.connect("http://..."))
```

### Guardrails
```java
app.guard(GuardRail.pii())               // PII scrub — pre and post LLM
app.guard(GuardRail.jailbreak())         // adversarial prompt detection
app.guard(GuardRail.promptInjection())   // data-sourced injection detection
app.guard(GuardRail.bias())              // demographic bias detection
app.guard(GuardRail.hallucination())     // factual grounding scoring
app.guard(GuardRail.toxicity())          // harmful content filtering
app.guard(GuardRail.regulatory()         // GDPR, HIPAA, FCRA, CCPA
    .gdpr().hipaa())
app.guard(GuardRail.topicBoundary()      // scope enforcement
    .allow("customer service", "orders")
    .deny("politics", "medical advice"))
```

### Agents
```java
app.agent("classifier", AgentDefinition.react()   // ReAct loop agent
    .tools(classifyTool)
    .maxIterations(5))
app.orchestrate("pipeline",                        // multi-agent via Structured Concurrency
    "classifier", "retriever", "responder")
```

### Observability
```java
app.observe(ObserveStrategy.otel())       // OpenTelemetry — production
app.observe(ObserveStrategy.console())    // console — development
app.eval(EvalHarness.defaults())          // retrieval + response quality scoring
```

---

## Module Structure

```
cafeai/
├── cafeai-core           ← Express-style API, routing, middleware chain, all AI primitives
├── cafeai-memory         ← Tiered context memory (FFM, Chronicle, Redis, Memcached)
├── cafeai-rag            ← Document ingestion, chunking, embedding, retrieval, vector DBs
├── cafeai-tools          ← Java tool registration, MCP server integration
├── cafeai-agents         ← ReAct, multi-agent orchestration via Structured Concurrency
├── cafeai-guardrails     ← PII, jailbreak, bias, hallucination, regulatory compliance
├── cafeai-observability  ← OpenTelemetry, metrics, eval harness, prompt versioning
├── cafeai-security       ← Prompt injection, data leakage, semantic cache poisoning
├── cafeai-streaming      ← SSE and WebSocket token streaming with backpressure
└── cafeai-examples       ← Runnable reference implementations — the adoption ladder
```

Each module is an independent rung on the adoption ladder. Start with `cafeai-core`. Graduate when you're ready.

---

## The Incremental Adoption Ladder

CafeAI is structured so that every team can start at the bottom and climb deliberately.

| Rung | Capability | What You Learn |
|---|---|---|
| 1 | Plain LLM call | Helidon SE + Langchain4j basics |
| 2 | Prompt templates | Structured prompt engineering |
| 3 | Context memory | Conversation state, FFM memory API |
| 4 | RAG | Ingestion, embeddings, vector retrieval |
| 5 | Tool use / MCP | Giving the AI actions to take |
| 6 | Guardrails | Safety, ethics, compliance as middleware |
| 7 | Agents | Autonomous reasoning loops, Structured Concurrency |
| 8 | Observability + Evals | Production measurement, prompt versioning |
| 9 | Streaming | SSE, backpressure, real-time UX |
| 10 | Security | Injection, leakage, adversarial robustness |

---

## Java 21+ Feature Map

CafeAI treats Java 21+ features as load-bearing architecture — not demos.

| Feature | Where CafeAI Uses It | Why |
|---|---|---|
| **FFM API** | Native ML bindings (ONNX, llama.cpp) | JNI-free native access |
| **FFM MemorySegment** | SSD-backed session memory | Off-heap, OS page cache, crash-recovery |
| **Structured Concurrency** | Multi-agent orchestration | Isolated failures, clean joins |
| **Scoped Values** | Request context propagation | No ThreadLocal hacks |
| **Vector API** | Cosine similarity, dot products | SIMD hardware acceleration for RAG |
| **Virtual Threads** | Every request handler | I/O-bound LLM calls at zero cost |

---

## Tiered Memory Architecture

```
Hot    →  JVM Heap           (active conversation turn)
Warm   →  FFM MemorySegment  (recent sessions — SSD-backed, no network)
Cool   →  Chronicle Map      (high-throughput off-heap, single node)
Cold   →  Redis / Memcached  (distributed — the escape valve)
Frozen →  Vector DB          (semantic long-term memory, RAG corpus)
```

The key insight: **most applications do not need Redis.** The SSD-backed FFM tier handles production single-node deployments with zero network overhead, zero cloud tax, and crash-recovery for free. Redis is the escape valve — not the default.

---

## Technology Stack

| Concern | Technology | Version |
|---|---|---|
| Runtime | Java | 21+ |
| HTTP Server | Helidon SE | 4.1.4 |
| AI Framework | Langchain4j | 0.35.0 |
| LLM Providers | OpenAI, Anthropic, Ollama | — |
| Off-heap Memory | Java FFM / Chronicle Map | JDK 21 / 3.25 |
| Distributed Cache | Redis (Lettuce) / Memcached | 6.3 / 2.12 |
| Vector DB | PgVector / Chroma | — |
| Observability | OpenTelemetry | 1.40.0 |
| PII Detection | Apache OpenNLP | 2.3.3 |
| Build | Gradle (Groovy DSL) | 8.x |

---

## Running the Examples

```bash
# Clone
git clone https://github.com/your-org/cafeai.git
cd cafeai

# Run the hello world example
./gradlew :cafeai-examples:run

# Run a specific example
./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.RagExample
```

---

## Blog Series

Each module is a blog post. The project is the curriculum.

1. **Brewing AI in Java** — CafeAI Introduction and Philosophy
2. **The Middleware Pattern Meets Gen AI** — From Express to CafeAI
3. **Your First LLM Call Without Spring Boot** — Helidon SE + Langchain4j
4. **Prompt Engineering in Java** — Templates, System Prompts, and the API Vocabulary
5. **Context Memory Without the Cloud Tax** — Java FFM and the Tiered Memory Model
6. **Building a RAG Pipeline in Java** — Ingestion, Embedding, and Retrieval
7. **Tool Use and MCP in Java** — The Difference Between a Tool and an MCP Server
8. **Ethical Guardrails as Middleware** — PII, Jailbreak, Bias, and Hallucination
9. **Multi-Agent Orchestration with Java Structured Concurrency**
10. **Production-Grade AI Observability** — OpenTelemetry, Evals, and Prompt Versioning
11. **AI Security Beyond Guardrails** — Prompt Injection, Data Leakage, and Cache Poisoning
12. **Token Streaming in Java** — SSE, WebSocket, and Reactive Backpressure

---

## License

Apache 2.0

---

> *CafeAI: Not an invention of anything new — a re-orientation of everything proven.*
