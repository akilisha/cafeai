# Getting Started with CafeAI

## Using CafeAI in Your Project

CafeAI is published to Maven Central under `com.akilisha.oss`. If you just want to
build an app *with* CafeAI, this is all you need — the rest of this document is
for building or contributing to CafeAI itself.

**Gradle** (`build.gradle`)
```groovy
repositories { mavenCentral() }

dependencies {
    implementation 'com.akilisha.oss:cafeai-core:0.4.0'
    // add capability modules as needed — cafeai-config, cafeai-agents, cafeai-memory,
    // cafeai-rag, cafeai-guardrails, cafeai-observability, cafeai-security,
    // cafeai-connect, cafeai-views-mustache, cafeai-sentinel
}
```

**Maven** (`pom.xml`)
```xml
<dependency>
  <groupId>com.akilisha.oss</groupId>
  <artifactId>cafeai-core</artifactId>
  <version>0.4.0</version>
</dependency>
```

Requires **Java 23+**. For a local `Jlama` model, add
`--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED` to your
run arguments.

---

## Prerequisites

- **Java 23+** — required for the FFM API, the Vector API (Jlama), and Virtual Threads (**Java 25**, the current LTS, is recommended)
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
use `Ollama.of("llama3.3")` / `Jlama.of("tjake/Qwen2.5-0.5B-Instruct-JQ4")` (no key).

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
├── cafeai-config/          ← Application configuration — ConfigKey/AppConfig, Helidon Config-backed
├── cafeai-memory/          ← Tiered context memory
├── cafeai-rag/             ← RAG pipeline — ingestion, embedding, retrieval
├── cafeai-guardrails/      ← PII, jailbreak, toxicity, regulatory
├── cafeai-observability/   ← OpenTelemetry tracing, console logging
├── cafeai-agents/          ← app.agent() — binds LangChain4j AiServices (session, guardrails, RAG, observe)
├── cafeai-security/        ← Blocks prompt injection, raises audit events
├── cafeai-connect/         ← Out-of-process services: Redis, Ollama, pgvector
├── cafeai-views-mustache/  ← Optional Mustache view engine
├── cafeai-sentinel/        ← AI cluster incident pipeline for Kubernetes / OpenShift
├── cafeai-examples/        ← Runnable examples — always kept working
├── capstones/              ← Full reference apps (support-desk, meridian-qualify, acme-claims, invoice-processor)
└── docs/
    ├── SPEC.md             ← Full formal specification
    ├── adr/                ← Architecture Decision Records
    └── roadmap/            ← ROADMAP + MILESTONE documents
```

## Building and Testing

```bash
./gradlew build            # compile + test every module
./gradlew :cafeai-core:test # one module
./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.HelloCafeAI
```

The `docs/roadmap/` `ROADMAP-*` / `MILESTONE-*` documents track what's been built
and what's planned; each has explicit acceptance criteria. Publishing to Maven
Central is covered in `distribution.md`.

### Live tests against real providers

`./gradlew build` and `test` never touch the network. A separate task runs a smoke test against a
real provider, so you can check the whole path — CafeAI provider, LangChain4j bridge, the wire —
with your own key:

```bash
./gradlew :cafeai-core:liveTest
```

Live tests are tagged `@Tag("live")`, excluded from `test`, and skip themselves when the provider's
key is absent. The key comes from the environment only; it is never read from a file in the repo.

```powershell
# PowerShell — this window only
$env:NVIDIA_API_KEY = "nvapi-..."
# or for your user account (open a new terminal afterwards)
[Environment]::SetEnvironmentVariable("NVIDIA_API_KEY", "nvapi-...", "User")
```

```bash
# bash / zsh
export NVIDIA_API_KEY=nvapi-...
```

The NVIDIA suite (`cafeai-core/src/test/java/io/cafeai/core/live/NvidiaLiveTest.java`) proves:

| Test | What it proves |
|---|---|
| plain call | `app.prompt(...).call()` returns text, token usage and the model id |
| streamed call | `.stream(...)` delivers several chunks that add up to the answer |
| structured output | `.call(Class)` returns a typed object parsed from the model's JSON |
| system prompt | `app.system(...)` shapes the reply |
| session memory | a fact given in one turn is recalled in the next (`MemoryStrategy` + `.session(...)`) |
| `withMaxTokens` | a 16-token cap truncates a long answer (the setting reaches the vendor parameter) |
| `withTemperature` | the endpoint accepts the setting |
| `withTimeout` | an impossible timeout fails within seconds instead of waiting for a reply |
| thinking stream *(opt-in)* | `.onThinking(...)` receives reasoning tokens apart from the answer text |
| vision *(opt-in)* | `app.vision(...)` sends an image and the model describes it |

Two of them run only when you name a model for them:

| Variable | Purpose | Default |
|---|---|---|
| `NVIDIA_LIVE_MODEL` | text model for the main tests | `nvidia/nemotron-3.5-lightning-30b-a3b` |
| `NVIDIA_LIVE_REASONING_MODEL` | enables the thinking-stream test (`.onThinking(...)`); slow | *(skipped)* |
| `NVIDIA_LIVE_VISION_MODEL` | enables the vision test | *(skipped)* |

Model ids are provider data and change: NVIDIA retires models, and a model in its catalog may not be
enabled for your account (HTTP `410` and `404` respectively). List what your key can see with
`curl -H "Authorization: Bearer $NVIDIA_API_KEY" https://integrate.api.nvidia.com/v1/models`, and
point the variables above at one that answers.

Jlama runs in-process and needs no key, only a model on disk, so its live tests run when you name one:

```bash
JLAMA_LIVE_MODEL=tjake/Qwen2.5-0.5B-Instruct-JQ4 ./gradlew :cafeai-core:liveTest --tests '*JlamaLiveTest*'
```

The first run downloads the model (about 300 MB) into `~/.jlama/models`; `liveTest` already passes the
Vector API flags Jlama needs. They cover a plain and a streamed call, `withMaxTokens` (which for Jlama
counts the prompt too), `withTemperature(0)` repeatability, and `Jlama.cachedIn(...)`.

To add a live test for another provider: tag the class `@Tag("live")`, skip it with
`Assumptions.assumeTrue(...)` when the key is missing, and make sure the module applies
`gradle/live-tests.gradle` (`cafeai-core` already does).

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
