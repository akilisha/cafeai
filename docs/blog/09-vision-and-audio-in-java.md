# Vision and Audio in Java — Multimodal AI Without the Boilerplate

*Post 9 of 12 in the CafeAI series*

---

The first version of `atlas-inbox` worked. The Meridian Home Loans vendor invoice processor classified attachments, extracted invoice data, reconciled amounts, and drafted replies. It processed real vendor PDFs and produced correct decisions.

It also had a structural problem: the part that worked hardest — the multimodal classification and extraction — bypassed CafeAI entirely.

`MultimodalChatService` was a raw LangChain4j wrapper. It built its own `OpenAiChatModel`, managed its own base64 encoding, wrote its own fence-stripping parser. Guardrails did not fire on those calls. Observability did not trace them. The token budget did not apply. CafeAI was a satellite orbiting a sun that had nothing to do with the framework.

This was a framework gap, not a developer mistake. `app.prompt()` accepts a string. There was no CafeAI-native path for binary content.

ROADMAP-14 closed the gap. ROADMAP-15 extended it to audio. This post covers both.

---

## The Three Modalities

After ROADMAP-15, CafeAI has three modality entry points:

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

## The `atlas-inbox` Refactor

Before ROADMAP-14:

```java
// Bypasses CafeAI entirely — no guardrails, no observability, no budget
var chat = new MultimodalChatService(SYSTEM_PROMPT);  // raw LangChain4j
var classification = chat.promptWithPdf(buildPrompt(), pdfBytes);
String clean = response.replaceAll("(?s)```json\\s*", "").trim();
AttachmentClassification result = MAPPER.readValue(clean, AttachmentClassification.class);
```

After ROADMAP-14:

```java
// Through the CafeAI pipeline — guardrails, observability, budget, retry all apply
AttachmentClassification result = app.vision(buildPrompt(), pdfBytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);
```

`MultimodalChatService` was deleted. 80 lines of raw LangChain4j wiring removed. The boilerplate — base64 encoding, fence stripping, Jackson parsing — absorbed into the framework where it belongs.

The outcome is identical. The architecture is correct.

---

## Structured Output from Vision

The `.returning(Class)` pattern works identically for vision and text. `atlas-inbox` uses it for both classification and extraction:

```java
// Classify the attachment
AttachmentClassification classification = app.vision(classificationPrompt, pdfBytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);

// classification.isInvoice() → true
// classification.docType()   → "INVOICE"
// classification.confidence() → "HIGH"
// classification.reason()    → "Document contains billing totals and payment terms"

// Extract invoice data
InvoiceData invoice = app.vision(extractionPrompt, pdfBytes, "application/pdf")
    .returning(InvoiceData.class)
    .call(InvoiceData.class);

// invoice.vendorName()    → "Liberty Fastener Company"
// invoice.invoiceNumber() → "0212164-1"
// invoice.totalAmount()   → "1353.50"
// invoice.isComplete()    → true
```

Both `AttachmentClassification` and `InvoiceData` are plain Java records. No custom deserialisation. No schema definition files. `SchemaHintBuilder` reflects on the record at call time and appends the schema hint to the prompt automatically.

---

## Vision Provider Capability

Not every provider supports vision. CafeAI checks at call time:

```java
// supportsVision() = true
app.ai(OpenAI.gpt4o());        // gpt-4o supports vision
app.ai(Ollama.llava());        // LLaVA is a vision model

// supportsVision() = false — throws VisionNotSupportedException
app.ai(OpenAI.gpt4oMini());   // gpt-4o-mini does not support vision
app.ai(Ollama.llama3());       // llama3 is text-only
```

`VisionNotSupportedException` on the wrong provider is better than a cryptic 400 error from the OpenAI API. The error message names the registered provider and suggests vision-capable alternatives.

---

## The `app.audio()` Pipeline

Audio calls follow the same pipeline structure as vision, with one important routing difference:

```
OpenAI providers:
    → Direct HTTP to /v1/audio/transcriptions (Whisper multipart endpoint)
    → Optionally: transcript sent back through gpt-4o for reasoning/extraction

Gemini providers:
    → AudioContent via chat completions API (supported natively)
```

This routing exists because LangChain4j 1.11 does not serialise `AudioContent` to the `input_audio` format that OpenAI's chat completions API requires. The `AudioMessageBuilder` handles this transparently — OpenAI audio calls go to Whisper; Gemini audio calls go through the standard chat path. The developer writes the same `app.audio()` call either way.

The routing is documented honestly in the code — the comment in `AudioMessageBuilder` explains exactly what would need to change in LangChain4j to remove the Whisper direct-HTTP path.

---

## Audio Transcription

```java
var app = CafeAI.create();
app.ai(OpenAI.gpt4o());  // supportsAudio() = true
app.guard(GuardRail.pii());  // scrubs PII from transcripts

// Plain transcription
AudioResponse transcript = app.audio(
    "Transcribe this customer support call verbatim.",
    audioBytes, "audio/wav").call();

System.out.println(transcript.text());  // the transcript
System.out.println(transcript.totalTokens());  // tokens consumed
```

The PII guardrail fires on the transcript text — not on the raw audio. If the caller mentions their phone number during the call, the guardrail catches it in the transcript before the response is returned to the application.

---

## Structured Extraction from Audio

The `.returning()` pattern applies to audio exactly as it does to text and vision:

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
    .returning(CallSummary.class)
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

One practical finding from the `atlas-inbox` validation: multi-page PDFs that combine an invoice with a packing list were initially misclassified as `PACKING_LIST`. The model read the first visible content and stopped.

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

Post 10 covers the `.returning(Class).call(Class)` pattern in depth — `SchemaHintBuilder`, `ResponseDeserializer`, how the schema hint is constructed from Java records, and why removing the boilerplate is more than a convenience.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
