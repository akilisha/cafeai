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

`./gradlew build` and `test` never touch the network. A separate task runs the same set of checks
against a real provider, so you can see the whole path (CafeAI provider, LangChain4j bridge, the wire)
work with your own key:

```bash
./gradlew :cafeai-core:liveTest      # every provider you have a key for, and Ollama if it is running
./gradlew :cafeai-agents:liveTest    # an agent that calls a tool, against the first provider available
```

Live tests are tagged `@Tag("live")`, excluded from `test`, and skip themselves when a provider is not
available: a missing key, no server, a model that is not installed. Keys come from the environment only;
they are never read from a file in the repo.

```powershell
# PowerShell — this window only
$env:OPENAI_API_KEY = "sk-..."
# or for your user account (open a new terminal afterwards)
[Environment]::SetEnvironmentVariable("OPENAI_API_KEY", "sk-...", "User")
```

```bash
# bash / zsh
export OPENAI_API_KEY=sk-...
```

| Provider | Needs | Model variable (default) |
|---|---|---|
| OpenAI | `OPENAI_API_KEY` | `OPENAI_LIVE_MODEL` (`gpt-4o-mini`) |
| Anthropic | `ANTHROPIC_API_KEY` | `ANTHROPIC_LIVE_MODEL` (`claude-haiku-4-5-20251001`) |
| Gemini | `GEMINI_API_KEY` | `GEMINI_LIVE_MODEL` (`gemini-2.5-flash`) |
| NVIDIA | `NVIDIA_API_KEY` | `NVIDIA_LIVE_MODEL` (`nvidia/nemotron-3.5-lightning-30b-a3b`) |
| Ollama | a running server with the model pulled | `OLLAMA_LIVE_MODEL` (`llama3.2`), `OLLAMA_LIVE_URL` (`http://localhost:11434`) |
| Jlama | a model on disk | `JLAMA_LIVE_MODEL` (no default: the first run downloads about 300 MB) |

Model ids are provider data and change: a vendor retires a model, or a model is not enabled for your
account. When a default stops working, point the variable at one that answers. For NVIDIA,
`curl -H "Authorization: Bearer $NVIDIA_API_KEY" https://integrate.api.nvidia.com/v1/models` lists what
your key can see.

Every provider runs the same suite (`cafeai-core/src/test/java/io/cafeai/core/live/ProviderLiveSuite.java`):

| Test | What it proves |
|---|---|
| plain call | `app.prompt(...).call()` returns text, token usage and the model id |
| streamed call | `.stream(...)` delivers several chunks that add up to the answer |
| structured output | `.call(Class)` returns a typed object parsed from the model's JSON |
| system prompt | `app.system(...)` shapes the reply |
| session memory | a fact given in one turn is recalled in the next (`MemoryStrategy` + `.session(...)`) |
| `HistoryPolicy.summarise()` | a fact folded into a summary is still known, and the model kept it in the summary |
| `HistoryPolicy.lastMessages(n)` | a fact that has left the window is not known |
| `withMaxTokens` | a 16-token cap truncates a long answer (the setting reaches the vendor parameter) |
| `withTemperature` | the endpoint accepts the setting |
| `withTimeout` | an impossible timeout fails within seconds instead of waiting for a reply |
| vision *(when the provider has a vision model)* | `app.vision(...)` sends an image and the model describes it |

Some providers add checks of their own:

| Provider | Extra checks |
|---|---|
| OpenAI | speech round trip (`app.synthesise(...)` makes audio, `app.audio(...)` transcribes it back); the moderation guardrail blocks violent text |
| NVIDIA | `NVIDIA_LIVE_REASONING_MODEL` enables the thinking-stream test (`.onThinking(...)` receives reasoning apart from the answer); `NVIDIA_LIVE_VISION_MODEL` enables vision |
| Ollama | `OLLAMA_LIVE_VISION_MODEL` (for example `llava`) enables vision |
| Jlama | runs the suite above with a larger `withMaxTokens` cap (Jlama's limit counts the prompt too) and no `withTimeout` check (there is no call to time out); adds a limit below the prompt failing with a clear message, `withTemperature(0)` repeatability and `Jlama.cachedIn(...)`. Run with `JLAMA_LIVE_MODEL=tjake/Qwen2.5-0.5B-Instruct-JQ4 ./gradlew :cafeai-core:liveTest --tests '*JlamaLiveTest*'`; `liveTest` already passes the Vector API flags Jlama needs. A 0.5B model is small, so try a larger one before suspecting the framework when a recall check fails |
| agents (`:cafeai-agents:liveTest`) | the model calls a `@Tool` and answers from the result; session memory reaches the agent, tool calls included |

A small local model rewords things and gets simple questions wrong now and then, so a failure on Ollama
is worth reading before it is worth blaming. Live tests have found real bugs here: an agent with tools
and session memory used to crash on its second message, and the wording of a history summary decided
whether a small model connected it to "my name".

To add a live test for another provider: extend `ProviderLiveSuite`, say why the suite cannot run
(`skipReason()`) and how to build the provider (`provider()`), and add whatever only that provider can do.
Make sure the module applies `gradle/live-tests.gradle` (`cafeai-core` and `cafeai-agents` already do).

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
