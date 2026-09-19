# CafeAI — What It Is

> A Java framework for building AI-powered applications.
> Built on Helidon 4 and LangChain4j 1.20. Production-ready today.

---

## The one-sentence pitch

CafeAI lets Java developers add AI capabilities to their applications using
the same fluent, Express-style API they already know — without learning a new
paradigm, fighting with Python interop, or reading three pages of LangChain
documentation to do something simple.

---

## What it actually does

### Prompt

The core operation. Text in, text out, with the full production stack behind it.

```java
var app = CafeAI.create();
app.ai(OpenAI.of("gpt-4o"));
app.guard(GuardRail.jailbreak());
app.observe(ObserveStrategy.console());
app.budget(TokenBudget.perMinute(60_000));

String answer = app.prompt("What is the standard deviation of [2, 4, 4, 4, 5, 5, 7, 9]?")
    .call().text();
```

Guardrails, observability, and token budget are not bolt-ons — they apply to
every call automatically once registered.

### Vision

Multimodal input — PDF, JPEG, PNG — with the same pipeline.

```java
record InvoiceData(String vendor, double total, String currency) {}

InvoiceData invoice = app.vision("Extract invoice data.", pdfBytes, "application/pdf")
    .returning(InvoiceData.class)
    .call(InvoiceData.class);
```

Structured output via `.returning()` generates a JSON schema hint, sends it
with the prompt, and deserialises the response — no manual parsing.

### Audio

Transcription and audio analysis.

```java
String transcript = app.audio("Transcribe this call recording.", wavBytes, "audio/wav")
    .call().text();
```

### Speech synthesis (TTS)

Text to audio bytes, ready to stream or save.

```java
byte[] speech = app.synthesise("Hello, welcome to today's lesson.")
    .provider("voice")
    .call().audioBytes();

Files.write(Path.of("welcome.mp3"), speech);
```

### Named providers

Multiple providers in one application, each with a role.

```java
app.ai("tutor",         OpenAI.of("gpt-4o"));
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

app.prompt(lessonPrompt).provider("tutor").call();
app.audio(prompt, wav, "audio/wav").provider("transcription").call();
app.synthesise(explanation).provider("voice").call();
```

### Memory

Conversation history across calls, transparent to the developer.

```java
app.memory(MemoryStrategy.inMemory());

// First call
app.prompt("My name is Alex.").session("user-123").call();

// Second call — prior exchange is in context automatically
String response = app.prompt("What is my name?").session("user-123").call().text();
// → "Your name is Alex."
```

### RAG

Retrieval-augmented generation. Ingest documents once, retrieve relevant
context automatically on every prompt call.

```java
app.embed(EmbeddingProvider.local());
app.vectordb(VectorStore.inMemory());
app.rag(Retriever.semantic(5));

app.ingest(Source.pdf(Path.of("policy-manual.pdf"), "hr/policy"));

// Context is injected automatically
String answer = app.prompt("What is the parental leave policy?").call().text();
```

### Tools

Java methods the LLM can call, via an agent — LangChain4j `AiServices` owns dispatch, CafeAI gives it an HTTP identity.

```java
class CreditCheckTool {
    @Tool("Check applicant credit score and eligibility")
    public String checkCredit(String applicantId, double loanAmount) {
        return creditService.evaluate(applicantId, loanAmount).toJson();
    }
}

interface LoanAgent {
    String qualify(String question);
}

app.agent("loan", LoanAgent.class).tool(new CreditCheckTool());

// The LLM calls the tool when it decides it needs to
var agent = app.agent("loan", LoanAgent.class, sessionId);
String decision = agent.qualify("Qualify applicant A123 for a $250,000 mortgage");
```

### Guardrails

Pre- and post-LLM safety checks, composable.

```java
app.guard(GuardRail.jailbreak());
app.guard(GuardRail.pii());
app.guard(GuardRail.regulatory().gdpr().hipaa());
app.guard(GuardRail.topicBoundary().allow("insurance", "claims").deny("competitor pricing"));
```

### Model routing

Route to cheap vs expensive models by complexity — automatically.

```java
app.ai(ModelRouter.smart()
    .simple(OpenAI.of("gpt-4o-mini"))   // classification, short answers
    .complex(OpenAI.of("gpt-4o")));    // reasoning, tool use, long context

// Or as a named provider
app.ai("router", ModelRouter.smart()
    .simple(OpenAI.of("gpt-4o-mini"))
    .complex(OpenAI.of("gpt-4o")));
```

### HTTP server

Express-style routing built in. AI calls live inside route handlers.

```java
app.filter(CafeAI.json());

app.post("/classify", (req, res, next) -> {
    byte[] pdf     = req.bodyBytes();
    String result  = app.vision("Classify this document.", pdf, "application/pdf").call().text();
    res.json(Map.of("classification", result));
});

app.listen(8080);
```

### Observability

Console logging or OpenTelemetry, registered once, applies everywhere.

