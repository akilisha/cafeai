# Production-Grade AI — Token Budgets, Retries, and Observability

*Post 11 of 12 in the CafeAI series*

---

The `atlas-inbox` capstone had two `Thread.sleep` calls in its first version.

```java
// Rate limit courtesy pause between emails — keeps us under 30k TPM
if (i < total - 1) {
    System.out.println("  Pausing 15s (rate limit)...");
    Thread.sleep(15_000);
}

// Pause before reconciliation — classification + extraction already consumed tokens
System.out.println("  Pausing 10s before reconciliation (rate limit)...");
Thread.sleep(10_000);
```

These are correct solutions. The OpenAI free tier allows 30,000 tokens per minute. Processing one vendor email with a PDF attachment — classification, extraction, reconciliation, composition — consumes roughly 8,000-15,000 tokens. At that rate, two emails in quick succession blow through the budget.

Pausing is the right behaviour. But `Thread.sleep` in application code is the wrong place for it. This is infrastructure — the framework should handle it, not the developer.

ROADMAP-14 closed this gap. Post 11 covers how.

---

## Token Budget

```java
app.budget(TokenBudget.perMinute(30_000));   // OpenAI free tier
app.budget(TokenBudget.perMinute(500_000));  // OpenAI Tier 1
app.budget(TokenBudget.unlimited());          // no limit — for testing
```

The token budget tracks actual token consumption across all calls — text, vision, and audio — and enforces the per-minute limit. When the budget is approaching exhaustion, subsequent calls wait until the window resets.

The implementation uses a sliding window counter. Token usage from the last 60 seconds is summed on every call. If the sum plus the estimated cost of the upcoming call would exceed the budget, the call waits. When the window slides past old usage, the budget refills.

This is the correct behaviour. The developer registers the budget at startup. The framework manages the timing. There are no `Thread.sleep` calls in application code.

```java
// Before
Thread.sleep(15_000);  // manual pause between emails

// After
app.budget(TokenBudget.perMinute(30_000));
// framework waits automatically when needed
```

---

## Retry Policy

LLM API calls fail. Networks time out. Rate limits are hit transiently even with a budget. A production AI application must handle these failures gracefully.

```java
app.retry(RetryPolicy.onRateLimit()
    .maxAttempts(3)
    .backoff(Duration.ofSeconds(10)));
```

The retry policy fires when a rate limit error (`429`) is received from the API. It retries the call up to `maxAttempts` times, waiting `backoff * attemptNumber` between each attempt (exponential-ish backoff).

The retry policy applies to all call types — text, vision, and audio. It is registered once and the framework handles the retry loop invisibly.

```
18:35:01 WARN  RetryUtils - A retriable exception occurred. Remaining retries: 2 of 2
18:35:40 WARN  RetryUtils - A retriable exception occurred. Remaining retries: 1 of 2
```

The `atlas-inbox` validation run showed LangChain4j's own retry layer catching connection resets before CafeAI's retry layer sees them. Both layers are present and correct — LangChain4j handles network-level transients; CafeAI handles API-level rate limits.

If all retries are exhausted, `RetryPolicy.RateLimitExceededException` is thrown with the original cause and the number of attempts made.

---

## Observability

```java
app.observe(ObserveStrategy.console());  // development
app.observe(ObserveStrategy.otel());     // production (OpenTelemetry)
```

Observability in CafeAI means structured data on every LLM call:

```
-- LLM Call ------------------------------------------
  model:      openai (gpt-4o)
  session:    demo-session
  tokens:     58 prompt + 149 completion = 207 total
  latency:    2,425ms
------------------------------------------------------

-- Audio Call ----------------------------------------
  mimeType:   audio/wav
  bytes:      32,044
  model:      gpt-4o
  tokens:     101 prompt + 34 completion = 135 total
  latency:    12,321ms
------------------------------------------------------

-- Vision Call ---------------------------------------
  mimeType:   application/pdf
  bytes:      48,231
  model:      gpt-4o
  tokens:     847 prompt + 23 completion = 870 total
  latency:    4,192ms
  session:    vendor-session-1
------------------------------------------------------
```

Every call type produces a structured log entry with model, tokens, latency, and modality-specific metadata. The console strategy writes to stdout. The OTel strategy sends traces to any OpenTelemetry-compatible backend (Jaeger, Grafana, Honeycomb, Datadog).

The hooks — `beforePrompt`/`afterPrompt`, `beforeVision`/`afterVision`, `beforeAudio`/`afterAudio` — fire at the boundaries of each call type. The `ObserveBridge` SPI allows custom observability implementations beyond the two built-in strategies.

---

## What Observability Reveals

