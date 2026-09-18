# The Capstone Series — Five Applications and What Each Found

*Post 12 of 12 in the CafeAI series*

---

A framework's documentation says what it is meant to do. An application built with it finds out what it does. The capstones in `capstones/` are five runnable applications, each chosen to lean on a different part of CafeAI, and most of what is now in the framework (the multimodal entry points, structured output, the token budget, the engine-applied guardrails) is there because one of them ran into its absence.

A note on what these applications are. Each consumes the framework as `project(':cafeai-*')`, so an API change that breaks a capstone breaks `./gradlew build`; CI compiles them. Their "test" classes are live-service harnesses (a real model, real Gmail, a real cluster), run by hand. They are not part of the automated suite, which lives in the framework modules (post 8 describes what it covers for guardrails). This post is about what the capstones showed, not a claim that they are regression tests.

---

## Capstone 1 — `support-desk`: Everything at Once

A Helidon-served support assistant for an imaginary connection pool library: RAG over its documentation, a GitHub-issue agent with two tools, session memory, guardrails, the security filter, observability and a WebSocket chat.

The first application is the discovery pass. It wires the stack together for the first time and finds out what works and what was only assumed to. One lesson from that pass has held ever since: a module's own tests cannot find a missing connection *between* modules. A guardrail can be registered, correct and unit-tested, and still never be called by the pipeline. That is why `cafeai-core` now has tests that register a guardrail and assert the engine applies it to `app.prompt()`, `.vision()` and `.audio()`.

It also shows the local-model-with-cloud-fallback pattern: `app.connect(Ollama...)` probes Ollama at startup and registers OpenAI if it is not there. The choice is made once, at startup.

---

## Capstone 2 — `meridian-qualify`: A Regulated Domain

A loan pre-qualification assistant, chosen because a regulated domain is where a framework's safety story either holds up or does not.

- **Regulatory guardrails compose.** ECOA, FCRA and Fair Housing checks register beside the jailbreak, injection and topic guardrails, each firing in registration order. Note that `regulatory()` screens the user's input only; it does not read the model's answer.
- **Typed output.** Every qualification decision is a `QualificationDecision` record, not free text. The same boilerplate (ask for JSON, parse it, handle a bad reply) appeared here and again in `invoice-processor`, which is how `.call(Class)` came to exist (post 10).
- **A tool that computes the verdict.** The footprint check returns `APPROVED` or `DECLINED`, and the system prompt says the tool's result is authoritative. That constrains what the model can honestly say, because the answer came from a method. It is a request to the model, though, not a guarantee (post 7 covers this).

---

## Capstone 3 — `acme-claims`: A Second Domain

Insurance claims intake: a claims-API agent, Redis session memory, Chroma for the policy documents, HIPAA and fraud-coaching guardrails.

The point of the third application is transfer. The domain changed, the corpus changed, and the session memory and vector store are Redis and Chroma rather than the in-process options; each of those is a one-line choice at startup (`MemoryStrategy.redis(...)`, `VectorStore.chroma(...)`). The application code that calls `app.prompt()` did not change.

---

## Capstone 4 — `invoice-processor`: The Gap Finder

A batch application with no HTTP server: it reads vendor invoices from Gmail, extracts them with `app.vision()`, reconciles them with a reconciliation agent and classifies email sentiment. It found three gaps.

**Multimodal.** `app.prompt()` took a string, so the first version routed PDFs through its own wrapper around LangChain4j. Guardrails did not fire on those calls and observability did not trace them; the framework was beside the hardest work rather than under it. `app.vision()` and `app.audio()` are now first-class entry points that run the same pipeline, and the wrapper is gone.

**Structured output.** The parse-the-JSON boilerplate appeared repeatedly and is now one call.

**Token budget.** Application code carried `Thread.sleep` calls to stay under rate limits. The token budget replaced them.

The application also carries the clearest lesson about validation. A refactor was "complete" when it compiled; the real proof was running real PDFs through the real pipeline and recording every outcome in `capstones/invoice-processor/notes/VALIDATION.md`. That run found multi-page PDFs being misclassified (a prompt fix) and a sample invoice that was really from a different vendor (a stub-data fix). Neither was visible in the code.

---

## Capstone 5 — `cluster-sentinel`: A Different Kind of Application

