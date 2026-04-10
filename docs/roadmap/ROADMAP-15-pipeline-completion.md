# ROADMAP-15 — Pipeline Completion

> Completes the work that ROADMAP-14 started but deferred.
> These phases were always implicit in the multimodal story —
> `app.audio()` is the third modality, streaming vision is the
> natural completion of the streaming story, atlas-inbox validation
> is Phase 7's missing acceptance test, and the blog series is the
> documentation debt the framework has been accumulating since Capstone 1.
>
> They are here rather than in ROADMAP-14 only because the work
> in that milestone was already substantial enough.

---

## Context

ROADMAP-14 delivered `app.vision()`, structured output, token budget,
POST_LLM guardrails, and the atlas-inbox refactor. At the end of that
work, four loose threads remained:

**Thread 1 — `app.audio()`** was explicitly called out in ROADMAP-14's
"What this roadmap does NOT cover" section, deferred until vision was
proven. Vision is now proven. Audio is the natural next modality.

**Thread 2 — Streaming vision** was also deferred. `app.vision().call()`
is synchronous — it blocks until the full response is available. For
long classification or extraction calls, the developer gets nothing
until the model finishes. `app.vision().stream()` fixes this.

**Thread 3 — atlas-inbox dry-run validation** is Phase 7's missing
acceptance criteria. The phase was declared complete when the code
compiled and the refactor was structurally correct, but it was never
run against real PDFs with real API calls to confirm the vision pipeline
produces the same outcomes as `MultimodalChatService` did. That
verification is outstanding.

**Thread 4 — Blog series** is the documentation debt accumulated across
14 roadmap items and four capstones. The SPEC has promised a 12-part
blog and conference series since the beginning. Nothing has been written.
The framework is now mature enough to write it honestly.

---

## Dependency Map

```
Phase 1  (AudioRequest + AudioResponse types)
    └── Phase 2  (CafeAI.audio() interface)
    └── Phase 3  (CafeAIApp.audio() implementation)
        └── Phase 4  (Whisper provider support)
            └── Phase 5  (atlas-inbox transcription demo)

Phase 6  (VisionRequest.stream() — streaming vision responses)
    └── Phase 7  (atlas-inbox streaming classification)

Phase 8  (atlas-inbox dry-run validation — real PDFs)

Phase 9  (Blog series — 12 posts)
    └── depends on all prior phases being stable
```

Phases 1–5 (audio pipeline) are sequential.
Phase 6–7 (streaming vision) are independent of audio.
Phase 8 (dry-run validation) is independent of all.
Phase 9 (blog series) depends on the framework being stable — write
after Phase 8 confirms end-to-end correctness.

---

## Phase 1 — `AudioRequest` and `AudioResponse`

**Goal:** Define the core types for audio calls. Mirror `VisionRequest`
and `VisionResponse` in structure so the pattern is immediately familiar.

**Module:** `cafeai-core`

**New files:**
- `io.cafeai.core.ai.AudioRequest`
- `io.cafeai.core.ai.AudioResponse`

**`AudioRequest` API:**

```java
// Transcription
AudioResponse response = app.audio(
    "Transcribe this customer support call.",
    audioBytes, "audio/wav").call();

// Transcription with session memory
AudioResponse response = app.audio(
    "Transcribe and summarise this call.",
    audioBytes, "audio/mp3")
    .session(sessionId)
    .call();

// Structured output
CallSummary summary = app.audio(
    "Extract action items from this meeting recording.",
    audioBytes, "audio/wav")
    .returning(CallSummary.class)
    .call(CallSummary.class);
```

**`AudioRequest` fields:**
- `String prompt` — the instruction (transcribe, summarise, extract, etc.)
- `byte[] content` — the audio payload
- `String mimeType` — `"audio/wav"`, `"audio/mp3"`, `"audio/ogg"`, `"audio/m4a"`
- `String sessionId` — optional
- `String systemOverride` — optional
- `Class<?> returningType` / `String schemaHint` — structured output support

