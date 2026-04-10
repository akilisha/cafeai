# MILESTONE-15 — Pipeline Completion

> Tracks execution of ROADMAP-15. Each phase has a status and acceptance
> criteria checkboxes. Update this file as work completes.

**Current Status:** 🔴 Not Started

| Phase | Description | Module | Status | Target |
|-------|-------------|--------|--------|--------|
| Phase 1 | `AudioRequest` + `AudioResponse` types | `cafeai-core` | 🔴 Not Started | — |
| Phase 2 | `CafeAI.audio()` interface method | `cafeai-core` | 🔴 Not Started | — |
| Phase 3 | `CafeAIApp.audio()` implementation | `cafeai-core` | 🔴 Not Started | — |
| Phase 4 | Provider audio support (`OpenAI.whisper()`) | `cafeai-core` | 🔴 Not Started | — |
| Phase 5 | `AudioTranscriptionExample` | `cafeai-examples` | 🔴 Not Started | — |
| Phase 6 | Streaming vision: `app.vision().stream()` | `cafeai-core` | 🔴 Not Started | — |
| Phase 7 | `atlas-inbox` streaming classification | `atlas-inbox` | 🔴 Not Started | — |
| Phase 8 | `atlas-inbox` dry-run validation | `atlas-inbox` | 🔴 Not Started | — |
| Phase 9 | Blog and conference series (12 posts) | `cafeai/docs/blog` | 🔴 Not Started | — |

---

## Phase 1 — `AudioRequest` and `AudioResponse`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AudioRequest.java` in `io.cafeai.core.ai` — mirrors `VisionRequest`
- [ ] `AudioResponse.java` in `io.cafeai.core.ai` — mirrors `VisionResponse`
- [ ] `AudioRequest.AudioExecutor` functional interface defined
- [ ] `AudioNotSupportedException` nested on `AudioRequest`
- [ ] `UnsupportedAudioFormatException` nested on `AudioRequest`
- [ ] Validation: prompt, content, mimeType all non-null/non-blank
- [ ] `.returning(Class<T>)` and `.call(Class<T>)` supported
- [ ] Fluent chain: `session()`, `system()`, `request()` all return `this`
- [ ] Zero new dependencies in `cafeai-core`
- [ ] `./gradlew :cafeai-core:compileJava` passes

### Key Design
```java
// Transcription
AudioResponse transcript = app.audio(
    "Transcribe this call.", audioBytes, "audio/wav").call();

// Structured extraction
CallSummary summary = app.audio(
    "Extract action items.", audioBytes, "audio/mp3")
    .returning(CallSummary.class)
    .call(CallSummary.class);
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — `CafeAI.audio()` interface method

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `audio(String prompt, byte[] content, String mimeType)` added to `CafeAI`
- [ ] Javadoc explains pipeline differences from `prompt()` and `vision()`
- [ ] Stub `executeAudio()` in `CafeAIApp` throws `UnsupportedOperationException`
      with message pointing to Phase 3
- [ ] `./gradlew :cafeai-core:compileJava` passes

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `CafeAIApp.audio()` implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AudioMessageBuilder` written — package-private in `internal`
- [ ] Supported: `audio/wav`, `audio/mp3`, `audio/ogg`, `audio/m4a`, `audio/flac`
- [ ] Unsupported MIME → `UnsupportedAudioFormatException` with clear message
- [ ] `executeAudio()` implements full 12-step pipeline (mirrors `executeVision`)
- [ ] `beforeAudio`/`afterAudio` default hooks added to `ObserveBridge`
- [ ] `ObserveBridgeImpl` implements both hooks (console + OTel)
- [ ] Session memory stores text only — never audio bytes
- [ ] Token budget and retry apply to audio calls
- [ ] PRE_LLM and POST_LLM guardrails fire on text
- [ ] `./gradlew :cafeai-core:test` — all existing tests pass

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — Provider audio support

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `supportsAudio()` default method added to `AiProvider` (returns `false`)
- [ ] `OpenAI.whisper()` factory method added
- [ ] `OpenAI.gpt4o()` returns `true` for `supportsAudio()`
- [ ] `OpenAI.gpt4oMini()` returns `false` for `supportsAudio()`
- [ ] `CafeAIApp.executeAudio()` checks `supportsAudio()` before proceeding
- [ ] `AudioNotSupportedException` message lists known audio-capable providers
- [ ] New tests covering audio provider capability checks

### Error Message Format
```
AudioNotSupportedException: The registered provider 'gpt-4o-mini' does
not support audio input. Use an audio-capable provider:
  app.ai(OpenAI.whisper())   -- dedicated transcription model
  app.ai(OpenAI.gpt4o())     -- multimodal (text + vision + audio)
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 5 — `AudioTranscriptionExample`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AudioTranscriptionExample.java` in `cafeai-examples`
- [ ] Demonstrates both transcription and structured extraction
- [ ] Sample audio file in `src/test/resources/` (synthetic — not a real recording)
- [ ] PII guardrail fires when phone number appears in transcript
- [ ] `cafeai-examples/audio/README.md` with prerequisites and walkthrough
- [ ] `./gradlew :cafeai-examples:compileJava` passes

### Teaching Goal
A developer reading this example should understand:
- When to use `app.audio()` vs `app.prompt()` (modality determines the entry point)
- How `.returning(Class)` applies to audio just like vision and text
- That guardrails on transcripts work identically to guardrails on text prompts

### Notes
<!-- Add implementation notes here -->

---

