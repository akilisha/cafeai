# ☕ CafeAI

> *A foundational and composable framework for Gen AI in Java.*

**CafeAI is not an invention of anything new.** It is a deliberate re-orientation of familiar, battle-tested patterns and paradigms — Java's robustness, Express's composability, Langchain's AI primitives — unified into a foundational and composable framework for the AI age. Built for Java developers who refuse to trade understanding for convenience.

---

## Why CafeAI?

The Java ecosystem deserves a serious Gen AI story. Not one hidden behind Spring Boot abstractions, but one built on first principles — where every layer is explainable, every concern is composable, and every design decision has a reason you can articulate in a conference talk.

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
**CafeAI** → sounds like *"kaf-ai"* — a natural coming together of Java and AI

---

## The Three Lineages

CafeAI stands deliberately on the shoulders of three proven traditions:

| Lineage               | Contribution                                             | Why It Matters                               |
|-----------------------|----------------------------------------------------------|----------------------------------------------|
| **Java / JVM**        | Robustness, FFM, Structured Concurrency, Virtual Threads | Enterprise systems are already here          |
| **Express.js / Node** | Middleware composability, ergonomic API                  | Zero mental model ramp-up for Java devs      |
| **Python Langchain**  | AI primitives vocabulary, RAG, agents                    | Parity for AI practitioners across languages |

---

## Installation

On Maven Central under `com.akilisha.oss`. CafeAI is modular — start with
`cafeai-core` and add capability modules as you climb the adoption ladder.

**Gradle**
```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.4.0'

    // add only what you use:
    implementation 'com.akilisha.oss:cafeai-config:0.4.0'         // application.properties/.yaml + profiles
    implementation 'com.akilisha.oss:cafeai-aiservices:0.4.0'         // app.agent() — LangChain4j AiServices
    implementation 'com.akilisha.oss:cafeai-memory:0.4.0'         // tiered context memory
    implementation 'com.akilisha.oss:cafeai-rag:0.4.0'            // retrieval-augmented generation
    implementation 'com.akilisha.oss:cafeai-guardrails:0.4.0'     // PII, jailbreak, toxicity, regulatory
    implementation 'com.akilisha.oss:cafeai-observability:0.4.0'  // OpenTelemetry tracing
    implementation 'com.akilisha.oss:cafeai-security:0.4.0'       // audit events for blocked prompt injection
    implementation 'com.akilisha.oss:cafeai-connect:0.4.0'        // Redis, Ollama, pgvector
    implementation 'com.akilisha.oss:cafeai-views-mustache:0.4.0' // Mustache view engine
    implementation 'com.akilisha.oss:cafeai-session:0.4.0'        // SQLite-backed HTTP session store
    implementation 'com.akilisha.oss:cafeai-flight:0.4.0'         // JVM visibility via Flight Recorder -> OTel
    implementation 'com.akilisha.oss:cafeai-sentinel:0.4.0'       // AI Kubernetes/OpenShift incident pipeline
}
```

**Maven**
```xml
<dependency>
  <groupId>com.akilisha.oss</groupId>
  <artifactId>cafeai-core</artifactId>
  <version>0.4.0</version>
</dependency>
```

**Using several modules?** Import the BOM once and leave the versions off, so the modules cannot drift
apart (they are released together and share service-loader interfaces, so a mix of versions is not a
supported combination):

```groovy
dependencies {
    implementation platform('com.akilisha.oss:cafeai-bom:0.4.0')
    implementation 'com.akilisha.oss:cafeai-core'
    implementation 'com.akilisha.oss:cafeai-guardrails'
    implementation 'com.akilisha.oss:cafeai-aiservices'
}
```

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.akilisha.oss</groupId>
      <artifactId>cafeai-bom</artifactId>
      <version>0.4.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

The BOM lists only CafeAI's own modules; LangChain4j and Helidon keep their own BOMs, which the modules import.

Requires **Java 23+** (CI builds and tests on Java 23). For a local
`Jlama` model, also add `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
to your run args.

---

## Quick Start

```java
var app = CafeAI.create();

