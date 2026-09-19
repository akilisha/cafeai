# Vision and Audio in Java — Multimodal AI Without the Boilerplate

*Post 9 of 12 in the CafeAI series*

---

Language models take more than text. CafeAI has one pipeline for three kinds of input — text, images and documents, and audio — so guardrails, session memory, the token budget, retries and observability apply to all of them the same way. This post covers `app.vision()` and `app.audio()`.

---

## The Three Modalities

CafeAI has three modality entry points:

```java
// Text — full pipeline, all features
PromptResponse r = app.prompt("Summarise this support ticket").call();

// Vision — binary content + text, vision pipeline
VisionResponse r = app.vision("Is this an invoice?", pdfBytes, "application/pdf").call();

// Audio — binary content + text, audio pipeline
AudioResponse  r = app.audio("Transcribe this call", wavBytes, "audio/wav").call();
```

All three support the same features through the same pipeline:

| Feature | prompt() | vision() | audio() |
|---------|----------|----------|---------|
| Session memory | ✅ | ✅ | ✅ |
| Guardrails PRE_LLM | ✅ | ✅ | ✅ |
| Guardrails POST_LLM | ✅ | ✅ | ✅ |
| Structured output | ✅ | ✅ | ✅ |
| Token budget | ✅ | ✅ | ✅ |
| Retry on rate limit | ✅ | ✅ | ✅ |
| Observability | ✅ | ✅ | ✅ |
| RAG retrieval | ✅ | ❌ | ❌ |

RAG is skipped for vision and audio — binary content cannot be embedded and compared to text embeddings. Every other feature applies uniformly.

---

## The `app.vision()` Pipeline

Vision calls send binary content alongside a text prompt to a multimodal LLM. The pipeline:

```
1. Validate provider supports vision (supportsVision() = true)
2. Apply PRE_LLM guardrails to the text prompt
3. Build session history (text only — no binary content stored)
4. Resolve system prompt
5. Append schema hint if .returning(Class) is set
6. Build LangChain4j message list via VisionMessageBuilder
7. Call model (with budget + retry)
8. Apply POST_LLM guardrails to the response
9. Persist to session memory (text only)
10. Return VisionResponse
```

The `VisionMessageBuilder` handles provider-specific content encoding. OpenAI's chat completions API accepts `ImageContent` (base64 PNG/JPEG) and `PdfFileContent` (data URI format with `data:application/pdf;base64,` prefix). The prefix matters — raw base64 produces a 400 error from the OpenAI API. The builder handles this encoding transparently.

---

## Structured Output from Vision

`.call(Class)` works identically for vision and text. `invoice-processor` uses it for both classification and extraction:

```java
// Classify the attachment
AttachmentClassification classification =
    app.vision(classificationPrompt, pdfBytes, "application/pdf").call(AttachmentClassification.class);

// classification.isInvoice() → true
// classification.docType()   → "INVOICE"
// classification.confidence() → "HIGH"
// classification.reason()    → "Document contains billing totals and payment terms"

// Extract invoice data
InvoiceData invoice =
    app.vision(extractionPrompt, pdfBytes, "application/pdf").call(InvoiceData.class);

// invoice.vendorName()    → "Liberty Fastener Company"
// invoice.invoiceNumber() → "0212164-1"
// invoice.totalAmount()   → "1353.50"
// invoice.isComplete()    → true
```

Both `AttachmentClassification` and `InvoiceData` are plain Java records. No custom deserialisation. No schema definition files. `SchemaHintBuilder` reflects on the record at call time and appends the schema hint to the prompt automatically.

---

## Vision Provider Capability

A provider declares whether it takes images, and CafeAI checks before the call:

```java
app.ai(OpenAI.of("gpt-4o"));      // OpenAI chat models are treated as vision-capable; the API rejects one that is not
app.ai(Anthropic.of("claude-sonnet-4-5"));
app.ai(Gemini.of("gemini-3.6-flash"));
app.ai(Ollama.vision("llava"));   // a local vision model

app.ai(Ollama.of("llama3.3"));    // text-only — app.vision() throws VisionNotSupportedException
```

`VisionNotSupportedException` on a text-only provider is better than a cryptic error from the model server. The message names the registered provider.