**`AudioResponse` fields:**
- `String text()` — transcript or model response
- `String modelId()` — which model answered
- `int promptTokens()`, `int outputTokens()`, `int totalTokens()`
- `boolean fromCache()` — reserved, always false

**Pipeline differences from `app.prompt()`:**
- RAG retrieval skipped — audio bytes cannot be embedded
- Guardrails apply to the text transcript/response, not the audio
- Session memory stores the prompt and response text only (not audio bytes)
- Provider must declare `supportsAudio() = true`
- Transcription may be a two-step call: audio → text → prompt

**Tasks:**
- [ ] Write `AudioRequest.java` — mirrors `VisionRequest` structure exactly
- [ ] Write `AudioResponse.java` — mirrors `VisionResponse` structure
- [ ] `AudioRequest.AudioExecutor` functional interface
- [ ] `AudioNotSupportedException` nested on `AudioRequest`
- [ ] `UnsupportedAudioFormatException` nested on `AudioRequest`
- [ ] Validation: prompt not null/blank, content not null/empty,
      mimeType not null/blank
- [ ] `AudioRequest` supports `.returning(Class<T>)` and `.call(Class<T>)`
- [ ] `./gradlew :cafeai-core:compileJava` passes

**Acceptance criteria:**
- [ ] `AudioRequest` and `AudioResponse` compile in `cafeai-core`
- [ ] Zero new dependencies required
- [ ] Structure mirrors `VisionRequest`/`VisionResponse` exactly

---

## Phase 2 — `CafeAI.audio()` interface method

**Goal:** Add `audio()` to the `CafeAI` interface alongside `prompt()`
and `vision()`. The three modalities side by side.

**Module:** `cafeai-core`

**New method:**

```java
/**
 * Creates an audio request for the registered LLM provider.
 *
 * <p>Audio calls send binary audio content alongside a text prompt
 * to a model capable of speech understanding. The audio pipeline:
 * <ul>
 *   <li>RAG retrieval is skipped — audio cannot be embedded</li>
 *   <li>PRE_LLM and POST_LLM guardrails apply to the text</li>
 *   <li>Session memory stores the text prompt and response only</li>
 *   <li>The registered provider must declare supportsAudio() = true</li>
 * </ul>
 *
 * <pre>{@code
 *   // Transcription
 *   AudioResponse r = app.audio(
 *       "Transcribe this call.", audioBytes, "audio/wav").call();
 *
 *   // Structured extraction
 *   CallSummary s = app.audio(
 *       "Extract action items.", audioBytes, "audio/mp3")
 *       .returning(CallSummary.class)
 *       .call(CallSummary.class);
 * }</pre>
 *
 * @param prompt   the text instruction for the model
 * @param content  the audio bytes
 * @param mimeType MIME type, e.g. "audio/wav", "audio/mp3"
 * @throws AudioRequest.AudioNotSupportedException if the provider
 *         does not support audio input
 */
io.cafeai.core.ai.AudioRequest audio(String prompt, byte[] content, String mimeType);
```

**Tasks:**
- [ ] Add `audio()` to `CafeAI` interface
- [ ] Add stub `executeAudio()` to `CafeAIApp` delegating to
      `this::executeAudio` (throws `UnsupportedOperationException`
      pointing to Phase 3)
- [ ] `./gradlew :cafeai-core:compileJava` passes

**Acceptance criteria:**
- [ ] `app.audio()` is callable and type-safe
- [ ] Stub throws a clear error pointing to Phase 3

---

## Phase 3 — `CafeAIApp.audio()` implementation

**Goal:** Full `executeAudio()` implementation. Wire the audio pipeline
identically to `executeVision()` — same guardrail, observability,
token budget, retry, session memory pattern.

**Module:** `cafeai-core`

**Implementation pipeline:**

