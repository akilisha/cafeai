# CafeAI — What It Is

> A Java framework for building AI-powered applications.
> Built on Helidon 4 and LangChain4j 1.11. Production-ready today.

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
app.ai(OpenAI.gpt4o());
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
app.ai("tutor",         OpenAI.gpt4o());
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
app.embed(EmbeddingModel.local());
app.vectordb(VectorStore.inMemory());
app.rag(Retriever.semantic(5));

app.ingest(Source.pdf(Path.of("policy-manual.pdf"), "hr/policy"));

// Context is injected automatically
String answer = app.prompt("What is the parental leave policy?").call().text();
```

### Tools

Java methods the LLM can call. Annotate, register, done.

```java
class CreditCheckTool {
    @CafeAITool("Check applicant credit score and eligibility")
    public String checkCredit(String applicantId, double loanAmount) {
        return creditService.evaluate(applicantId, loanAmount).toJson();
    }
}

app.tool(new CreditCheckTool());

// The LLM calls the tool when it decides it needs to
String decision = app.prompt("Qualify applicant A123 for a $250,000 mortgage").call().text();
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
    .simple(OpenAI.gpt4oMini())   // classification, short answers
    .complex(OpenAI.gpt4o()));    // reasoning, tool use, long context

// Or as a named provider
app.ai("router", ModelRouter.smart()
    .simple(OpenAI.gpt4oMini())
    .complex(OpenAI.gpt4o()));
```

### HTTP server

Express-style routing built in. AI calls live inside route handlers.

```java
app.filter(CafeAI.json());

app.post("/classify", (req, res) -> {
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

---

## What it runs on

- **Java 21** — virtual threads, records, pattern matching, sealed classes
- **Helidon 4** — reactive HTTP server on virtual threads
- **LangChain4j 1.11** — LLM provider abstraction
- **OpenAI** — GPT-4o, GPT-4o Mini, Whisper, TTS, o1
- **Anthropic** — Claude 3.5 Sonnet, Claude 3 Haiku
- **Ollama** — any local model (Llama 3, LLaVA, Mistral)

---

## What it is not

CafeAI is not trying to be LangChain for Java. LangChain has 800 integrations
and a Python-first philosophy. CafeAI has 8 well-made primitives and a
Java-first philosophy. The bet is that most production AI applications need
the same 8 things done well, not 800 things done tolerably.

CafeAI is also not production-hardened at scale yet — it is a framework by
one developer, with 411 tests, four capstone applications, and a clear
roadmap. It is ready for real projects. It is not ready to be the infrastructure
layer for a Fortune 500 company's AI platform. That comes after 0.2.0.

---

## The capstone applications

Four complete applications built with CafeAI, each demonstrating a different
use case:

**helios** — AI-powered customer support platform. Prompt pipeline, guardrails,
session memory, topic boundary enforcement.

**acme-claims** — Insurance claim processing with RAG. PDF ingestion, semantic
retrieval, structured extraction, PII protection.

**atlas-inbox** — Intelligent email routing with vision. PDF/image classification,
structured output, multi-strategy routing, confidence scoring.

**nova-tutor** (in progress) — AI tutoring agent with voice. Named providers,
TTS synthesis, whiteboard command generation, lesson plan RAG.

---

## The numbers

| Metric | Value |
|--------|-------|
| Modules | 8 |
| Tests | 411 |
| Capstone applications | 4 (+ 1 in progress) |
| LLM providers supported | 3 (OpenAI, Anthropic, Ollama) |
| Lines of production code | ~12,000 |
| External dependencies | 3 (Helidon, LangChain4j, SLF4J) |
| Python required | 0 |
