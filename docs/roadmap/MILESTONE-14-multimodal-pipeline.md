# MILESTONE-14 — Multimodal Pipeline

> Tracks execution of ROADMAP-14. Each phase has a status and acceptance
> criteria checkboxes. Update this file as work completes.

**Current Status:** ✅ Complete

| Phase | Description | Module | Status | Target |
|-------|-------------|--------|--------|--------|
| Phase 1 | `VisionRequest` + `VisionResponse` types | `cafeai-core` | ✅ Complete | — |
| Phase 2 | `CafeAI.vision()` interface method | `cafeai-core` | ✅ Complete | — |
| Phase 3 | `CafeAIApp.vision()` implementation | `cafeai-core` | ✅ Complete | — |
| Phase 4 | LangChain4j message construction | `cafeai-core` | ✅ Complete | — |
| Phase 5 | Model capability check | `cafeai-core` + `cafeai-connect` | ✅ Complete | — |
| Phase 6 | Guardrails + observability | `cafeai-core` | 🔴 Not Started | — |
| Phase 7 | `atlas-inbox` refactor | `atlas-inbox` | ✅ Complete | — |
| Phase 8 | Tests + build verification | `cafeai-core` | ✅ Complete | — |

---

## Phase 1 — `VisionRequest` and `VisionResponse`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `VisionRequest.java` in `io.cafeai.core.ai` — fluent builder
- [ ] `VisionResponse.java` in `io.cafeai.core.ai` — mirrors `PromptResponse`
- [ ] `VisionRequest.VisionExecutor` functional interface defined
- [ ] `VisionRequest` validates: prompt, content, mimeType all non-null/non-blank
- [ ] `VisionRequest` is fluent: `session()`, `system()`, `call()` all return
      correct types for chaining
- [ ] Zero new dependencies introduced to `cafeai-core`
- [ ] `./gradlew :cafeai-core:compileJava` passes

### Key Design
```java
// Developer usage
VisionResponse r = app.vision("Is this an invoice?", pdfBytes, "application/pdf")
    .session(sessionId)
    .system("You are an invoice classifier.")
    .call();

String answer = r.text();       // "YES, this is a FedEx invoice"
String model  = r.modelId();    // "gpt-4o"
int tokens    = r.totalTokens();
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — `CafeAI.vision()` interface method

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `vision(String prompt, byte[] content, String mimeType)` added to `CafeAI` interface
- [ ] `VisionNotSupportedException` defined (nested on `VisionRequest`)
- [ ] Javadoc explains: RAG skipped, guardrails apply to text, provider must
      support vision
- [ ] `./gradlew :cafeai-core:compileJava` passes

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `CafeAIApp.vision()` implementation

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `CafeAIApp.vision()` implements the interface method
- [ ] PDF content: base64 data URI prefix applied correctly
      (`data:application/pdf;base64,<b64>`)
- [ ] Image content: `ImageContent.from(base64, mimeType)` used correctly
- [ ] Session history injected before the multimodal `UserMessage`
- [ ] Session updated after the call
- [ ] Guardrails applied (PRE_LLM on prompt text, POST_LLM on response)
- [ ] RAG retrieval explicitly skipped
- [ ] Observability event emitted
- [ ] `app.vision("prompt", pdfBytes, "application/pdf").call()` returns response
- [ ] `app.vision("prompt", imageBytes, "image/jpeg").call()` returns response

### Critical Implementation Note
`PdfFileContent.from()` requires a `data:` URI, NOT raw base64.
This was discovered in Capstone 4. The implementation must prepend:
```
data:application/pdf;base64,<base64-encoded-bytes>
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — LangChain4j message construction

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `VisionMessageBuilder` class written (package-private in `cafeai-core`)
- [ ] `application/pdf` → `PdfFileContent` with data URI
- [ ] `image/*` → `ImageContent` with base64
- [ ] Unknown MIME type → `UnsupportedContentTypeException` with clear message
- [ ] History messages prepended before multimodal `UserMessage`
- [ ] System message added first when non-null
- [ ] Unit tests for PDF, image, and unknown MIME type cases

### Notes
<!-- Add implementation notes here -->

---