```
1. Validate provider supports audio
2. Apply PRE_LLM guardrails to prompt text
3. Build session history (text messages only)
4. Determine system prompt
5. Append schema hint if returning(Class) is set
6. Build LangChain4j message list via AudioMessageBuilder
7. Call model (with budget + retry)
8. Record observability via beforeAudio/afterAudio hooks
9. Apply POST_LLM guardrails to response text
10. Set LLM_RESPONSE_TEXT for HTTP middleware
11. Persist to session memory
12. Return AudioResponse
```

**New class:** `AudioMessageBuilder` — package-private in `cafeai-core`

```java
// OpenAI audio format:
// UserMessage with AudioContent — parallel to ImageContent and PdfFileContent
List<ChatMessage> build(String prompt, byte[] content, String mimeType,
                         String systemPrompt, List<ChatMessage> history);
```

**Supported MIME types:**
- `audio/wav`, `audio/wave` → WAV
- `audio/mp3`, `audio/mpeg` → MP3
- `audio/ogg` → OGG
- `audio/m4a` → M4A (AAC)
- `audio/flac` → FLAC
- All others → `UnsupportedAudioFormatException`

**Tasks:**
- [ ] Write `AudioMessageBuilder.java` — package-private in `internal`
- [ ] Implement `executeAudio()` in `CafeAIApp`
- [ ] Add `beforeAudio`/`afterAudio` default hooks to `ObserveBridge`
- [ ] Implement `beforeAudio`/`afterAudio` in `ObserveBridgeImpl`
      (console + OTel, mirrors vision observability)
- [ ] `app.audio()` with mock audio provider returns `AudioResponse`
- [ ] Session memory stores transcript, not audio bytes

**Acceptance criteria:**
- [ ] `app.audio("transcribe this", wavBytes, "audio/wav").call()`
      returns an `AudioResponse`
- [ ] PRE_LLM guardrails fire on the text prompt
- [ ] POST_LLM guardrails fire on the response
- [ ] Session memory stores text only
- [ ] Observability console shows audio calls with MIME type and byte count
- [ ] `./gradlew :cafeai-core:test` — all existing tests still pass

---

## Phase 4 — Provider audio support

**Goal:** Add `supportsAudio()` to `AiProvider`, override in
audio-capable providers, add Whisper support.

**Module:** `cafeai-core`

**Design:**

```java
// AiProvider interface — default false
default boolean supportsAudio() { return false; }

// OpenAI — whisper-1 supports audio
// gpt-4o also supports audio input (as of late 2024)
OpenAI.gpt4o()     → supportsAudio() = true
OpenAI.whisper()   → new factory method, purpose-built for transcription

// Anthropic — no audio support yet
// Ollama — depends on model, default false
```

**New factory method:**

```java
// OpenAI.java
/**
 * OpenAI Whisper — dedicated transcription model.
 * Supports audio/wav, audio/mp3, audio/ogg, audio/m4a, audio/flac.
 *
 * <pre>{@code
 *   app.ai(OpenAI.whisper());
 *   AudioResponse r = app.audio(
 *       "Transcribe this call.", audioBytes, "audio/wav").call();
 * }</pre>
 */
public static AiProvider whisper() {
    return new OpenAiAudioProvider("whisper-1");
}
```

**Error message when provider doesn't support audio:**

```
AudioNotSupportedException: The registered provider 'claude-3-5-sonnet'
does not support audio input. Use an audio-capable provider:
  app.ai(OpenAI.whisper())      -- dedicated transcription
  app.ai(OpenAI.gpt4o())        -- multimodal (text + vision + audio)
```

**Tasks:**
- [ ] Add `supportsAudio()` default to `AiProvider` (returns `false`)
- [ ] Add `OpenAI.whisper()` factory with `OpenAiAudioProvider` record
- [ ] `OpenAI.gpt4o()` overrides `supportsAudio()` to return `true`
- [ ] `CafeAIApp.executeAudio()` checks `supportsAudio()` before proceeding
- [ ] Error message lists known audio-capable providers