## Phase 6 — Streaming vision: `app.vision().stream()`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `stream(Consumer<String> onChunk)` added to `VisionRequest`
- [ ] `executeVisionStream()` implemented in `CafeAIApp`
- [ ] Token budget tracking applies to streaming calls
- [ ] POST_LLM guardrails apply to assembled stream before first chunk
- [ ] `afterVisionStream()` observability hook added
- [ ] Existing `call()` behaviour unchanged
- [ ] `./gradlew :cafeai-core:test` — all existing 359 tests pass

### When to use stream() vs call()
```java
// Use call() when you need the complete response before processing
// (structured output, JSON parsing, decision-making)
AttachmentClassification r = app.vision(prompt, bytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);

// Use stream() when you want progressive display while waiting
app.vision(prompt, bytes, "application/pdf")
    .stream(chunk -> res.write(chunk));
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — `atlas-inbox` streaming classification

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AttachmentTypeClassifier` has a streaming variant alongside structured
- [ ] `--stream` flag in `AtlasInboxProcessor` switches to streaming mode
- [ ] Progressive output visible during `./gradlew run -Pdry --stream`
- [ ] Structured output path unchanged and still passes `testClassification`
- [ ] `README.md` updated with streaming section

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — `atlas-inbox` dry-run validation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `./gradlew testClassification` passes with real PDFs:
  - `liberty-fastener-324119.pdf` → `isInvoice=true`, `INVOICE`
  - `kso-metalfab-64485.pdf` → `isInvoice=true`
  - `ultratech-91560.pdf` → `isInvoice=true`, `INVOICE`
  - `heiden-221914.pdf` → `isInvoice=true`, `INVOICE`
- [ ] `./gradlew testExtraction` passes:
  - Liberty Fastener: `vendorName`, `invoiceNumber`, `totalAmount` non-null
  - Heiden: handwritten fields extracted correctly
- [ ] `./gradlew testPipeline` passes:
  - Pipeline A (Liberty Fastener) produces `DISCREPANCY` decision
  - Pipeline B (Heiden) produces `APPROVED` decision
- [ ] `./gradlew run -Pdry` completes with 0 errors
- [ ] Results documented in `VALIDATION.md` in `atlas-inbox`

### Validation Document Format
```markdown
# atlas-inbox Validation Report
Date: YYYY-MM-DD
CafeAI version: 0.1.0-SNAPSHOT
Pipeline: app.vision() (ROADMAP-14 Phase 7 refactor)

## Classification Results
| Document | Expected | Actual | Pass |
| ...

## Extraction Results
| Document | Field | Expected | Actual | Pass |
| ...

## Dry-Run Summary
Emails processed: N
Approvals: N | Discrepancies: N | Escalations: N | Errors: 0
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 9 — Blog and Conference Series

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] 12 markdown files in `cafeai/docs/blog/`:
  - [ ] `01-brewing-ai-in-java.md`
  - [ ] `02-middleware-pattern-meets-gen-ai.md`
  - [ ] `03-first-llm-call-without-spring-boot.md`
  - [ ] `04-prompt-engineering-in-java.md`
  - [ ] `05-context-memory-without-cloud-tax.md`
  - [ ] `06-building-rag-pipeline-in-java.md`
  - [ ] `07-tool-use-in-java.md`
  - [ ] `08-ethical-guardrails-as-middleware.md`
  - [ ] `09-vision-and-audio-in-java.md`
  - [ ] `10-structured-output.md`
  - [ ] `11-production-grade-ai.md`
  - [ ] `12-the-capstone-series.md`
- [ ] Each post is 1,500–2,500 words, standalone, publish-ready
- [ ] All code examples in each post compile against `cafeai-core:0.1.0-SNAPSHOT`
- [ ] Each post links to a specific runnable example or capstone
- [ ] Post 12 links to `CAFEAI-CAPSTONE-SERIES.md`
- [ ] No placeholder sections ("TBD", "coming soon", etc.)

### Priority Order
Write in this order — earlier posts establish the narrative foundation:
1. Post 1 (philosophy) — sets the tone for everything
2. Post 3 (first LLM call) — the entry point most readers will want
3. Post 2 (middleware pattern) — the architectural argument
4. Posts 5, 6, 7, 8 (memory, RAG, tools, guardrails) — the core ladder
5. Post 9 (vision + audio) — multimodal story
6. Post 10, 11 (structured output, production) — advanced capabilities
7. Post 4 (prompt engineering) — can go anywhere
8. Post 12 (capstone series) — last, ties everything together

### Notes
<!-- Add implementation notes here -->

---

## Completion Definition

MILESTONE-15 is **complete** when:

1. All 9 phases show ✅ Complete
2. Test count >= 380 (359 + new audio + streaming tests)
3. `./gradlew clean build` — BUILD SUCCESSFUL
4. `atlas-inbox` dry-run validation documented in `VALIDATION.md`
5. 12 blog posts exist in `cafeai/docs/blog/`
6. `app.audio()`, `app.vision().stream()` both callable from `atlas-inbox`

**What success looks like:**

```java
// All three modalities, one coherent API
PromptResponse  text      = app.prompt("Summarise this ticket").call();
VisionResponse  vision    = app.vision("Is this an invoice?", pdfBytes, "application/pdf").call();
AudioResponse   audio     = app.audio("Transcribe this call", wavBytes, "audio/wav").call();

// All support structured output
SentimentResult sentiment = app.prompt(prompt).returning(SentimentResult.class).call(SentimentResult.class);
InvoiceData     invoice   = app.vision(prompt, pdf, "application/pdf").returning(InvoiceData.class).call(InvoiceData.class);
CallSummary     summary   = app.audio(prompt, wav, "audio/wav").returning(CallSummary.class).call(CallSummary.class);

// All through the same pipeline — guardrails, observability, budget, retry
```