## Phase 5 — Model capability check

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `supportsVision()` added to `AiProvider` interface (default `false`)
- [ ] `OpenAI.gpt4o()` returns `true` for `supportsVision()`
- [ ] `OpenAI.gpt4oMini()` returns `false` for `supportsVision()`
- [ ] `Ollama.llava()` factory method added, returns `true` for `supportsVision()`
- [ ] Calling `app.vision()` with non-vision provider throws
      `VisionNotSupportedException` with helpful message
- [ ] Exception message lists known vision-capable providers

### Error message format
```
VisionNotSupportedException: The registered provider 'gpt-4o-mini' does
not support vision/multimodal input. Use a vision-capable provider:
  app.ai(OpenAI.gpt4o())
  app.connect(Ollama.at("http://localhost:11434").model("llava"))
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 6 — Guardrails and observability

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] PRE_LLM guardrail fires on `VisionRequest.prompt()` text
- [ ] POST_LLM guardrail fires on `VisionResponse.text()`
- [ ] Guardrail block prevents the vision call from executing
- [ ] Observability event shows: model, mimeType, contentBytes, tokens, latency
- [ ] Session memory stores prompt as user message, response as assistant message
- [ ] RAG pipeline is NOT invoked for vision calls

### What applies vs what does not
| Feature | Applies to vision? |
|---------|-------------------|
| PRE_LLM guardrails | ✅ Yes — on text prompt |
| POST_LLM guardrails | ✅ Yes — on text response |
| Observability | ✅ Yes — with content metadata |
| Session memory | ✅ Yes — same as prompt() |
| RAG retrieval | ❌ No — skip entirely |
| Semantic cache | ❌ Not yet |

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — `atlas-inbox` refactor

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `MultimodalChatService.java` deleted
- [ ] `AttachmentTypeClassifier` rewritten — uses `app.vision()`
- [ ] `InvoiceDataExtractor` rewritten — uses `app.vision()`
- [ ] Direct `langchain4j-open-ai` dependency removed from `atlas-inbox/build.gradle`
- [ ] `./gradlew run -Pdry` completes with 0 errors
- [ ] Classification results match previous output (FedEx approved, discrepancies flagged)
- [ ] `DEMO.md` updated — multimodal calls now go through CafeAI pipeline

### Before / After
```java
// Before — MultimodalChatService bypasses CafeAI
var chat = new MultimodalChatService(SYSTEM_PROMPT);
var classification = new AttachmentTypeClassifier(chat);  // raw LangChain4j

// After — through CafeAI pipeline
var classification = new AttachmentTypeClassifier(app);   // app.vision() internally
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — Tests and build verification

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `VisionPipelineTest` written in `cafeai-core`
- [ ] All 12 test cases from ROADMAP-14 Phase 8 pass
- [ ] `./gradlew clean build` — BUILD SUCCESSFUL
- [ ] Total test count >= 323 (311 existing + ~12 new)
- [ ] Zero regressions in existing 311 tests

### Notes
<!-- Add build output here when complete -->

---

## Completion Definition

MILESTONE-14 is **complete** when:
1. All 8 phases show ✅ Complete
2. `app.vision(prompt, bytes, mimeType).call()` works for PDF and images
3. `atlas-inbox` has no `MultimodalChatService` and no direct LangChain4j
   model instantiation
4. Test count >= 323, BUILD SUCCESSFUL
5. The gap identified in the Capstone 4 gap analysis is closed

**What success looks like:**

```java
// atlas-inbox AtlasInboxProcessor.java — after Phase 7
// No MultimodalChatService. No direct OpenAiChatModel.
// Everything through app.vision().

var app = CafeAI.create();
app.ai(OpenAI.gpt4o());    // vision-capable provider
app.guard(GuardRail.jailbreak());
app.tool(new VendorContractLookup());

// Classification — through CafeAI pipeline
VisionResponse classification = app.vision(
    "Is this document a vendor invoice? Reply: {isInvoice: true/false, confidence: HIGH/MEDIUM/LOW}",
    pdfBytes, "application/pdf").call();

// Extraction — through CafeAI pipeline
VisionResponse extraction = app.vision(
    EXTRACT_PROMPT, pdfBytes, "application/pdf").call();
```

