# ROADMAP-14 — Multimodal Pipeline

> Adds first-class multimodal entry points to CafeAI's pipeline.
> Closes the architectural gap exposed by Capstone 4 (`atlas-inbox`),
> where the entire vision workload bypassed `app.prompt()` entirely
> because there was no CafeAI-native way to pass binary content to the LLM.

---

## Context

### The gap

`app.prompt(String)` accepts a text message. That is its contract and it
should stay that way. The problem is that vision workloads — classifying
a PDF, extracting data from a scanned invoice, describing an image — need
to send binary content alongside a text prompt. There is no CafeAI path
for this today.

The consequence: Capstone 4 (`atlas-inbox`) built `MultimodalChatService`
as a raw LangChain4j wrapper outside the CafeAI pipeline. Classification,
extraction, and invoice processing all bypass guardrails, observability,
and session threading. CafeAI is marginally involved in its own most
ambitious capstone.

### Why dedicated entry points, not an extended `app.prompt()`

A single entry point that handles both text and binary content obscures
the modality at the call site. The developer has to read documentation
to know whether guardrails apply, whether session memory is threaded,
whether the registered provider supports the content type.

Dedicated entry points make the contract explicit:

```java
app.prompt("classify this")          // text — full pipeline, all features
app.vision("is this an invoice?",    // vision — binary + text, vision pipeline
    pdfBytes, "application/pdf")
app.audio("transcribe this call",    // future — audio pipeline
    audioBytes, "audio/wav")
```

Each name is a modality. Each modality has a clear, distinct contract.
A developer reading unfamiliar code knows immediately what each line does.
This follows the CafeAI naming philosophy: names are guessable, no
abbreviations, no string enums.

The increase in API surface area is real and intentional. Each addition
is a new capability with a distinct contract — not a variation on an
existing one.

### Pipeline differences

Vision calls require a model that supports multimodal input (e.g.
`gpt-4o`, not `gpt-4o-mini`). CafeAI should either route to a
vision-capable model automatically or surface a clear error if the
registered provider does not support vision.

Vision calls also do not meaningfully benefit from RAG retrieval — you
cannot embed binary content and compare it to text embeddings. The
vision pipeline skips RAG retrieval and injects the binary content
directly into the LLM message list.

---

## Dependency Map

```
Phase 1  (VisionRequest + VisionResponse — core types)
    └── Phase 2  (CafeAI.vision() interface method)
    └── Phase 3  (CafeAIApp.vision() implementation)
        └── Phase 4  (LangChain4j message builder — PDF + image content)
            └── Phase 5  (Model capability check — vision-capable routing)
                └── Phase 6  (Guardrails + observability on vision calls)
                    └── Phase 7  (atlas-inbox refactor — remove MultimodalChatService)
                        └── Phase 8  (tests)
```

---

## Phase 1 — `VisionRequest` and `VisionResponse`

**Goal:** Define the core types for vision calls. Mirror `PromptRequest`
and `PromptResponse` in structure and ergonomics so the pattern is
immediately familiar.

**Module:** `cafeai-core`

**New files:**
- `io.cafeai.core.ai.VisionRequest`
- `io.cafeai.core.ai.VisionResponse`

**`VisionRequest` API:**

```java
// Entry points (via app.vision())
app.vision("Is this an invoice?", pdfBytes, "application/pdf")
   .session(sessionId)
   .system("You are an invoice classifier.")
   .call();

app.vision("Describe the damage shown", imageBytes, "image/jpeg")
   .call();
```

**`VisionRequest` fields:**
- `String prompt` — the text question or instruction
- `byte[] content` — the binary payload (PDF, image, audio)
- `String mimeType` — `"application/pdf"`, `"image/jpeg"`, `"image/png"`, etc.
- `String sessionId` — optional, same threading as `PromptRequest`
- `String systemOverride` — optional, same override as `PromptRequest`

**`VisionResponse` fields:**
- `String text()` — the model's text reply
- `String modelId()` — which model answered
- `int promptTokens()`, `int outputTokens()`, `int totalTokens()`
- `boolean fromCache()` — future: semantic cache for vision calls