// Infrastructure
app.ai(OpenAI.of("gpt-4o"));
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

app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));

app.post("/chat", (req, res, next) -> {
    res.stream(app.prompt(req.body("message")));
});

app.listen(8080, () -> System.out.println("☕ CafeAI is brewing on :8080"));
```

Read that out loud. A Java developer who has never touched Gen AI understands every line. An Express developer who has never touched Java understands the structure. A Python LangChain developer recognizes the concepts. That's three audiences, zero confusion.

Talk to it with any HTTP client. `/chat` streams the answer back as Server-Sent Events:

```bash
curl -N -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
     -d '{"message": "What is your return policy?"}' \
     http://localhost:8080/chat

# data: Our
# data:  return
# data:  policy
# data:  allows
# ...
# data: [DONE]
```

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
[ semantic cache lookup ]       ← memory middleware (clean, prompt-only answers only)
   ↓
[ RAG retrieval ]               ← rag middleware
   ↓
[ LLM call / model router ]     ← ai middleware
   ↓
[ guardrails POST ]             ← ethical / regulatory middleware
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
app.ai(OpenAI.of("gpt-4o"))                    // register LLM provider
app.ai(Anthropic.of("claude-sonnet-4-5"))        // swap providers freely
app.ai(Gemini.of("gemini-3.6-flash"))           // Google Gemini — not a built-in type, added via ChatModelAccess
app.ai(Nvidia.of("moonshotai/kimi-k3"))         // NVIDIA API catalog (build.nvidia.com), key from $NVIDIA_API_KEY
app.ai(Ollama.of("llama3.3"))                   // local model via Ollama, no data leaves your infra
app.ai(Jlama.of("tjake/Qwen2.5-0.5B-Instruct-JQ4"))                     // pure-Java local model — in-process, no server
                                          //   run with: --add-modules jdk.incubator.vector
                                          //             --enable-native-access=ALL-UNNAMED
app.ai(ModelRouter.smart()                // smart routing — cheap vs expensive
        .simple(OpenAI.of("gpt-4o-mini"))
        .complex(OpenAI.of("gpt-4o")))
app.system("You are...")                  // system prompt — the AI's persona
app.template("name", "{{variable}}")     // named prompt templates
```

### Memory
```java
app.memory(MemoryStrategy.inMemory())     // Rung 1: JVM HashMap — prototype
app.memory(MemoryStrategy.mapped())      // Rung 2: SSD-backed via Java FFM
app.memory(MemoryStrategy.redis(config)) // Rung 3: Redis — the escape valve
app.memory(MemoryStrategy.hybrid())      // Rung 4: warm SSD + cold Redis
```

### Semantic cache
```java
app.cache(SemanticCache.inMemory(EmbeddingProvider.local()).build());
```
Repeat questions are answered from a cache matched by *meaning*, skipping the model call. A cache
shared between users is also an attack surface — coax the model into a bad answer, get it stored,
and it is served to everyone whose question lands nearby — so the defences are built in, not
optional: only **clean, prompt-only** answers are stored (nothing a guardrail flagged; nothing with
a session or RAG); entries are isolated per model and system prompt; a hit needs high embedding
similarity **and** high word overlap **and** similar length (so a popular question with instructions
appended does not match); every hit is **re-screened** by the current guardrails; and entries expire.
`.noCache()` opts a call out. It is heuristic, not a guarantee — see
`docs/adr/ADR-013-semantic-cache-and-poisoning-defences.md` for the threat model and its limits.

### RAG
```java
app.vectordb(PgVector.connect(config))   // vector store
app.embed(EmbeddingProvider.local())     // embedding model (ONNX via FFM)
app.ingest(Source.pdf("handbook.pdf"))   // ingest knowledge
app.ingest(Source.url("https://..."))
app.ingest(Source.directory("docs/"))
app.rag(Retriever.semantic(5))           // attach retrieval pipeline
app.rag(Retriever.hybrid(5))             // dense + sparse retrieval
```

