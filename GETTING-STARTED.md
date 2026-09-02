# Getting Started with CafeAI

## Using CafeAI in Your Project

CafeAI is published to Maven Central under `com.akilisha.oss`. If you just want to
build an app *with* CafeAI, this is all you need — the rest of this document is
for building or contributing to CafeAI itself.

**Gradle** (`build.gradle`)
```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.1.3'
    // add capability modules as needed — cafeai-memory, cafeai-rag,
    // cafeai-guardrails, cafeai-observability, cafeai-security,
    // cafeai-streaming, cafeai-connect, cafeai-views-mustache
}
```

**Maven** (`pom.xml`)
```xml
<dependency>
  <groupId>com.akilisha.oss</groupId>
  <artifactId>cafeai-core</artifactId>
  <version>0.1.3</version>
</dependency>
```

Requires **Java 23+**. For a local `Jlama` model, add
`--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED` to your
run arguments.

---

## Prerequisites

- **Java 23+** — required for the FFM API, the Vector API (Jlama), and Virtual Threads
- **IntelliJ IDEA** 2023.3+ (recommended) or any IDE with Gradle support
- **Git**

## First-Time Setup

### 1. Clone and build

The Gradle wrapper is committed — no local Gradle install needed. The wrapper
downloads Gradle 9.7.1 and all dependencies on first run.

```bash
git clone https://github.com/akilisha/cafeai.git
cd cafeai
./gradlew build
```

### 2. Open in IntelliJ IDEA

```
File → Open → select the cafeai/ directory → Open as Project
```

IntelliJ detects `settings.gradle` and prompts to load the Gradle project.
Accept — it uses the wrapper's Gradle 9.7.1 and resolves everything automatically.

### 3. Run HelloCafeAI

```
cafeai-examples → src/main/java → io.cafeai.examples → HelloCafeAI → Run
```

Or from the terminal:

```bash
./gradlew :cafeai-examples:run
```

### 4. Test the endpoints

`HelloCafeAI` needs an LLM provider — set `OPENAI_API_KEY`, or edit the source to
use `Ollama.llama3()` / `Jlama.qwen2()` (no key).

```bash
# Health check
curl http://localhost:8080/health

# One-shot question
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"question":"What is a virtual thread?"}'

# Session-aware chat (X-Session-Id threads conversation history)
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" -H "X-Session-Id: demo" \
     -d '{"message":"My name is Ada."}'

# Template-based classification
curl -X POST http://localhost:8080/classify \
     -H "Content-Type: application/json" \
     -d '{"message":"Where is my package?"}'
```

## Project Structure

```
cafeai/
├── cafeai-core/            ← Start here — the Express API + AI primitives
├── cafeai-memory/          ← Tiered context memory
├── cafeai-rag/             ← RAG pipeline — ingestion, embedding, retrieval
├── cafeai-guardrails/      ← PII, jailbreak, bias, hallucination, regulatory
├── cafeai-observability/   ← OpenTelemetry, metrics, eval harness
├── cafeai-security/        ← Prompt injection, data leakage, cache poisoning
├── cafeai-streaming/       ← SSE / WebSocket token streaming
├── cafeai-connect/         ← Out-of-process services: Redis, Ollama, pgvector, MCP
├── cafeai-views-mustache/  ← Optional Mustache view engine
├── cafeai-examples/        ← Runnable examples — always kept working
└── docs/
    ├── SPEC.md             ← Full formal specification
    ├── adr/                ← Architecture Decision Records
    └── roadmap/            ← ROADMAP + MILESTONE documents
```

`cafeai-agents` (LangChain4j `AiServices` binding) is planned — see `docs/roadmap/ROADMAP-12`.

## Building and Testing

```bash
./gradlew build            # compile + test every module
./gradlew :cafeai-core:test # one module
./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.HelloCafeAI
```

The `docs/roadmap/` `ROADMAP-*` / `MILESTONE-*` documents track what's been built
and what's planned; each has explicit acceptance criteria. Publishing to Maven
Central is covered in `distribution.md`.

## JVM Flags for Local Models (Jlama)

CafeAI itself needs no special JVM flags — it targets stable Java 23. The one
exception is `Jlama`, the pure-Java local inference provider: its model classes
use the incubating Vector API, so any process that runs a Jlama model must add

```
--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
```

`cafeai-examples` sets this already. In IntelliJ: Run → Edit Configurations →
VM Options. Without it, model construction fails with
`ClassNotFoundException: jdk.incubator.vector.FloatVector`.

## Key Design Decisions

Before changing anything architectural, read:

- `docs/adr/` — the Architecture Decision Records (permanent decisions and their rationale)
- `docs/SPEC.md` — the full formal specification
- `DEVELOPER_GUIDE.md` — how the pieces fit together from a user's perspective

The ADRs answer *why*; the ROADMAPs answer *how*.