**Acceptance criteria:**
- [ ] `OpenAI.whisper().supportsAudio()` returns `true`
- [ ] `OpenAI.gpt4oMini().supportsAudio()` returns `false`
- [ ] Calling `app.audio()` with an unsupported provider throws
      `AudioNotSupportedException` with a helpful message
- [ ] New tests in `VisionPipelineTest` (or new `AudioPipelineTest`):
      audio provider capability checks

---

## Phase 5 — `cafeai-examples`: `AudioTranscriptionExample`

**Goal:** A standalone, runnable example in `cafeai-examples`
demonstrating `app.audio()` for call transcription — the most common
real-world audio use case.

**Module:** `cafeai-examples`

**What the example demonstrates:**

```java
var app = CafeAI.create();
app.ai(OpenAI.whisper());             // or OpenAI.gpt4o() for multimodal
app.system("You are a transcription assistant...");
app.guard(GuardRail.pii());           // scrub PII from transcripts
app.observe(ObserveStrategy.console());

// Simple transcription
AudioResponse transcript = app.audio(
    "Transcribe this customer support call verbatim.",
    Files.readAllBytes(Path.of("call-recording.wav")),
    "audio/wav").call();

// Structured extraction from audio
CallSummary summary = app.audio(
    "Extract the key decisions and action items from this meeting.",
    Files.readAllBytes(Path.of("meeting.mp3")),
    "audio/mp3")
    .returning(CallSummary.class)
    .call(CallSummary.class);

record CallSummary(
    List<String> actionItems,
    List<String> decisions,
    String      sentiment,
    int         durationMinutes
) {}
```

**Tasks:**
- [ ] Write `AudioTranscriptionExample.java`
- [ ] Include a short sample WAV file in `src/test/resources/`
      (synthetic — a few seconds of silence or a beep) so the example
      compiles and runs without real audio
- [ ] `README.md` in `cafeai-examples/audio/` with:
      - Prerequisites (OpenAI API key with Whisper access)
      - What the example demonstrates
      - How to swap in your own audio file
- [ ] `./gradlew :cafeai-examples:compileJava` passes

**Acceptance criteria:**
- [ ] Example compiles and starts without error
- [ ] `CallSummary` structured extraction produces a populated record
- [ ] PII guardrail fires if a phone number appears in the transcript

---

## Phase 6 — Streaming vision: `app.vision().stream()`

**Goal:** Add streaming support to `VisionRequest` so vision calls
can stream tokens as they are generated, rather than blocking until
the full response is available. Classification and extraction calls
can take 3–8 seconds — streaming gives the user something immediately.

**Module:** `cafeai-core`

**Developer API:**

```java
// Current — blocks until complete
VisionResponse r = app.vision(prompt, pdfBytes, "application/pdf").call();

// New — streams tokens as generated
app.vision(prompt, pdfBytes, "application/pdf")
   .stream(chunk -> res.write(chunk));  // callback per token

// Or with SSE
app.vision(prompt, imageBytes, "image/jpeg")
   .stream(res.sseEmitter());
```

**Design constraints:**
- `stream()` is only meaningful for text generation responses
  (classification, description, extraction). It is not useful for
  Whisper-style transcription which produces complete output only.
- The existing `app.prompt().stream()` pattern should be the model —
  reuse whatever streaming infrastructure exists there.
- `VisionResponse` is not returned from `stream()` — the response
  is consumed via callback or emitter.

**Tasks:**
- [ ] Read current `app.prompt().stream()` implementation to understand
      the existing streaming pattern
- [ ] Add `stream(Consumer<String> onChunk)` to `VisionRequest`
- [ ] Implement `executeVisionStream()` in `CafeAIApp`
- [ ] Token budget tracking applies to streaming calls
- [ ] POST_LLM guardrails apply to the assembled stream before
      the first chunk is emitted (or at assembly — decide which)