### Guardrails
```java
app.guard(GuardRail.pii())               // PII detection — pre and post LLM
app.guard(GuardRail.jailbreak())         // adversarial prompt detection
app.guard(GuardRail.promptInjection())   // injected instructions — in input AND in retrieved RAG documents
app.guard(GuardRail.secrets())           // API keys, tokens, private keys — in and out
app.guard(GuardRail.promptLeak(system))  // the model repeating its own system prompt
app.guard(GuardRail.toxicity())          // harmful content filtering
app.guard(GuardRail.moderation(          // a moderation MODEL (LangChain4j's), not a pattern list
    OpenAI.moderation("omni-moderation-latest")))
app.guard(GuardRail.regulatory()         // GDPR, HIPAA, FCRA, CCPA
    .gdpr().hipaa())
app.guard(GuardRail.topicBoundary()      // scope enforcement
    .allow("customer service", "orders")
    .deny("politics", "medical advice"))
```

### Agents & Tools
```java
// You define a typed interface; CafeAI binds it to a LangChain4j AiService —
// which owns the reasoning loop, tool dispatch, and chat memory — and gives it
// an HTTP identity: session threading, guardrail pre-screening, observability.
app.agent("support", SupportAgent.class)
    .system("You are a support specialist for Acme.")
    .tool(new OrderLookupTool())                   // @Tool-annotated Java methods
    .memory(MemoryStrategy.inMemory())
    .guard(GuardRail.jailbreak())                  // runs before the agent loop
    .configure(b -> b /* full AiServices.Builder escape hatch */);
```

CafeAI does **not** reimplement the ReAct loop, tool protocol, or MCP client —
LangChain4j does all of that. CafeAI writes the HTTP binding. MCP servers attach
through LangChain4j's MCP support; multi-agent workflows are a supervisor agent
calling sub-agents as tools, or a middleware chain — not bespoke primitives.

### Observability
```java
app.observe(ObserveStrategy.otel())       // OpenTelemetry — production
app.observe(ObserveStrategy.console())    // console — development
```

### Configuration
```java
// declared once, at the point of use — a self-documenting, self-registering key
static final ConfigKey<Integer> CHUNK_SIZE =
    ConfigKey.of("cafeai.rag.chunk.size", Integer.class, 500, "Chunk size in characters");

int size = AppConfig.load().get(CHUNK_SIZE);
```
```yaml
# application.yaml on the classpath — or application-{profile}.yaml, or an
# external file pointed to by CAFEAI_CONFIG_FILE / cafeai.config.file
cafeai:
  rag:
    chunk:
      size: 800
```
`ConfigKey`/`AppConfig` live in `cafeai-core` — every module can declare a tunable
value with no new dependency. Add `cafeai-config` to your *application* to turn
those keys into real, overridable settings — system property, environment
variable, profile file, or `application.yaml` — resolved by Helidon Config, no
CafeAI-invented naming scheme. Without it, every key just resolves to its own
coded default, exactly as before this existed. See `docs/adr/ADR-012-application-config.md`.

### `cafeai-sentinel` — AI Cluster Incident Pipeline
```java
SentinelConfig config = SentinelConfig.create().namespace("payments");
ClusterWatch watch = new ClusterWatch(config);

app.agent("cluster-investigator", ClusterInvestigator.class)
    .model(Anthropic.of("claude-sonnet-4-5-20250929"))
    .tool(new KubeTools(watch.client(), "payments", Redactor.of(config.isRedact())));

IncidentTracker tracker = new IncidentTracker(config)
    .onIncident(IncidentSink.of(new LogSink(), new SsePublisher()))
    .investigator(incident -> /* run the agent above */ null)
    .start();

watch.onPodState(tracker::accept);
watch.start();
```
Watches one Kubernetes/OpenShift namespace, triages pod failures with rules
(no model), coalesces correlated failures into one incident per broken
workload, and runs a read-only agentic investigation on confirmed incidents —
secrets and PII redacted before anything reaches the prompt. A pipeline, not a
product: it ends at "incident published," fanned out to a log, a webhook, an
SSE stream, or your own `IncidentSink`. See `docs/roadmap/ROADMAP-18-sentinel.md`
and the runnable `capstones/cluster-sentinel` companion.

