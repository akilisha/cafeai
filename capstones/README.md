# CafeAI Capstones

Runnable reference applications that exercise the framework end to end. They are
here, in the umbrella build, on purpose: each consumes CafeAI as
`project(':cafeai-*')`, so an API change that breaks a capstone breaks
`./gradlew build`. They are **not** published to Maven Central.

Every "test" class in these apps is a live-service harness (real OpenAI, real
Gmail) wired as a Gradle `JavaExec` task. CI compiles the capstones; it does not
run those harnesses.

| Capstone | Directory | Domain | Demonstrates |
|---|---|---|---|
| 1 | `support-desk` | Helios support desk | RAG + a GitHub-issue agent, session memory, guardrails, security, WebSocket |
| 2 | `meridian-qualify` | Regulated loan pre-qual | a forced tool-protocol agent, ECOA/FCRA/Fair-Housing guardrails, structured `QualificationDecision` |
| 3 | `acme-claims` | Insurance claims intake | a claims-API agent, Redis session memory, Chroma RAG, HIPAA + fraud guardrails |
| 4 | `invoice-processor` | AP / vendor invoices | batch job (no HTTP), `app.vision()` extraction, a reconciliation agent, Gmail |
| 5 | `nova-tutor` | AI tutor / presenter | **spec only** — see `docs/roadmap/CAPSTONE-5-nova-tutor.md` |

## Running

From the repository root:

```bash
./gradlew :capstones:support-desk:run
./gradlew :capstones:meridian-qualify:run
./gradlew :capstones:acme-claims:run          # needs docker-compose up -d (Redis + Chroma)
./gradlew :capstones:invoice-processor:run -Pdry
```

Each needs `OPENAI_API_KEY` (or a local Ollama for 1–3). `acme-claims` and the
Ollama-backed apps ship a `docker-compose.yml`. `invoice-processor` needs Gmail
OAuth2 credentials placed under its `src/main/resources/credentials/` (gitignored).

## What the series proves

Read together, the capstones show the framework's composability holding across
domains, deployment models (HTTP server, WebSocket, batch), and operational
concerns (regulated output, Redis memory, multimodal, cost control). Where a
capstone hit a gap, the gap was closed in the framework rather than worked around
in the app — the tools API becoming agent-only (`app.agent()`), `app.vision()`,
`.returning(Class)` structured output, `app.budget()` / `app.retry()`,
`AgentConfig.rag()`, and named providers all trace back to a capstone that needed
them.

## History

Capstones 1–4 began as separate repositories on `io.cafeai:*:0.1.x` and the
now-removed `cafeai-tools` module. They were folded in here in 2026-09; the
per-capstone `notes/` (invoice-processor) and this file are the only surviving
narrative from that era. `invoice-processor` was `atlas-inbox`; its package was
renamed to `io.meridian.invoice`.