- [ ] Add `ObserveBridge.afterVisionStream()` hook

**Acceptance criteria:**
- [ ] `app.vision(prompt, bytes, mimeType).stream(chunk -> ...)` compiles
- [ ] Tokens arrive progressively in mock tests
- [ ] Token budget tracks usage correctly for streamed calls
- [ ] `./gradlew :cafeai-core:test` — all existing tests still pass

---

## Phase 7 — `atlas-inbox` streaming classification

**Goal:** Demonstrate `app.vision().stream()` in `atlas-inbox` for
the attachment classification step — the call where progressive
feedback is most valuable because the user is waiting for a
yes/no decision on whether to proceed with extraction.

**Module:** standalone `atlas-inbox` capstone

**What changes:**

```java
// Before — blocks for 3-5s per attachment
AttachmentClassification result = app.vision(prompt, pdfBytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);

// After — prints reasoning as it arrives, then parses the final JSON
System.out.print("    Classifying: ");
app.vision(prompt, pdfBytes, "application/pdf")
    .stream(chunk -> System.out.print(chunk));
System.out.println();
// For structured output, use .call() — streaming is for UX,
// not for cases where you need the full JSON at once
```

**Note:** structured output (`.returning(Class).call(Class)`) requires
the complete response before parsing. Streaming and structured output
serve different needs. The example should show both and explain when
to use each.

**Tasks:**
- [ ] Update `AttachmentTypeClassifier` to show streaming variant
      alongside the existing structured output variant
- [ ] Add a `--stream` flag to `AtlasInboxProcessor` to demonstrate
      the difference
- [ ] `README.md` updated with streaming section

**Acceptance criteria:**
- [ ] `./gradlew run -Pdry --stream` produces progressive output
      during classification
- [ ] Structured output path still works unchanged

---

## Phase 8 — `atlas-inbox` dry-run validation

**Goal:** Run the full `atlas-inbox` pipeline against real vendor PDFs
with real API calls and confirm the vision pipeline (`app.vision()`)
produces the same classification and extraction outcomes as
`MultimodalChatService` did before the refactor.

**Module:** standalone `atlas-inbox` capstone

**This is the missing acceptance test for ROADMAP-14 Phase 7.**
The refactor was declared complete when the code compiled correctly.
The proof that it works correctly is a real dry run with real PDFs.

**What to verify:**

| Document | Expected classification | Expected extraction |
|----------|------------------------|---------------------|
| `liberty-fastener-324119.pdf` | `isInvoice=true`, `INVOICE` | vendorName, invoiceNumber, totalAmount populated |
| `kso-metalfab-64485.pdf` | `isInvoice=true`, `PURCHASE_ORDER` | key fields populated |
| `ultratech-91560.pdf` | `isInvoice=true`, `INVOICE` | fields from scanned/handwritten doc |
| `heiden-221914.pdf` | `isInvoice=true`, `INVOICE` | fields including AP stamps |

**Run:**

```bash
./gradlew testClassification    # AttachmentClassificationTest
./gradlew testExtraction        # InvoiceExtractionTest
./gradlew testPipeline          # ReconciliationPipelineTest
./gradlew run -Pdry             # Full pipeline, no emails sent
```

**Success criteria:**
- [ ] All four PDFs classify correctly (match expected `isInvoice` and `docType`)
- [ ] `liberty-fastener` extraction: `vendorName`, `invoiceNumber`, `totalAmount` non-null
- [ ] `heiden` extraction: handwritten fields extracted correctly
- [ ] Full dry-run completes with 0 errors
- [ ] APPROVED/DISCREPANCY decisions match prior run with `MultimodalChatService`
- [ ] Results documented in `VALIDATION.md` in the `atlas-inbox` repo

---