---

## Module Structure

```
cafeai/
├── cafeai-bom            ← Bill of materials: one version for every published module
├── cafeai-core           ← Express-style API, routing, middleware chain, all AI primitives
├── cafeai-config         ← File-based config (application.properties/.yaml + profiles) for AppConfig
├── cafeai-aiservices         ← Binds LangChain4j AiServices to the HTTP server — app.agent()
├── cafeai-memory         ← Tiered context memory (in-memory, FFM/SSD, Redis)
├── cafeai-rag            ← Document ingestion, chunking, embedding, retrieval, vector DBs
├── cafeai-guardrails     ← PII, jailbreak, toxicity, regulatory compliance
├── cafeai-observability  ← OpenTelemetry tracing, console logging
├── cafeai-security       ← Blocks prompt injection, raises audit events
├── cafeai-connect        ← Out-of-process services: Redis, Ollama, pgvector
├── cafeai-views-mustache ← Optional Mustache view engine
├── cafeai-session        ← HTTP session store for Middleware.session() (SQLite, single-instance)
├── cafeai-flight         ← JVM-level visibility via Java Flight Recorder, surfaced as OTel metrics
├── cafeai-sentinel       ← AI cluster incident pipeline for Kubernetes / OpenShift (ROADMAP-18)
└── cafeai-examples       ← Runnable reference implementations — the adoption ladder
```

Each module is an independent rung on the adoption ladder. Start with `cafeai-core`. Graduate when you're ready.

---

## The Incremental Adoption Ladder

CafeAI is structured so that every team can start at the bottom and climb deliberately.

| Rung | Capability            | What You Learn                                     |
|------|-----------------------|----------------------------------------------------|
| 1    | Plain LLM call        | Helidon SE + Langchain4j basics                    |
| 2    | Prompt templates      | Structured prompt engineering                      |
| 3    | Context memory        | Conversation state, FFM memory API                 |
| 4    | RAG                   | Ingestion, embeddings, vector retrieval            |
| 5    | Tool use / MCP        | Giving the AI actions to take                      |
| 6    | Guardrails            | Safety, ethics, compliance as middleware           |
| 7    | Agents                | Typed agent interfaces, tool-call loops via LangChain4j |
| 8    | Observability         | Production measurement, OpenTelemetry traces       |
| 9    | Streaming             | SSE, backpressure, real-time UX                    |
| 10   | Security              | Prompt-injection blocking with audit events        |

---

## Modern Java Feature Map

CafeAI treats modern Java (21–23) features as load-bearing architecture — not demos.

| Feature                    | Where CafeAI Uses It                 | Why                                     |
|----------------------------|--------------------------------------|-----------------------------------------|
| **FFM API**                | Native ML bindings (ONNX, llama.cpp) | JNI-free native access                  |
| **FFM MemorySegment**      | SSD-backed session memory            | Off-heap, OS page cache, crash-recovery |
| **Scoped Values**          | Request context propagation          | No ThreadLocal hacks                    |
| **Vector API**             | Cosine similarity, dot products      | SIMD hardware acceleration for RAG      |
| **Virtual Threads**        | Every request handler                | I/O-bound LLM calls at zero cost        |

---

## Tiered Memory Architecture

```
Hot    →  JVM Heap           (active conversation turn)
Warm   →  FFM MemorySegment  (recent sessions — SSD-backed, no network)
Cold   →  Redis               (distributed — the escape valve)
Frozen →  Vector DB          (semantic long-term memory, RAG corpus)
```

The key insight: **most applications do not need Redis.** The SSD-backed FFM tier handles production single-node deployments with zero network overhead, zero cloud tax, and crash-recovery for free. Redis is the escape valve — not the default.

---

## Technology Stack