**Tasks:**
- [ ] Write `VisionRequest.java` — fluent builder, `call()` delegates to `VisionExecutor`
- [ ] Write `VisionResponse.java` — mirrors `PromptResponse`, builder pattern
- [ ] `VisionRequest.VisionExecutor` functional interface — implemented by `CafeAIApp`
- [ ] `VisionRequest` validates: prompt not null/blank, content not null/empty,
      mimeType not null/blank
- [ ] `VisionResponse` Javadoc explains which fields are meaningful for vision calls

**Acceptance criteria:**
- [ ] Both classes compile in `cafeai-core` with zero new dependencies
- [ ] `VisionRequest` is fluent — `session()`, `system()`, `call()` all chainable
- [ ] `VisionResponse` matches `PromptResponse` shape for developer consistency

---

## Phase 2 — `CafeAI.vision()` interface method

**Goal:** Add `vision()` to the `CafeAI` interface alongside `prompt()`.

**Module:** `cafeai-core`

**New method in `CafeAI.java`:**

```java
/**
 * Creates a vision request for the registered LLM provider.
 *
 * <p>Vision calls send binary content (PDF, image) alongside a text prompt
 * to a multimodal model. The vision pipeline differs from {@link #prompt()}:
 * <ul>
 *   <li>RAG retrieval is skipped — binary content cannot be embedded</li>
 *   <li>Guardrails apply to the text prompt only</li>
 *   <li>Observability traces the call with content type and size</li>
 *   <li>The registered provider must support multimodal input</li>
 * </ul>
 *
 * <pre>{@code
 *   // Classify a PDF
 *   VisionResponse response = app.vision(
 *       "Is this document an invoice? Reply YES or NO.",
 *       pdfBytes, "application/pdf").call();
 *
 *   // Describe an image with session memory
 *   VisionResponse response = app.vision(
 *       "What type of damage is visible?",
 *       imageBytes, "image/jpeg")
 *       .session(req.header("X-Session-Id"))
 *       .call();
 * }</pre>
 *
 * @param prompt   the text instruction for the model
 * @param content  the binary content (PDF bytes, image bytes, etc.)
 * @param mimeType MIME type of the content, e.g. "application/pdf"
 * @throws IllegalStateException if no AI provider has been registered
 * @throws VisionNotSupportedException if the registered provider does not
 *         support multimodal input
 */
VisionRequest vision(String prompt, byte[] content, String mimeType);
```

**Tasks:**
- [ ] Add `vision()` method to `CafeAI` interface
- [ ] Add `VisionNotSupportedException` as a nested class on `VisionRequest`
      (same pattern as `MemoryModuleNotFoundException`)
- [ ] Javadoc clearly explains what is and is not supported vs `prompt()`

**Acceptance criteria:**
- [ ] `CafeAI.vision()` compiles and is visible in the interface
- [ ] Javadoc explains the vision pipeline differences clearly

---

## Phase 3 — `CafeAIApp.vision()` implementation

**Goal:** Implement `vision()` in `CafeAIApp`. Build the LangChain4j
message list with multimodal content, route to the model, return
`VisionResponse`.

**Module:** `cafeai-core`

**What the implementation does:**

```
1. Validate prompt, content, mimeType
2. Check provider supports vision (Phase 5)
3. Apply PRE_LLM guardrails to the text prompt
4. Build LangChain4j message list:
   a. SystemMessage (app-level or override)
   b. Session history (if sessionId provided)
   c. UserMessage with multimodal content:
      - ImageContent (for image/*)
      - PdfFileContent (for application/pdf)
      - TextContent (the prompt text)
5. Call model.generate(messages)
6. Apply POST_LLM guardrails to response text
7. Emit observability event
8. Store in session memory (if sessionId provided)
9. Return VisionResponse
```

**Key detail — building multimodal messages:**

```java
// PDF
UserMessage.from(
    PdfFileContent.from(dataUri),   // "data:application/pdf;base64,<b64>"
    TextContent.from(prompt));

// Image
UserMessage.from(
    ImageContent.from(base64, mimeType),
    TextContent.from(prompt));
```

Note: `PdfFileContent.from()` requires a `data:` URI prefix — raw base64
fails. This was discovered in Capstone 4 and must be handled in the
implementation.