## Phase 9 — Blog and Conference Series

**Goal:** Write the 12-part blog series that the SPEC has promised since
the beginning. The framework is now mature enough to write it honestly —
every post can link to a working capstone that proves the claim.

**Module:** `cafeai/docs/blog/`

**The 12 posts:**

| # | Title | Primary Capstone | Framework Feature |
|---|-------|-----------------|-------------------|
| 1 | **Brewing AI in Java** — CafeAI Introduction and Philosophy | All | Why CafeAI exists |
| 2 | **The Middleware Pattern Meets Gen AI** — From Express to CafeAI | C1 | Pipeline architecture |
| 3 | **Your First LLM Call Without Spring Boot** — Helidon SE + LangChain4j | C1 | `app.ai()`, `app.prompt()` |
| 4 | **Prompt Engineering in Java** — Templates, System Prompts, API Vocabulary | C1, C2 | `app.template()`, `app.system()` |
| 5 | **Context Memory Without the Cloud Tax** — Java FFM and Tiered Memory | C2, C3 | `app.memory()`, all rungs |
| 6 | **Building a RAG Pipeline in Java** — Ingestion, Embedding, Retrieval | C1, C3 | `app.rag()`, `app.ingest()` |
| 7 | **Tool Use in Java** — Giving the AI Actions to Take | C1, C2, C3 | `@CafeAITool`, `app.tool()` |
| 8 | **Ethical Guardrails as Middleware** — PII, Jailbreak, Bias, Hallucination | C1, C2, C3 | `app.guard()` |
| 9 | **Vision and Audio in Java** — Multimodal AI Without the Boilerplate | C4 | `app.vision()`, `app.audio()` |
| 10 | **Structured Output** — Typed LLM Responses, No Parser Required | C2, C3, C4 | `.returning(Class)` |
| 11 | **Production-Grade AI** — Token Budgets, Retries, Observability | C4 | `app.budget()`, `app.retry()`, `app.observe()` |
| 12 | **The Capstone Series** — Four Applications, One Framework | All | End-to-end narrative |

**Format for each post:**
- 1,500–2,500 words
- One primary code example that is self-contained and runnable
- Linked to a specific capstone or `cafeai-examples` example
- One "before CafeAI" vs "with CafeAI" comparison where relevant
- One teaching moment that couldn't be conveyed by documentation alone

**Output location:**
- Draft markdown files in `cafeai/docs/blog/`
- Named `01-brewing-ai-in-java.md` through `12-the-capstone-series.md`
- Each file is publish-ready — no placeholder sections

**Tasks:**
- [ ] Write all 12 posts as draft markdown files
- [ ] Each post reviewed for technical accuracy against the actual codebase
- [ ] Code examples in each post verified to compile
- [ ] `CAFEAI-CAPSTONE-SERIES.md` (the unified README) updated to link
      to individual posts as they are published

**Acceptance criteria:**
- [ ] 12 markdown files in `cafeai/docs/blog/`
- [ ] Each post is a standalone readable article (no "TBD" sections)
- [ ] All code examples compile against `cafeai-core:0.1.0-SNAPSHOT`
- [ ] Post 12 ties back to `CAFEAI-CAPSTONE-SERIES.md`

---

## What this roadmap does NOT cover

- **`app.image()` generation** — generating images via DALL-E or
  Stable Diffusion. A different capability from vision understanding.
  Separate roadmap when the time is right.

- **Real-time audio** — streaming audio input (live transcription
  of a phone call in progress). Requires WebSocket audio streaming
  and a fundamentally different pipeline model. Deferred.

- **Multi-modal RAG** — embedding images and audio into the vector
  store for semantic retrieval. Requires multi-modal embedding models
  and a different chunking strategy. Deferred.

- **Conference talk preparation** — slide decks, demos, and speaker
  notes for presenting CafeAI. Follows naturally from the blog series
  but is out of scope for this roadmap.
