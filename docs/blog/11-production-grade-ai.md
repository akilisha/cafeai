# Production-Grade AI — Token Budgets, Retries, Observability, and Incident Response

*Post 11 of 12 in the CafeAI series*

---

A production AI application has to stay inside its provider's rate limits, survive transient failures, and show what it is doing. This post covers the primitives for that — token budgets, retries and observability — and ends with a production checklist.

---

## Token Budget

```java
app.budget(TokenBudget.perMinute(30_000));   // OpenAI free tier
app.budget(TokenBudget.perMinute(500_000));  // OpenAI Tier 1
app.budget(TokenBudget.unlimited());          // no limit — for testing
```

The token budget tracks actual token consumption across all calls — text, vision, and audio — and enforces the per-minute limit. When the budget is approaching exhaustion, subsequent calls wait until the window resets.

The budget is a one-minute window. Tokens used are added up as calls complete; once the window's total reaches the limit, the next call waits until the window resets, then proceeds. There are no `Thread.sleep` calls in application code — the framework parks the (virtual) thread.

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

LangChain4j has its own retry layer for network-level transients (connection resets); CafeAI's policy handles API-level rate limits. Both are present.

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
  model:      gpt-4o
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

## JVM-Level Visibility with Flight Recorder

Everything above answers "what happened in this LLM call." It has nothing to say about the JVM underneath it — a GC pause, allocation pressure, lock contention, or (specific to a framework built entirely on virtual threads) a virtual thread pinned to its carrier. `cafeai-flight` closes that gap with Java Flight Recorder, exported as OpenTelemetry metrics:

```java
var flight = FlightBridge.builder()
    .categories(FlightCategory.GC, FlightCategory.VIRTUAL_THREADS)  // the defaults
    .build();
flight.start();
// ... app.listen(...) ...
Runtime.getRuntime().addShutdownHook(new Thread(flight::close));
```

`cafeai-flight` is deliberately independent of `cafeai-observability` — no dependency either direction. Both simply call `GlobalOpenTelemetry.get()`, so they share whatever exporter the application registers, for free, because that's how an OTel global registration already works. CafeAI has never managed the OTel SDK lifecycle for either module; configure your own exporter the same way for both.

The headline event is `jdk.VirtualThreadPinned`: a virtual thread stuck to its carrier thread — inside a `synchronized` block, for instance — past a threshold (20ms by default, matching the JVM's own default). For a framework where every request runs on a virtual thread, this is usually the first thing worth asking a JVM about when things get slow, and it costs nothing to leave on: JFR's whole design point is low, always-on production overhead. `FlightCategory.GC` and `FlightCategory.VIRTUAL_THREADS` are the two enabled by default; `ALLOCATION`, `CPU`, and `IO` are higher-volume and opt-in.

One explicit non-goal: `cafeai-flight` does not ship a dashboard. Point whatever OTel-compatible backend you already use (Grafana, Jaeger, Honeycomb, Datadog) at the same exporter, and these metrics show up next to everything else.

---

## Beyond Observability — Watching a Cluster

Observability answers "what happened." `cafeai-sentinel` is built on the same primitives to answer "is anything wrong right now, and why?" Nothing comes in over HTTP: it watches a Kubernetes or OpenShift namespace, triages every pod event with rules (no model call), coalesces related failures into one incident per owning Deployment, and only for a confirmed incident runs an `app.agent(...)` investigation with seven read-only cluster-reading tools. It ends at "structured incident published" — it is a pipeline, not a remediation product. See the developer guide's `cafeai-sentinel` chapter and the `cluster-sentinel` capstone.

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
app.guard(GuardRail.promptInjection());
app.guard(GuardRail.pii());
app.guard(GuardRail.secrets());
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
          .onUnavailable(Fallback.use(OpenAI.of("gpt-4o-mini"))));
```

**Cluster incident response (optional — containerized deployments):** see the `cafeai-sentinel` chapter of the developer guide.

---

## Helidon SE and Virtual Threads

The HTTP server behind `support-desk` and `meridian-qualify` uses Helidon SE with virtual threads. Each incoming request runs on a virtual thread — a lightweight, JVM-managed thread that parks during I/O without blocking a platform thread.

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

Post 12 is the synthesis — what four complete applications prove about a framework, about the middleware pattern applied to AI, and about what it means to build serious Gen AI infrastructure in Java.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