---

## Phase 9 — Structured output: `app.prompt().returning(Class)`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `returning(Class<T>)` added to `PromptRequest`
- [ ] Overloaded `call(Class<T>)` added to `PromptRequest`
- [ ] `SchemaHintBuilder` generates compact JSON example from class fields
- [ ] `ResponseDeserializer` strips fences and parses with Jackson
- [ ] Works with Java records and POJOs
- [ ] `StructuredOutputException` thrown with original text when parse fails
- [ ] `VisionRequest` gets the same API
- [ ] Existing `call()` behaviour unchanged

### Before / After
```java
// Before — every capstone
String raw = app.prompt(prompt).call().text();
String clean = raw.replaceAll("(?s)```json\\s*", "").trim();
SentimentResult result = MAPPER.readValue(clean, SentimentResult.class);

// After
SentimentResult result = app.prompt(prompt)
    .returning(SentimentResult.class)
    .call(SentimentResult.class);
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 10 — Token budget and rate limit handling

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `TokenBudget` record in `cafeai-core`
- [ ] `RetryPolicy` class in `cafeai-core`
- [ ] `app.budget()` and `app.retry()` added to `CafeAI` interface
- [ ] Token tracking in `CafeAIApp.executePrompt()`
- [ ] Automatic pause when budget window is exhausted
- [ ] Catch + retry on `RateLimitException` per `RetryPolicy`
- [ ] Same applied to vision calls (Phase 3)
- [ ] `atlas-inbox` `Thread.sleep` calls removed
- [ ] `app.budget(TokenBudget.perMinute(30_000))` wired in `atlas-inbox`

### Before / After
```java
// Before — atlas-inbox application code
Thread.sleep(15_000);   // rate limit pause between emails
Thread.sleep(10_000);   // pause before reconciliation

// After — framework handles it
app.budget(TokenBudget.perMinute(30_000));
app.retry(RetryPolicy.onRateLimit().maxAttempts(3).backoff(Duration.ofSeconds(2)));
// No Thread.sleep anywhere in application code
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 11 — POST_LLM guardrails on tool call output

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] POST_LLM guardrails applied to `toolBridge.executeWithTools()` response
- [ ] A clean input producing toxic tool-call output is blocked
- [ ] `atlas-inbox` EscalationTest jailbreak blocked by CafeAI guardrail
- [ ] No regressions in existing tool-calling tests
- [ ] At least 2 new tests covering POST_LLM tool-calling guardrail behaviour

### Current vs correct behaviour
```
Current:  input → [PRE_LLM] → tool loop → final response  (no POST_LLM)
Correct:  input → [PRE_LLM] → tool loop → final response → [POST_LLM]
```

### Fix location
`CafeAIApp.executePrompt()` — the `toolBridge.hasTools()` branch.
POST_LLM guardrail check already exists for the non-tool path.
Apply it to the tool-calling path too.

### Notes
<!-- Add implementation notes here -->

---

## Updated Summary Table

| Phase | Description | Module | Status |
|-------|-------------|--------|--------|
| Phase 1 | `VisionRequest` + `VisionResponse` types | `cafeai-core` | ✅ Complete |
| Phase 2 | `CafeAI.vision()` interface method | `cafeai-core` | ✅ Complete |
| Phase 3 | `CafeAIApp.vision()` implementation | `cafeai-core` | ✅ Complete |
| Phase 4 | LangChain4j message construction | `cafeai-core` | ✅ Complete |
| Phase 5 | Model capability check | `cafeai-core` + `cafeai-connect` | ✅ Complete |
| Phase 6 | Guardrails + observability on vision | `cafeai-core` | ✅ Complete |
| Phase 7 | `atlas-inbox` refactor | `atlas-inbox` | ✅ Complete |
| Phase 8 | Tests + build verification | `cafeai-core` | ✅ Complete |
| Phase 9 | Structured output `.returning(Class)` | `cafeai-core` | ✅ Complete |
| Phase 10 | Token budget + rate limit handling | `cafeai-core` | ✅ Complete |
| Phase 11 | POST_LLM guardrails on tool call output | `cafeai-core` | ✅ Complete |