`cafeai-sentinel` is a module that triages Kubernetes and OpenShift incidents: rule-based classification first, an agent that investigates, secret and PII redaction on the way out, and an SSE dashboard. The capstone runs it against a cluster, and all four of its demo scenarios were run on a real OpenShift cluster.

It matters because it is not a chat application. It shows the same primitives (agents, guardrails, redaction, streaming) serving an operations workflow, and it is the one capstone that ships as a published module rather than only as an example.

---

## The Numbers

| Metric | Value |
|--------|-------|
| Published modules | 11 (core, config, memory, rag, guardrails, observability, security, views-mustache, connect, agents, sentinel) |
| Test methods | about 860 across those modules (core ~590, sentinel ~60, guardrails ~55, connect ~47, observability ~27, agents ~23, memory ~20, views-mustache ~17, security ~12, config ~8, rag ~4) |
| Runnable capstones | 5 (plus `nova-tutor`, specified but not built) |
| Entry points | `app.prompt()`, `app.vision()`, `app.audio()`, `app.synthesise()` |
| Memory strategies | 4 (`inMemory`, `mapped`, `redis`, `hybrid`) |
| Vector stores | 3 (in-memory, Chroma, PgVector) |
| LLM providers | 6 (OpenAI, Anthropic, Gemini, Ollama, Jlama, NVIDIA) |

Counts are `@Test` annotations, not executed cases, and a test count says how much is checked, not how well. Live tests exist for NVIDIA and Jlama only (`GETTING-STARTED.md` explains how to run them); the other providers are covered by mapping tests that do not call a model.

---

## Known Limits

The framework's limits are listed in its documentation rather than discovered in production. The ones worth having in mind:

- **`.stream()` cannot take back tokens already sent.** A POST_LLM guardrail that flags a streamed answer can only report it afterwards.
- **An agent's retrieved documents are not screened.** LangChain4j owns that path; only `app.prompt()` screens what RAG retrieves.
- **`regulatory()` checks input only.**
- **The semantic cache is in-memory only.** There is no store-backed cache.
- **There is no per-user access control on documents**, so a knowledge base is readable by every caller of the application.
- **Real-time audio** (live transcription of a call in progress) needs a different pipeline model and is not scoped.
- **`nova-tutor`**, the AI tutor that would combine audio, vision, structured output and RAG under one application, exists as a specification (`docs/roadmap/CAPSTONE-5-nova-tutor.md`) and has not been built.

---

## What Emerged

The middleware model is the right shape for the HTTP side of an AI application: a JSON parser, CORS, a rate limit and your own authentication compose in order, and any of them can be added or removed without touching the others. The AI pipeline behind `app.prompt()` follows a fixed order (guardrails, cache, history, retrieval, the model call, guardrails again, memory), and what keeps it honest is that the engine applies the registered guardrails, so a call site cannot forget them.

The tiered memory model matters more than it looks. A local memory-mapped file is enough for one node and needs no infrastructure; Redis is for the moment a second instance needs to share sessions.

A tool that computes the answer beats a prompt that asks for it. Where the eligibility check, the arithmetic or the permission is a Java method, a unit test can pin it. Where it is a sentence in a system prompt, it is hoped for.

And an application running against real infrastructure finds what unit tests and integration tests do not. The capstones are the reason the framework has the shape it has.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*

---

## The Series

| Post | Title |
|------|-------|
| 1 | [Brewing AI in Java — Introducing CafeAI](01-brewing-ai-in-java.md) |
| 2 | [The Middleware Pattern Meets Gen AI](02-middleware-pattern-meets-gen-ai.md) |
| 3 | [Your First LLM Call Without Spring Boot](03-first-llm-call-without-spring-boot.md) |
| 4 | [Prompt Engineering in Java](04-prompt-engineering-in-java.md) |
| 5 | [Context Memory Without the Cloud Tax](05-context-memory-without-cloud-tax.md) |
| 6 | [Building a RAG Pipeline in Java](06-building-rag-pipeline-in-java.md) |
| 7 | [Tool Use in Java](07-tool-use-in-java.md) |
| 8 | [Ethical Guardrails as Middleware](08-ethical-guardrails-as-middleware.md) |
| 9 | [Vision and Audio in Java](09-vision-and-audio-in-java.md) |
| 10 | [Structured Output](10-structured-output.md) |
| 11 | [Production-Grade AI](11-production-grade-ai.md) |
| 12 | [The Capstone Series](12-the-capstone-series.md) |