---

## The `app.audio()` Pipeline

Audio calls follow the same pipeline structure as vision, with one routing difference:

```
OpenAI providers:
    → the audio goes to OpenAI's /v1/audio/transcriptions endpoint (Whisper, multipart upload)
    → optionally: the transcript is sent back through the chat model for reasoning or extraction
```

OpenAI's chat completions API accepts only text and image content, so audio cannot ride along with a chat message; `AudioMessageBuilder` sends it to the transcription endpoint and treats the transcript as text from then on. The developer writes the same `app.audio()` call either way. Every `OpenAI.of(...)` provider can take audio, as can `OpenAI.whisper()`, the purpose-built transcription provider. The other built-in providers do not declare audio support, so `app.audio()` on them fails before any call; a custom `AiProvider` that declares `supportsAudio()` has the audio sent as LangChain4j `AudioContent` through the chat path.

---

## Audio Transcription

```java
var app = CafeAI.create();
app.ai(OpenAI.of("gpt-4o"));  // supportsAudio() = true
app.guard(GuardRail.pii());  // blocks a transcript that contains PII

// Plain transcription
AudioResponse transcript = app.audio(
    "Transcribe this customer support call verbatim.",
    audioBytes, "audio/wav").call();

System.out.println(transcript.text());  // the transcript
System.out.println(transcript.totalTokens());  // tokens consumed
```

The PII guardrail checks the transcript text — not the raw audio. If the caller mentions their phone number during the call, the guardrail catches it in the transcript and the response is replaced with a refusal instead of being returned to the application.

---

## Structured Extraction from Audio

`.call(Class)` applies to audio exactly as it does to text and vision:

```java
record CallSummary(
    String  customerName,
    String  issueType,       // BILLING, TECHNICAL, ACCOUNT, OTHER
    String  summary,
    boolean resolved,
    String  followUpAction
) {}

CallSummary summary = app.audio(
    "Extract the key details from this customer support call.",
    audioBytes, "audio/wav")
    .call(CallSummary.class);
```

Internally: Whisper transcribes the audio to text, then `gpt-4o` receives the transcript plus the schema hint and produces structured JSON. The developer sees one call that returns a typed record.

---

## Mixed-Modality Session Memory

The most instructive demo in `AudioTranscriptionExample` is Demo 4 — an audio call and a text call on the same session:

```java
// Audio call — transcribes the call and establishes session context
app.audio("Transcribe this support call and note the main customer complaint.",
    audioBytes, "audio/wav")
    .session("demo-session")
    .call();

// Text call — references the audio session context
var followUp = app.prompt(
    "Based on the support call we just reviewed, " +
    "draft a follow-up email to the customer.")
    .session("demo-session")  // same session — has the transcript
    .call();
```

The transcript from the audio call is stored in session memory as text. The subsequent text prompt loads that context and reasons about it. Audio bytes are never persisted — only the text of what was said.

This enables a pattern where a complex audio analysis produces a structured transcript, and subsequent text prompts operate on that transcript without re-transcribing the audio.

---

## Classification Prompt Engineering for PDFs

One practical finding from the `invoice-processor` validation: multi-page PDFs that combine an invoice with a packing list were initially misclassified as `PACKING_LIST`. The model read the first visible content and stopped.

The fix was a prompt instruction:

```
IMPORTANT: This document may be multi-page. Scan ALL pages before classifying.
A document that contains BOTH a packing list and an invoice should be classified
as isInvoice=true, because it contains billing information.
```

This is prompt engineering in the service of correctness — the classification failure was not a model failure, it was an incomplete instruction. The updated prompt produced correct `isInvoice=true` results for all three present test PDFs.

The lesson: vision prompts need the same engineering attention as text prompts. Explicit instructions about scope (scan all pages), handling of ambiguity (both invoice and packing list → classify as invoice), and output format (ONLY valid JSON) measurably affect results.

---

## Post 10 — Structured Output

Post 10 covers `.call(Class)` in depth — `SchemaHintBuilder`, `ResponseDeserializer`, how the schema hint is constructed from Java records, and why removing the boilerplate is more than a convenience.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
