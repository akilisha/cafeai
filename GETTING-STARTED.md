# Getting Started with CafeAI

## Prerequisites

- **Java 21+** — required for FFM API, Structured Concurrency, Virtual Threads
- **IntelliJ IDEA** 2023.3+ (recommended) or any IDE with Gradle support
- **Git**

## First-Time Setup

### 1. Bootstrap the Gradle Wrapper

The `gradle-wrapper.jar` is not included in the repository (binary file).
Generate it once after cloning:

```bash
# Option A — if you have Gradle installed locally
gradle wrapper --gradle-version 8.10

# Option B — IntelliJ IDEA will offer to download Gradle automatically
# when you open the project. Click "Load Gradle Project" when prompted.
```

### 2. Open in IntelliJ IDEA

```
File → Open → select the cafeai/ directory → Open as Project
```

IntelliJ will detect `settings.gradle` and prompt to load the Gradle project.
Accept. It will download Gradle 8.10 and all dependencies automatically.

### 3. Run HelloCafeAI

```
cafeai-examples → src/main/java → io.cafeai.examples → HelloCafeAI → Run
```

Or from the terminal:

```bash
./gradlew :cafeai-examples:run
```

### 4. Test the endpoints

```bash
# Health check
curl http://localhost:8080/health

# Echo (demonstrates req/res round-trip)
curl -X POST http://localhost:8080/echo \
     -H "Content-Type: application/json" \
     -d '{"message":"hello cafeai"}'

# Path parameter
curl http://localhost:8080/users/42

# Rate-limited endpoint
curl http://localhost:8080/api/hello
```

## Project Structure

```
cafeai/
├── cafeai-core/          ← Start here — the Express API + AI primitives
├── cafeai-memory/        ← Tiered context memory (build after ROADMAP-01)
├── cafeai-rag/           ← RAG pipeline (build after ROADMAP-07 Phase 4)
├── cafeai-tools/         ← Tool use + MCP (build after ROADMAP-07 Phase 5)
├── cafeai-agents/        ← Multi-agent (build after ROADMAP-07 Phase 8)
├── cafeai-guardrails/    ← Guardrails (build after ROADMAP-07 Phase 7)
├── cafeai-observability/ ← OTel (build after ROADMAP-07 Phase 9)
├── cafeai-security/      ← Security layer (build after ROADMAP-07 Phase 10)
├── cafeai-streaming/     ← SSE/WebSocket (build after ROADMAP-09)
├── cafeai-examples/      ← Runnable examples — always kept working
└── docs/
    ├── SPEC.md           ← Full formal specification
    ├── adr/              ← Architecture Decision Records (ADR-001 to ADR-008)
    └── roadmap/          ← ROADMAP + MILESTONE documents (01 to 09)
```

## Build Order

Follow the roadmaps in sequence. Each phase has defined acceptance criteria.
Run `HelloCafeAI` after every phase to confirm nothing regressed.

```
ROADMAP-01  →  ROADMAP-02  →  ROADMAP-03  →  ROADMAP-04
     ↓               ↓
ROADMAP-05       ROADMAP-06
     ↓
ROADMAP-08 (DI layer — parallel with 02-06)
     ↓
ROADMAP-07 (Gen AI primitives — after Express foundation complete)
     ↓
ROADMAP-09 (Connectivity — WebSocket, SSE, gRPC, app.helidon())
```

## Enable Preview Features

Java 21 preview features (required for some FFM and Structured Concurrency APIs)
are already configured in `build.gradle`:

```groovy
compileJava.options.compilerArgs += ['--enable-preview', '-Xlint:preview']
```

Your IDE may need `--enable-preview` added to the run configuration JVM args.
In IntelliJ: Run → Edit Configurations → VM Options → `--enable-preview`

## Key Design Decisions

Before writing any code, read:

- `docs/adr/ADR-001` through `ADR-008` — the permanent architectural decisions
- `docs/SPEC.md` — the full formal specification
- `docs/roadmap/ROADMAP-01` — the first implementation phase

The ADRs answer *why* before the ROADMAPs answer *how*.
