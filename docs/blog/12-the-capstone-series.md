# The Capstone Series — What Four Applications Prove

*Post 12 of 12 in the CafeAI series*

---

A framework is not proven by its documentation. It is proven by the applications built with it — specifically by the applications that hit the framework's limits, expose its gaps, and force the question: is this a framework that grows to meet real demands, or one that works until the problem gets hard?

Four applications. Fourteen roadmap items. Three hundred and fifty-nine tests. This is what they proved.

---

## Capstone 1 — `support-agent`: Discovery

The first application is always the discovery pass. You wire the stack together for the first time and find out what actually works versus what was assumed to work.

`support-agent` found the most important bug in CafeAI's early architecture: `app.guard()` was not wired into the pipeline. Guardrails were registered. They were never called. The bug existed from the beginning and was invisible until a complete application tried to use them.

This is the value of capstone projects. Unit tests cannot find a bug in the integration between two systems. An application finds it immediately.

What `support-agent` proved:

**RAG, memory, tools, guardrails, observability, and HTTP routing compose correctly.** The pipeline works end-to-end. A question reaches the HTTP handler, RAG retrieves relevant documentation, session memory threads the conversation history, the LLM answers using the retrieved context, guardrails check the response, observability traces the call, memory writes back the exchange. All of this is registered at startup in under 20 lines.

**Local model + cloud fallback works as a deployment pattern.** Ollama runs locally in development with no API cost. OpenAI runs in production or environments where Ollama is unavailable. The same code, the same application, two different runtime profiles — switched by one line at startup.

**`@CafeAITool` as enforcement, not enrichment.** The GitHub issue status tool does not add colour to the answer — it constrains it. The model cannot invent an issue status because the tool returns the authoritative answer. This pattern reappears in every subsequent capstone. It is the correct mental model for tools in a safety-conscious application.

---

## Capstone 2 — `meridian-qualify`: Stress Test

The second application was chosen to stress-test. A loan pre-qualification assistant in a regulated domain — FCRA, ECOA, fair lending. If CafeAI's primitives hold up under regulatory pressure, the framework is ready for serious work. If they feel forced, the framework needs to grow.

What `meridian-qualify` proved:

**Regulatory guardrails compose correctly with domain logic.** The FCRA guardrail, the ECOA guardrail, and the bias detection guardrail all register alongside the jailbreak and PII guardrails without conflict. Each fires at its position in the pipeline. The model's outputs are checked against regulatory constraints before the caller receives them.

**Structured output is a missing primitive.** Every qualification decision needed to be a typed `QualificationDecision` record — not free text. The boilerplate appeared the first time in `meridian-qualify`. It appeared three more times in `atlas-inbox`. By the fourth repetition, the primitive had been added to the framework.

**Tool-as-policy-enforcer under adversarial testing.** Attempts to convince the model to approve a loan despite a tool returning `INSUFFICIENT_CREDIT` failed. The tool result was authoritative. The model respected it. This was meaningful validation — it is not obvious that a language model will consistently honour tool results as factual constraints rather than as one more piece of context to reason around.

---

## Capstone 3 — `acme-claims`: Confirmation

The third application ran cleaner than the first two. The hard lessons had already been baked into the framework. The domain was new (insurance, not lending), the RAG corpus was different (policy documents, not API documentation), the memory strategy was upgraded (Redis instead of SSD), and the vector store was upgraded (Chroma instead of in-memory).

None of these changes required framework modifications. The one-line swap between memory strategies worked as specified. The vector store swap was one line. The new domain's guardrails composed with the existing ones without conflict.

What `acme-claims` proved:

**The framework transfers to new domains without modification.** The primitives are genuinely reusable. A developer building an insurance application uses the same `app.tool()`, `app.guard()`, `app.rag()`, and `app.memory()` as a developer building a lending application. The domain is in the configuration, not in the framework.

**The tiered memory model works as specified.** `MemoryStrategy.redis()` is a drop-in replacement for `MemoryStrategy.mapped()`. Sessions persist across restarts. Multiple instances share the session store. One line changed.

**This is what a maturing framework feels like.** The first capstone was about discovery. The second was about stress-testing. The third ran clean because the hard lessons were already fixed. The test suite that proved the fixes is the reason the third capstone could trust the framework.

---

## Capstone 4 — `atlas-inbox`: The Real Test

The fourth application was the hardest. It introduced three capabilities that had never been demonstrated — and exposing their absence was half the point.

**The multimodal gap.** `app.prompt()` accepts a string. There was no CafeAI-native path for binary content. The first version of `atlas-inbox` worked around this with `MultimodalChatService` — a raw LangChain4j wrapper that bypassed the entire CafeAI pipeline. Guardrails did not fire on those calls. Observability did not trace them. CafeAI was a satellite orbiting a sun that had nothing to do with the framework.

The gap was closed. `app.vision()` and `app.audio()` are first-class entry points. Every call routes through the pipeline. `MultimodalChatService` was deleted.

**The structured output gap.** The boilerplate appeared four times. The primitive was added. The pattern became one line.

**The token budget gap.** `Thread.sleep` appeared twice in application code. The primitive was added. The sleeps were removed.

What `atlas-inbox` proved:

**The gravity of a framework is determined by what it can and cannot attract.** When a framework has gaps, the difficult work clusters around whatever can do the work — even if that thing has nothing to do with the framework. `MultimodalChatService` was the gravity well for the hardest work in `atlas-inbox`. When the framework grew to handle that work, gravity shifted. CafeAI became the sun.

**Correctness requires validation, not just compilation.** The ROADMAP-14 Phase 7 refactor was declared complete when the code compiled. The VALIDATION.md that Phase 8 produced — running real PDFs through the real pipeline and documenting every outcome — was the actual proof. Two things were discovered: multi-page PDFs were being misclassified (prompt fix), and the Heiden PDF was actually a Graybar Electric invoice (stub data fix). Neither was visible from the code. Both required running the application.

---

## The Numbers

| Metric | Value |
|--------|-------|
| Total tests | 359 |
| Test modules | cafeai-core (267), cafeai-guardrails (33), cafeai-memory (20), cafeai-rag (13), cafeai-security (14), cafeai-tools (12) |
| Capstones | 4 complete, 1 specified (nova-tutor) |
| Roadmap items | 15 complete |
| Framework modules | 10 (core, memory, rag, tools, agents, guardrails, observability, security, streaming, connect) |
| Modalities | 3 (prompt, vision, audio) |
| Memory rungs | 5 (inMemory, mapped, chronicle, redis, hybrid) |
| Vector stores | 3 (inMemory, Chroma, PgVector) |
| LLM providers | 4 (OpenAI, Anthropic, Ollama, Whisper) |

---

## What Was Not Built

Honesty about what is missing matters more than the feature list.

**Named provider registry.** `app.ai()` registers one provider. The `nova-tutor` capstone needs three simultaneously — transcription, reasoning, synthesis. The workaround is three separate CafeAI instances. The framework gap is documented. ROADMAP-16 closes it.

**Audio output (TTS).** `AudioResponse` returns text. Speech synthesis produces audio bytes. The response type needs extension. The design decision (new field vs new entry point) is deferred to ROADMAP-16.

**Streaming vision.** `app.vision().stream()` does not exist yet. Vision calls block until the full response arrives. For long classification calls, this means waiting 3-8 seconds for the first token. ROADMAP-15 Phase 6 specifies the fix; it was deferred in favour of completing the audio pipeline and validation.

**Real-time audio.** Live transcription of a phone call in progress requires a fundamentally different pipeline model — WebSocket audio streaming, chunked transcription, real-time response generation. Not yet scoped.

These are not failures. A framework that honestly documents its limits is more trustworthy than one that claims to do everything. The gaps are tracked, scoped, and addressed in order of actual demand.

---

## The Architecture That Emerged

Looking across all four capstones, the architecture that emerged is not what was designed at the beginning. It is better.

The middleware model turned out to be exactly the right abstraction — not just for HTTP routing (where it was already proven), but for guardrails, memory management, token budgets, and observability. Every hard problem in AI application development is a middleware concern. The pipeline is the architecture.

The tiered memory model turned out to be more important than anticipated. Most AI tutorials default to Redis before asking whether Redis is needed. The `mapped()` tier — SSD-backed, crash-safe, zero infrastructure — handles most production workloads. Redis is the right answer for multi-instance deployments, not the right default for all deployments.

The tool-as-enforcement pattern turned out to be the key insight in agent safety. A tool that returns authoritative data constrains what the model can honestly say. The model cannot invent a credit score, an issue status, or a contracted amount when a tool returns the real value. This is not a guardrail — it is a structural property of how the pipeline is built.

The capstone development process turned out to be the right way to find gaps. Unit tests cannot find integration bugs. Integration tests cannot find architectural gaps. A complete application running against real infrastructure finds both. The capstones are the reason the framework is correct, not just the reason it is documented.

---

## What Comes Next

**nova-tutor** — the fifth capstone. An AI tutoring and presentation agent that combines `app.audio()` for speech recognition, `app.vision()` for curriculum reading, `app.prompt()` for reasoning, structured output for whiteboard commands, RAG for the curriculum corpus, and session memory for conversation continuity. The tldraw whiteboard integration lives in the application; CafeAI provides the AI layer. This boundary is held deliberately tight.

**ROADMAP-16** — named provider registry, TTS audio output, streaming-to-voice coordination. Three framework gaps surfaced by nova-tutor. Each is a real problem with a real design decision. ROADMAP-16 will address them in the same way ROADMAP-14 and ROADMAP-15 addressed the multimodal gap: as first-class framework primitives, not workarounds.

**The blog series as conference talks.** Each post in this series is also a conference talk. The posts are structured as self-contained arguments: here is the problem, here is the design decision, here is the running code that proves the decision was correct. That is the right structure for a 40-minute conference talk on a technical topic.

---

## The Last Word

CafeAI is not finished. No serious framework ever is. The gaps are real, the next roadmap is scoped, and the fifth capstone is specified.

What is finished: a framework that can be used to build real AI applications in Java, today, without trading understanding for convenience. A framework that treats safety as infrastructure. A framework that honest about its limits. A framework whose architecture can be explained in a conference talk, debugged in a stack trace, and tested in a CI pipeline.

That is what the four capstones prove. Not that CafeAI does everything — that it does what it claims, correctly, with evidence.

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