The `atlas-inbox` validation run produced this observability picture across five emails:

```
Email 1 — ElevenLabs marketing (pre-filtered, 0 tokens)
Email 2 — Anthropic login link (pre-filtered, 0 tokens)
Email 3-5 — QuestCDN webinar invitations (pre-filtered, 0 tokens)
```

All five emails were pre-filtered without any LLM calls. The pre-filter — a cheap string check on sender domain and subject keywords — ran before any token was spent. Zero tokens consumed on a batch of five emails, all correctly identified as non-vendor.

This is observability working correctly: it confirms the pre-filter is saving tokens, not just claiming to.

During classification tests, the observability output confirmed that multi-page PDFs were consuming more tokens than single-page documents (the model reads all pages). The token counts in the observability trace were the evidence that led to the decision to add `TokenBudget.perMinute(30_000)` to `atlas-inbox`.

---

## The Production Checklist

Based on what the four capstones revealed, here is what a production CafeAI application needs:

**Token management:**
```java
app.budget(TokenBudget.perMinute(30_000));  // set to your tier limit
app.retry(RetryPolicy.onRateLimit().maxAttempts(3).backoff(Duration.ofSeconds(10)));
```

**Safety:**
```java
app.guard(GuardRail.jailbreak());
app.guard(GuardRail.pii());
// add domain-specific guardrails as needed
```

**Memory:**
```java
// Single-node production — SSD-backed, no Redis needed
app.memory(MemoryStrategy.mapped());
// Multi-node — Redis
app.memory(MemoryStrategy.redis(RedisConfig.of("redis.internal", 6379)));
```

**Observability:**
```java
app.observe(ObserveStrategy.otel());  // production
// or
app.observe(ObserveStrategy.console());  // development
```

**Provider with fallback (when using local models):**
```java
app.connect(
    Ollama.at("http://localhost:11434").model("qwen2.5")
          .onUnavailable(Fallback.use(OpenAI.gpt4oMini())));
```

---

## The `Thread.sleep` Refactor — Before and After

The complete `atlas-inbox` startup before ROADMAP-14:

```java
var chat = new MultimodalChatService(SYSTEM_PROMPT);  // raw LangChain4j
var analyzer   = new EmailSentimentAnalyzer(app);
var classifier = new AttachmentTypeClassifier(chat);  // bypasses CafeAI
var extractor  = new InvoiceDataExtractor(chat);      // bypasses CafeAI

// Between emails
Thread.sleep(15_000);  // manual rate limit management

// Before reconciliation
Thread.sleep(10_000);  // more manual rate limit management
```

After ROADMAP-14:

```java
app.budget(TokenBudget.perMinute(30_000));   // framework manages rate limits
app.retry(RetryPolicy.onRateLimit().maxAttempts(3).backoff(Duration.ofSeconds(10)));

var analyzer   = new EmailSentimentAnalyzer(app);
var classifier = new AttachmentTypeClassifier(app);  // through CafeAI pipeline
var extractor  = new InvoiceDataExtractor(app);      // through CafeAI pipeline

// No Thread.sleep anywhere in application code
```

The `Thread.sleep` calls were not wrong. They were correct for their time — the only tool available when the framework had no budget management. When the framework grew to handle the concern, the application code could stop handling it. That is the correct direction of travel.

---

## Helidon SE and Virtual Threads

The HTTP server behind `support-agent` and `meridian-qualify` uses Helidon SE with virtual threads. Each incoming request runs on a virtual thread — a lightweight, JVM-managed thread that parks during I/O without blocking a platform thread.

LLM calls are entirely I/O-bound. A `gpt-4o` call takes 1-5 seconds of waiting for the API to respond. On a platform thread, that blocks. On a virtual thread, the JVM parks the thread and resumes it when the response arrives — zero platform thread blocked, zero thread pool bottleneck.

```java
// Helidon SE with virtual threads — default in CafeAI
app.listen(8080, () -> System.out.println("Running on :8080"));

// Each request handler runs on a virtual thread automatically
app.post("/chat", (req, res, next) -> {
    // This LLM call parks the virtual thread while waiting for the API
    var response = app.prompt(req.body("message")).call();
    res.json(Map.of("response", response.text()));
});
```

A single JVM with virtual threads can hold thousands of concurrent LLM calls in flight. At 2-second average latency and 1,000 concurrent users, that is 2,000 in-flight LLM calls — well within virtual thread capacity, impossible on a fixed thread pool without careful sizing.

---

## Post 12 — The Capstone Series

Post 12 is the synthesis — what four complete applications, 359 tests, and 14 roadmap items prove about a framework, about the middleware pattern applied to AI, and about what it means to build serious Gen AI infrastructure in Java.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