```java
app.observe(ObserveStrategy.console());
// Every prompt, vision, audio, and synthesis call is logged with
// model, tokens, latency, and guardrail outcomes.
```

### Configuration

A timeout, a pool size, a retry count — declared once, at the point of use, as
a self-documenting `ConfigKey`, not a bare undocumented constant:

```java
static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
        "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60),
        "Timeout for a single LLM chat call, any provider");

Duration timeout = AppConfig.load().get(CHAT_TIMEOUT);
```

Add `cafeai-config` and that value becomes real, overridable configuration —
system property, environment variable, an external file, or
`application.yaml` on the classpath — resolved by Helidon Config, dotted keys,
no CafeAI-invented naming scheme. Without it, every key just resolves to its
own coded default. No module needs `cafeai-config` to *declare* a key; only
the application deciding whether real resolution is active needs it.

### Cluster incidents

An AI pipeline for Kubernetes/OpenShift, built on the agent layer above, not
a separate paradigm:

```java
SentinelConfig config = SentinelConfig.create().namespace("payments");
ClusterWatch watch = new ClusterWatch(config);

app.agent("cluster-investigator", ClusterInvestigator.class)
    .model(Anthropic.of("claude-sonnet-4-5-20250929"))
    .tool(new KubeTools(watch.client(), "payments", Redactor.of(config.isRedact())));

IncidentTracker tracker = new IncidentTracker(config)
    .onIncident(IncidentSink.of(new LogSink(), new SsePublisher()))
    .start();

watch.onPodState(tracker::accept);
```

`cafeai-sentinel` watches one namespace, triages pod failures with rules (no
model — cheap, every event), coalesces correlated failures into one incident
per broken workload, and runs a read-only agentic investigation on confirmed
incidents, redacting secrets and PII before anything reaches the prompt. It's
a pipeline, not a product — it ends at "incident published," fanned out to a
log, a webhook, or a live SSE stream. Validated live against both minikube
and a real OpenShift cluster; see the runnable `capstones/cluster-sentinel`
companion.

---

## What it runs on

- **Java 23+** — virtual threads, records, pattern matching, sealed classes, FFM, the Vector API (Java 25, the current LTS, is recommended)
- **Helidon 4** — reactive HTTP server on virtual threads
- **LangChain4j 1.20** — LLM provider abstraction
- **OpenAI** — any chat model by id (`gpt-4o`, ...), plus Whisper and TTS
- **Anthropic** — any Claude model by id (`claude-sonnet-4-5`, ...)
- **Gemini** — any Gemini model by id (`gemini-3.6-flash`, ...)
- **Nvidia** — any model on NVIDIA's API catalog by id (`moonshotai/kimi-k3`, ...)
- **Ollama** — any local model by id (`llama3.3`, `llava`, `mistral`, ...)
- **Jlama** — any pure-Java in-process local model by id, no server required

---

## What it is not

CafeAI is not trying to be LangChain for Java. LangChain has 800 integrations
and a Python-first philosophy. CafeAI has 8 well-made primitives and a
Java-first philosophy. The bet is that most production AI applications need
the same 8 things done well, not 800 things done tolerably.

CafeAI is also not production-hardened at scale yet — it is a framework by
one developer, with a large test suite, five complete capstone applications,
and a clear roadmap. It is ready for real projects. It is not yet the
infrastructure layer for a Fortune 500 company's AI platform.

---

## The capstone applications

Five complete applications built with CafeAI, each demonstrating a different
use case, plus a sixth still at the spec stage:

**support-desk** — AI-powered customer support platform for the fictional
Helios API. Prompt pipeline, guardrails, session memory, topic boundary
enforcement.

**meridian-qualify** — Regulated loan pre-qualification. Forced tool-protocol
agent, ECOA/FCRA/Fair-Housing guardrails, structured `QualificationDecision`.

**acme-claims** — Insurance claim processing with RAG. PDF ingestion, semantic
retrieval, structured extraction, PII protection.

**invoice-processor** — Vendor invoice processing with vision. PDF/image
classification, structured extraction, reconciliation, Gmail integration.

**cluster-sentinel** — AI cluster incident pipeline for Kubernetes/OpenShift
(ROADMAP-18). Rule-based triage, agentic investigation with read-only tools,
secret/PII redaction, live SSE dashboard. Validated against minikube and a
real OpenShift cluster.

**nova-tutor** (spec only) — AI tutoring agent with voice. Named providers,
TTS synthesis, whiteboard command generation, lesson plan RAG.

---

## The numbers

| Metric | Value |
|--------|-------|
| Modules | 12 |
| Tests | 411 |
| Capstone applications | 5 (+ 1 in progress) |
| LLM providers supported | 3 (OpenAI, Anthropic, Ollama) |
| Lines of production code | ~12,000 |
| External dependencies | 3 (Helidon, LangChain4j, SLF4J) |
| Python required | 0 |