**Tasks:**
- [ ] Implement `vision()` in `CafeAIApp`
- [ ] Build base64 data URI correctly for PDF content
- [ ] Build `ImageContent` correctly for image/* content
- [ ] Session history injected before the multimodal message
- [ ] Session updated after the call (same as `prompt()`)
- [ ] Guardrails applied to text prompt (PRE_LLM) and response (POST_LLM)
- [ ] Observability event emitted with content type and byte count

**Acceptance criteria:**
- [ ] `app.vision("prompt", pdfBytes, "application/pdf").call()` returns a response
- [ ] `app.vision("prompt", imageBytes, "image/jpeg").call()` returns a response
- [ ] Session memory is populated after a `.session(id).call()`
- [ ] Guardrail blocking on the text prompt works correctly
- [ ] Observability console shows the vision call

---

## Phase 4 — LangChain4j message construction

**Goal:** Isolate the LangChain4j-specific message building into a
dedicated helper so it can be tested independently and updated when
LangChain4j changes its multimodal API.

**Module:** `cafeai-core`

**New class:** `io.cafeai.core.internal.VisionMessageBuilder`

```java
// Converts CafeAI vision request fields into LangChain4j ChatMessage list
List<ChatMessage> build(String prompt, byte[] content, String mimeType,
                         String systemPrompt, List<ChatMessage> history);
```

**Tasks:**
- [ ] Write `VisionMessageBuilder` — package-private
- [ ] Handle `application/pdf` → `PdfFileContent` with data URI prefix
- [ ] Handle `image/*` → `ImageContent` with base64 encoding
- [ ] Handle unknown MIME types — throw `UnsupportedContentTypeException`
      with a clear message listing supported types
- [ ] History messages prepended before the multimodal `UserMessage`
- [ ] System message added first if non-null

**Acceptance criteria:**
- [ ] PDF and image content produce correct LangChain4j message structures
- [ ] Unknown MIME type produces a clear exception
- [ ] Unit tests cover all three cases (PDF, image, unknown)

---

## Phase 5 — Model capability check

**Goal:** Surface a clear error when `app.vision()` is called but the
registered provider does not support multimodal input.

**Module:** `cafeai-core` + `cafeai-connect`

**The problem:** A developer registers `OpenAI.gpt4oMini()` which does
not support vision, then calls `app.vision()`. Currently this would
produce a cryptic LangChain4j error. CafeAI should catch this early
and explain clearly.

**Approach:**

```java
// Add to AiProvider interface
boolean supportsVision();  // default: false

// Override in vision-capable providers
OpenAI.gpt4o()     → supportsVision() = true
OpenAI.gpt4oMini() → supportsVision() = false  (no vision)
Ollama.llama3()    → supportsVision() = false  (model-dependent)
```

If `app.vision()` is called and `provider.supportsVision()` is false,
throw `VisionNotSupportedException` with:

```
VisionNotSupportedException: The registered provider 'gpt-4o-mini' does
not support vision/multimodal input. Use a vision-capable provider:
  app.ai(OpenAI.gpt4o())       -- OpenAI vision
  app.connect(Ollama.at(...).model("llava"))  -- local vision model
```

**Tasks:**
- [ ] Add `supportsVision()` to `AiProvider` interface (default `false`)
- [ ] Override to `true` in `OpenAI.gpt4o()`, `OpenAI.of()` (caller's responsibility)
- [ ] Add `Ollama.llava()` factory method as the canonical local vision model
- [ ] `CafeAIApp.vision()` checks `supportsVision()` before building messages
- [ ] `VisionNotSupportedException` message lists known vision-capable providers

**Acceptance criteria:**
- [ ] Calling `app.vision()` with `gpt-4o-mini` throws with a helpful message
- [ ] Calling `app.vision()` with `gpt-4o` proceeds correctly
- [ ] `Ollama.llava()` is documented as the local vision option

---

## Phase 6 — Guardrails and observability on vision calls

**Goal:** Ensure vision calls participate in the full CafeAI pipeline
for the parts that are applicable.

**Module:** `cafeai-core`, `cafeai-observability`

**What applies to vision calls:**
- ✅ PRE_LLM guardrails — on the text prompt
- ✅ POST_LLM guardrails — on the text response
- ✅ Observability — trace with prompt, content type, byte count, response
- ✅ Session memory — store prompt + response for conversation continuity
- ❌ RAG retrieval — not applicable, binary content cannot be embedded
- ❌ Semantic cache — not yet (future roadmap item)

**Observability event for vision:**

```
[VISION] model=gpt-4o mimeType=application/pdf contentBytes=45231
         promptTokens=1842 outputTokens=127 latencyMs=2341
         prompt="Is this an invoice?"
         response="YES — this is an invoice from FedEx..."
```

**Tasks:**
- [ ] PRE_LLM guardrail check on `VisionRequest.prompt()` text
- [ ] POST_LLM guardrail check on `VisionResponse.text()`
- [ ] Observability `beforeVision()` / `afterVision()` hooks in `ObserveBridge`
      (or reuse `beforePrompt()` / `afterPrompt()` with a vision flag)
- [ ] Session memory stores `prompt` as user message, `response.text()` as
      assistant message — same as `prompt()`
- [ ] RAG pipeline explicitly skipped (no retrieval attempt)

**Acceptance criteria:**
- [ ] Jailbreak guardrail blocks a malicious text prompt in a vision call
- [ ] Observability console shows vision calls with content metadata
- [ ] Session follow-up after a vision call has context from that call

---

## Phase 7 — Refactor `atlas-inbox` to use `app.vision()`

**Goal:** Remove `MultimodalChatService` from `atlas-inbox` and replace
all direct LangChain4j calls with `app.vision()`. This is the proof
that the implementation is correct and complete.

**Module:** standalone `atlas-inbox` capstone project

**What changes:**

```java
// Before — raw LangChain4j, bypasses CafeAI entirely
var chat = new MultimodalChatService(SYSTEM_PROMPT);
var classification = classifier.classifyPdf(pdfBytes);   // uses chat directly
var invoice = extractor.extractFromPdf(pdfBytes);        // uses chat directly

// After — through CafeAI pipeline
var classification = app.vision(CLASSIFY_PROMPT, pdfBytes, "application/pdf").call();
var invoice = app.vision(EXTRACT_PROMPT, pdfBytes, "application/pdf").call();
```

**Classes to remove or simplify:**
- `MultimodalChatService.java` — deleted entirely
- `AttachmentTypeClassifier.java` — simplified, uses `app.vision()`
- `InvoiceDataExtractor.java` — simplified, uses `app.vision()`

**What this proves:**
- Guardrails now apply to classification and extraction calls
- Observability traces every PDF and image processed
- The rate limiting pauses (`Thread.sleep`) can be reduced — CafeAI can
  manage the model's token budget centrally

**Tasks:**
- [ ] Delete `MultimodalChatService.java`
- [ ] Rewrite `AttachmentTypeClassifier` to use `app.vision()`
- [ ] Rewrite `InvoiceDataExtractor` to use `app.vision()`
- [ ] Remove `openai` direct dependency from `atlas-inbox/build.gradle`
      (no longer needed — CafeAI handles the provider)
- [ ] Verify all Phase 5–11 tests still pass
- [ ] Update `DEMO.md` — the "CafeAI owns the multimodal calls" moment
      is now the correct story

**Acceptance criteria:**
- [ ] `MultimodalChatService.java` does not exist
- [ ] `atlas-inbox` build has no direct `langchain4j-open-ai` dependency
- [ ] All dry-run phases still produce the same classification and extraction results
- [ ] `./gradlew run -Pdry` completes with 0 errors

---

## Phase 8 — Tests

**Goal:** Full test coverage for the new multimodal pipeline.

**Module:** `cafeai-core`

**New test class:** `VisionPipelineTest`

**Test cases:**
- [ ] `app.vision()` with mock provider returns `VisionResponse`
- [ ] `VisionRequest` is fluent — `session()`, `system()`, `call()` chain
- [ ] `VisionRequest` rejects null/blank prompt
- [ ] `VisionRequest` rejects null content
- [ ] `VisionRequest` rejects null/blank mimeType
- [ ] PRE_LLM guardrail blocks text prompt → `VisionResponse` not returned
- [ ] Session memory populated after `.session(id).call()`
- [ ] `VisionNotSupportedException` thrown when provider does not support vision
- [ ] PDF content produces correct `PdfFileContent` data URI
- [ ] Image content produces correct `ImageContent` base64
- [ ] Unknown MIME type throws `UnsupportedContentTypeException`
- [ ] Observability hook fires on vision call

**Acceptance criteria:**
- [ ] All new tests pass
- [ ] `./gradlew clean build` — total test count >= 323 (311 + ~12 new)
- [ ] Zero regressions in existing 311 tests

---

## What this roadmap does NOT cover

- **`app.audio()`** — audio transcription pipeline. Separate roadmap item.
  Requires different model support (Whisper), different content handling,
  and different guardrail considerations. Deferred until vision is proven.

- **Semantic cache for vision** — caching identical vision calls is
  complex (binary content hashing, cache invalidation). Deferred.

- **Streaming vision responses** — `app.vision().stream()` for real-time
  token streaming of vision responses. Can be added in a follow-up once
  `app.vision().call()` is stable.

---

## Phase 9 — Structured output: `app.prompt().returning(Class)`

**Goal:** First-class typed output from LLM calls. Eliminate the
strip-and-parse boilerplate that every capstone reimplemented independently.

**Module:** `cafeai-core`

### The gap

Every capstone that needed a typed LLM response wrote the same three steps:

```java
// Step 1 — embed a JSON schema instruction in the prompt
String prompt = """
    Return ONLY valid JSON matching this schema. No prose. No fences.
    { "tone": "NEUTRAL|FRUSTRATED|HOSTILE", "escalate": true|false, ... }
    """;

// Step 2 — call the LLM
String raw = app.prompt(prompt).call().text();

// Step 3 — strip fences, parse with Jackson
String clean = raw.replaceAll("(?s)```json\\s*", "")
                  .replaceAll("(?s)```\\s*", "").trim();
SentimentResult result = MAPPER.readValue(clean, SentimentResult.class);
```

This appeared four times in `atlas-inbox` alone:
`SentimentResult`, `AttachmentClassification`, `InvoiceData`,
`ReconciliationResult`. Capstone 2 had `QualificationDecision`.
Capstone 3 had `ClaimsDecision`. Every capstone rediscovered this.

### The fix

```java
// Before — boilerplate in every handler
String raw = app.prompt(prompt).call().text();
String clean = raw.replaceAll("(?s)```json\\s*", "").trim();
SentimentResult result = MAPPER.readValue(clean, SentimentResult.class);

// After — one line
SentimentResult result = app.prompt(prompt)
    .returning(SentimentResult.class)
    .call(SentimentResult.class);
```

The framework owns the JSON schema instruction generation, fence stripping,
and Jackson deserialization. The developer writes none of the boilerplate.

### Design

`PromptRequest` gains a `returning(Class<T>)` method. When set, `call()`
is overloaded to return `T` directly:

```java
// Fluent — returning() sets the target type
public <T> PromptRequest returning(Class<T> type);

// Typed call — deserialises response to T
public <T> T call(Class<T> type);

// Original call() still works — returning() just adds schema injection
public PromptResponse call();
```

Internally, when `returning(Class)` is set:
1. Inspect the class fields via reflection to generate a JSON schema hint
2. Append the schema instruction to the prompt before the LLM call
3. After the call, strip fences and deserialise

The schema hint does not need to be a full JSON Schema — a compact example
object is sufficient to guide the model reliably:

```
Respond ONLY with valid JSON matching this structure. No prose. No markdown.
{"tone":"string","urgency":"string","escalate":boolean,"keyPhrases":["string"]}
```

**Tasks:**
- [ ] Add `returning(Class<T>)` to `PromptRequest`
- [ ] Add overloaded `call(Class<T>)` to `PromptRequest`
- [ ] `SchemaHintBuilder` — generates compact JSON example from class fields
      via reflection. Package-private in `cafeai-core`.
- [ ] `ResponseDeserializer` — strips fences, parses with Jackson.
      Package-private in `cafeai-core`.
- [ ] Jackson added to `cafeai-core` dependencies (or check if already present)
- [ ] Works with Java records and plain classes with public fields/getters
- [ ] Clear error if Jackson cannot deserialise (wraps as `StructuredOutputException`)
- [ ] `VisionRequest` gets the same `returning(Class<T>)` / `call(Class<T>)` API

**Acceptance criteria:**
- [ ] `app.prompt(prompt).returning(SentimentResult.class).call(SentimentResult.class)`
      returns a populated `SentimentResult` record
- [ ] Works for Java records, POJOs with getters, POJOs with public fields
- [ ] Fence stripping handles: no fences, ` ```json ` fences, ` ``` ` fences,
      leading/trailing whitespace
- [ ] `StructuredOutputException` thrown with original text when parse fails
- [ ] Existing `call()` behaviour unchanged — no regressions

---

## Phase 10 — Token budget and rate limit handling

**Goal:** Give the developer a CafeAI primitive for managing token spend
and rate limits, eliminating manual `Thread.sleep()` calls from application
code.

**Module:** `cafeai-core`

### The gap

`atlas-inbox` processed five emails with three AI calls each (sentiment,
classification, extraction, reconciliation, composition). On the free tier
(30k TPM), this blew through the limit immediately. The solution was:

```java
// In AtlasInboxProcessor.java — hand-rolled rate management
System.out.println("  Pausing 15s (rate limit)...");
Thread.sleep(15_000);

// Also between extraction and reconciliation
System.out.println("  Pausing 10s before reconciliation (rate limit)...");
Thread.sleep(10_000);
```

This is application code doing framework work. The developer should not
need to know the token limit of their tier or calculate sleep intervals.

### The fix

Two new primitives:

**1. Token budget enforcement:**
```java
app.budget(TokenBudget.perMinute(30_000));   // free tier
app.budget(TokenBudget.perMinute(300_000));  // paid tier
app.budget(TokenBudget.unlimited());          // no limit
```

When a budget is set, `CafeAIApp` tracks tokens consumed in the current
minute window. If a call would exceed the budget, it pauses automatically
until the window resets — no `Thread.sleep` in application code.

**2. Retry with backoff:**
```java
app.retry(RetryPolicy.onRateLimit()
    .maxAttempts(3)
    .backoff(Duration.ofSeconds(2)));
```

When a `RateLimitException` is thrown by LangChain4j, the framework catches
it, waits, and retries automatically. The developer sees a successful call
or a `RateLimitExceededException` after max attempts — never a raw
LangChain4j exception.

### Design

**`TokenBudget`** — a value type in `cafeai-core`:

```java
public record TokenBudget(long tokensPerMinute) {
    public static TokenBudget perMinute(long tokens) { ... }
    public static TokenBudget unlimited() { ... }
}
```

**`RetryPolicy`** — a value type in `cafeai-core`:

```java
public final class RetryPolicy {
    public static RetryPolicy onRateLimit() { ... }
    public RetryPolicy maxAttempts(int n) { ... }
    public RetryPolicy backoff(Duration d) { ... }
}
```

**`CafeAI` interface additions:**

```java
CafeAI budget(TokenBudget budget);
CafeAI retry(RetryPolicy policy);
```

**`CafeAIApp` execution loop changes:**
- Track tokens consumed in a sliding one-minute window
- Before each LLM call: check if estimated tokens would exceed budget;
  if so, sleep until the window resets
- Catch `RateLimitException` from LangChain4j: apply retry policy

**Tasks:**
- [ ] Write `TokenBudget` record in `cafeai-core`
- [ ] Write `RetryPolicy` class in `cafeai-core`
- [ ] Add `app.budget()` and `app.retry()` to `CafeAI` interface
- [ ] Implement token tracking in `CafeAIApp.executePrompt()`
- [ ] Implement retry on `RateLimitException` in `CafeAIApp.executePrompt()`
- [ ] Same tracking/retry applied to `executeVision()` (Phase 3)
- [ ] Startup log shows budget and retry policy when configured
- [ ] `atlas-inbox` `Thread.sleep` calls removed — replaced with
      `app.budget(TokenBudget.perMinute(30_000))`

**Acceptance criteria:**
- [ ] `app.budget(TokenBudget.perMinute(30_000))` configured in `atlas-inbox`
- [ ] `Thread.sleep` calls removed from `AtlasInboxProcessor`
- [ ] On rate limit: framework pauses and retries, not application code
- [ ] Token count logged per call in observability output
- [ ] `TokenBudgetExceededException` thrown when budget exhausted with no retry

---

## Phase 11 — POST_LLM guardrails on tool call output

**Goal:** Apply guardrails to the final assembled output of a tool-calling
loop, not just the initial user input.

**Module:** `cafeai-core`, `cafeai-tools`

### The gap

Guardrails fire once: `PRE_LLM` on the user's input message before the
LLM sees it. This is correct for blocking adversarial inputs.

But in a tool-calling application, the LLM's final response is assembled
from multiple model invocations — the initial call, then one or more
tool call/response cycles, then a final synthesis. The guardrail never
sees the assembled output. It only saw the original user input.

In `atlas-inbox` EscalationTest, the jailbreak attempt was handled
correctly — but only because the LLM's own safety training ignored it,
not because CafeAI's guardrail intercepted the output. If the model had
been less robust, the injected instruction could have influenced the
final response without any CafeAI guardrail seeing it.

The same applies to Capstone 3 (`acme-claims`) where tool calls open
and approve insurance claims. A crafted input could potentially influence
the tool-calling sequence in ways that bypass the PRE_LLM input check.

### Current behaviour vs correct behaviour

```
Current:
  User input → [PRE_LLM guardrails] → LLM → tool call → tool result
             → LLM → tool call → tool result → LLM → final response
                                                           ↑
                                               No guardrail here

Correct:
  User input → [PRE_LLM guardrails] → LLM → tool call → tool result
             → LLM → tool call → tool result → LLM → final response
                                                           ↑
                                               [POST_LLM guardrails]
```

### The fix

`ToolRegistry.executeWithTools()` returns the final assembled text.
That text passes through `POST_LLM` guardrails before being returned
to the caller.

**Current flow in `CafeAIApp.executePrompt()`:**

```java
if (toolBridge != null && toolBridge.hasTools()) {
    responseText = toolBridge.executeWithTools(model, messages);
    // responseText returned directly — no POST_LLM check
}
```

**Fixed flow:**

```java
if (toolBridge != null && toolBridge.hasTools()) {
    responseText = toolBridge.executeWithTools(model, messages);
    // Apply POST_LLM guardrails to the assembled tool-call output
    responseText = applyPostLlmGuardrails(responseText, res);
}
```

`applyPostLlmGuardrails()` already exists for the non-tool path —
it just needs to be called for the tool-calling path too.

**Tasks:**
- [ ] Read current `CafeAIApp.executePrompt()` tool-calling path
- [ ] Identify where POST_LLM guardrails are currently applied
- [ ] Apply same POST_LLM guardrail check to `toolBridge.executeWithTools()`
      response before returning
- [ ] Add a test: POST_LLM guardrail blocks a tool-calling response that
      contains toxic output even if the input was clean
- [ ] Verify `atlas-inbox` jailbreak test still passes (now blocked by
      guardrail, not just by LLM safety training)
- [ ] Document the fix in SPEC.md guardrail section

**Acceptance criteria:**
- [ ] POST_LLM guardrails fire on tool-calling responses
- [ ] A clean input that produces a toxic tool-call output is blocked
- [ ] `atlas-inbox` EscalationTest jailbreak blocked by CafeAI guardrail
      (not just by LLM safety training)
- [ ] No regressions in tool-calling tests
- [ ] Test count increases by at least 2 (new POST_LLM tool-calling tests)

---

## Updated dependency map

```
Phase 1  (VisionRequest + VisionResponse)
    └── Phase 2  (CafeAI.vision() interface)
    └── Phase 3  (CafeAIApp.vision() implementation)
        └── Phase 4  (LangChain4j message construction)
            └── Phase 5  (Model capability check)
                └── Phase 6  (Guardrails + observability)
                    └── Phase 7  (atlas-inbox refactor)

Phase 9  (structured output — independent, no deps)
    └── feeds into Phase 7 (atlas-inbox uses .returning())

Phase 10 (token budget — independent, no deps)
    └── feeds into Phase 7 (atlas-inbox removes Thread.sleep)

Phase 11 (POST_LLM on tool calls — independent, no deps)

Phase 8  (tests — depends on all prior phases)
```

Phases 9, 10, and 11 are independent of the vision phases and can be
worked in parallel or tackled first as warm-up before the larger
multimodal work.