| Concern           | Technology                        | Version        |
|-------------------|-----------------------------------|----------------|
| Runtime           | Java                              | 23+            |
| HTTP Server       | Helidon SE                        | 4.5.5          |
| AI Framework      | LangChain4j                       | 1.20.0         |
| LLM Providers     | OpenAI, Anthropic, Gemini, NVIDIA, Ollama, Jlama | —  |
| Off-heap Memory   | Java FFM                          | JDK 23         |
| Distributed Cache | Redis (Lettuce)                   | 6.3.2          |
| Vector DB         | PgVector / Chroma                 | —              |
| Observability     | OpenTelemetry                     | 1.40.0         |
| PII Detection     | Built-in regex patterns           | —              |
| Build             | Gradle (Groovy DSL)               | 9.7.1          |

---

## Running the Examples

```bash
# Clone
git clone https://github.com/akilisha/cafeai.git
cd cafeai

# Run the hello world example
./gradlew :cafeai-examples:run

# Run a specific example
./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.JlamaStreamingExample

# Smoke-test a real provider with your own key (skips itself without one)
./gradlew :cafeai-core:liveTest
```

Live tests, the keys they read and the model variables are described in
[GETTING-STARTED.md](GETTING-STARTED.md#live-tests-against-real-providers).

## Capstones

Full reference applications live in [`capstones/`](capstones/README.md) —
`support-desk`, `meridian-qualify`, `acme-claims`, `invoice-processor`. They build
against the framework as `project(':cafeai-*')`, so they break the build if an API
changes. Not published.

```bash
./gradlew :capstones:invoice-processor:run -Pdry
```

---

## Blog Series

A narrative arc through the framework, one capability at a time, each post anchored to working code — not one post per module.

1. **Brewing AI in Java** — [Introducing CafeAI](https://github.com/akilisha/cafeai/blob/main/docs/blog/01-brewing-ai-in-java.md)
2. **The Middleware Pattern Meets Gen AI** — [From Express to CafeAI](https://github.com/akilisha/cafeai/blob/main/docs/blog/02-middleware-pattern-meets-gen-ai.md)
3. **Your First LLM Call Without Spring Boot** — [Helidon SE + LangChain4j](https://github.com/akilisha/cafeai/blob/main/docs/blog/03-first-llm-call-without-spring-boot.md)
4. **Prompt Engineering in Java** — [Templates, System Prompts, and the API Vocabulary](https://github.com/akilisha/cafeai/blob/main/docs/blog/04-prompt-engineering-in-java.md)
5. **Context Memory Without the Cloud Tax** — [Java FFM and the Tiered Memory Model](https://github.com/akilisha/cafeai/blob/main/docs/blog/05-context-memory-without-cloud-tax.md)
6. **Building a RAG Pipeline in Java** — [Ingestion, Embedding, and Retrieval](https://github.com/akilisha/cafeai/blob/main/docs/blog/06-building-rag-pipeline-in-java.md)
7. **Tool Use in Java** — [Giving the LLM Actions to Take](https://github.com/akilisha/cafeai/blob/main/docs/blog/07-tool-use-in-java.md)
8. **Ethical Guardrails as Middleware** — [PII, Jailbreak, and Regulatory Compliance](https://github.com/akilisha/cafeai/blob/main/docs/blog/08-ethical-guardrails-as-middleware.md)
9. **Vision and Audio in Java** — [Multimodal AI Without the Boilerplate](https://github.com/akilisha/cafeai/blob/main/docs/blog/09-vision-and-audio-in-java.md)
10. **Structured Output** — [Typed LLM Responses, No Parser Required](https://github.com/akilisha/cafeai/blob/main/docs/blog/10-structured-output.md)
11. **Production-Grade AI** — [Token Budgets, Retries, Observability, and Incident Response](https://github.com/akilisha/cafeai/blob/main/docs/blog/11-production-grade-ai.md)
12. **The Capstone Series** — [Five Applications and What Each Found](https://github.com/akilisha/cafeai/blob/main/docs/blog/12-the-capstone-series.md)

---

## License

Apache 2.0

---

> *CafeAI: Not an invention of anything new — a re-orientation of everything proven.*
