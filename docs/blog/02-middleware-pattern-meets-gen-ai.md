# The Middleware Pattern Meets Gen AI — From Express to CafeAI

*Post 2 of 12 in the CafeAI series*

---

In 2010, TJ Holowaychuk released Express.js with a design decision that turned out to be one of the most influential ideas in web development: everything is middleware.

Not "here are the things you can plug in as middleware." Everything. Authentication, rate limiting, body parsing, logging, error handling, routing itself — all middleware, all composable, all following the same `(req, res, next)` contract. The developer assembles the pipeline from standard pieces and writes business logic at the end.

Express became the dominant Node.js framework not because it did more than competitors, but because it did less and let the developer compose the rest. The minimal surface area was the feature.

CafeAI applies this insight to AI applications. Not as an analogy — as a literal design decision. The same pipeline structure, the same composability contract, the same principle: the framework assembles middleware; the developer writes intent.

---

## The AI Pipeline Problem

An AI application is not a single function call. Between the incoming request and the response, a production-grade AI application does many things:

It authenticates the request. It rate-limits the caller. It checks the input for PII before it touches the LLM. It detects jailbreak attempts. It enforces topic boundaries. It manages the token budget. It retrieves relevant documents from a vector store. It builds the full message list including conversation history. It calls the LLM. It checks the response for policy violations. It records observability data. It writes the exchange to session memory.

In a typical implementation, these concerns are scattered. The authentication is in a filter. The RAG retrieval is inside the service method. The guardrail is a library call bolted on before the return statement. The observability is instrumented separately. When something goes wrong — and something always goes wrong — the developer traces through four different places to find out where the pipeline broke.

CafeAI treats each of these as a middleware layer. The developer registers the layers at startup. The framework executes them in order, every time, for every request. Adding a guardrail is one line. Removing RAG retrieval is one line. Adding observability is one line. None of these changes touches the others.

---

## The Pipeline in Full

Here is what runs on every `app.prompt()` call in CafeAI:

```
Incoming Request
    ↓
[ PRE_LLM guardrails ]      — jailbreak, PII, topic boundary, injection
    ↓
[ Session memory read ]     — load conversation history for this session ID
    ↓
[ RAG retrieval ]           — semantic search against registered vector store
    ↓
[ Token budget check ]      — wait if TPM limit is approaching
    ↓
[ LLM call ]                — with retry on rate limit
    ↓
[ POST_LLM guardrails ]     — hallucination scoring, bias, regulatory
    ↓
[ Observability ]           — token counts, latency, RAG documents retrieved
    ↓
[ Session memory write ]    — store prompt + response for next turn
    ↓
Response
```

Every layer is independent. The developer can inspect, replace, or remove any of them without touching the others. The LLM call does not know whether RAG ran before it. The guardrail does not know what the session history contains. The observability layer does not affect what the caller receives.

---

## The Express Contract

In Express, middleware follows a three-argument contract:

```javascript
// Express middleware signature
function middleware(req, res, next) {
    // do something with the request
    next(); // pass control to the next middleware
}
```

CafeAI's middleware follows the same contract in Java:

```java
// CafeAI middleware signature
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next) throws Exception;
}
```

A CafeAI middleware that adds a response header looks exactly like an Express middleware that adds a response header:

```java
// Express (JavaScript)
app.use((req, res, next) => {
    res.setHeader("X-Powered-By", "CafeAI");
    next();
});

// CafeAI (Java)
app.filter((req, res, next) -> {
    res.header("X-Powered-By", "CafeAI");
    next.run();
});
```

A Java developer who has never written Express reads the CafeAI version and understands it. An Express developer who has never written Java reads both and sees the same structure. This is not accidental. The naming, the contract, the composition model — all deliberately mirrored.

---

## Post-Processing Middleware

The Express mental model has one nuance that CafeAI preserves and extends: middleware can run both before and after the downstream handler.

```java
// Post-processing middleware — runs after the AI call returns
app.filter((req, res, next) -> {
    long start = System.currentTimeMillis();

    next.run();  // downstream runs here — including the LLM call

    long elapsed = System.currentTimeMillis() - start;
    res.header("X-Response-Time", elapsed + "ms");
});
```

The call to `next.run()` blocks until all downstream middleware and the final handler have completed. Everything before the call runs pre-processing. Everything after runs post-processing. This is how POST_LLM guardrails work — they are middleware that runs after the LLM response arrives but before the caller receives it.

---

## Guardrails as Middleware

The most important application of this pattern in CafeAI is guardrails. In most frameworks, safety checks are an afterthought — a library you call, a function you wrap around the LLM invocation, something that lives outside the pipeline and gets forgotten when deadlines arrive.

In CafeAI, a guardrail is a middleware with a position:

```java
// Registered at startup — runs on every prompt call automatically
app.guard(GuardRail.jailbreak());           // PRE_LLM — blocks before the call
app.guard(GuardRail.pii());                 // BOTH — checks input and output
app.guard(GuardRail.hallucination());       // POST_LLM — checks after the call
app.guard(GuardRail.regulatory().gdpr());   // POST_LLM — compliance check
```

The developer never calls these explicitly. They are registered once and the pipeline executes them on every call. Removing a guardrail is removing one line from startup registration. Adding one is adding one line. The LLM call doesn't change. The routes don't change. The guardrails are the pipeline, not the wrapper around it.

The PRE_LLM position runs before the LLM sees the prompt — blocking jailbreak attempts and scrubbing PII before they are sent. The POST_LLM position runs after the response arrives — scoring for hallucination, checking for bias, enforcing regulatory constraints. The BOTH position runs in both places.

This is why guardrails being middleware matters: they cannot be accidentally omitted. They are not a function call the developer remembers to make. They are a layer that fires whether the developer thinks about it or not.

---

## Routing is Middleware Too

CafeAI preserves Express's routing syntax exactly:

```java
// Express (JavaScript)
app.get("/health", (req, res) => res.json({ status: "ok" }));
app.post("/chat", (req, res) => { /* handler */ });
app.use("/api", apiRouter);

// CafeAI (Java)
app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));
app.post("/chat", (req, res, next) -> { /* handler */ });
app.filter("/api", apiMiddleware);
```

Path parameters, query strings, wildcard routes, sub-routers — the full Express routing model is present. A developer who knows Express knows CafeAI routing. A developer who knows CafeAI routing knows Express.

The sub-router pattern is particularly useful for versioned AI APIs:

```java
var v1 = CafeAI.Router();
v1.post("/chat",  chatHandler);
v1.post("/embed", embedHandler);
v1.get("/health", healthHandler);

app.filter("/api/v1", v1);
```

---

## The Composability Payoff

The real payoff of the middleware model appears when you need to change something.

Suppose you have a production AI application and you need to add PII scrubbing. In a typical implementation, you find every place where prompts are assembled and add a scrub call. You pray you didn't miss any. You write tests for each call site.

In CafeAI:

```java
app.guard(GuardRail.pii());
```

One line, added to startup. PII scrubbing now runs on every prompt call, every vision call, every audio call — automatically, without touching any of the call sites.

Suppose you need to add observability. Same story:

```java
app.observe(ObserveStrategy.otel());
```

One line. OpenTelemetry traces now appear on every LLM call, with model ID, token counts, latency, and RAG documents retrieved. Nothing else changes.

Suppose you need to swap providers from OpenAI to Anthropic:

```java
// Before
app.ai(OpenAI.gpt4o());

// After
app.ai(Anthropic.claude35Sonnet());
```

One line. The routes, the guardrails, the memory strategy, the RAG pipeline — unchanged.

This is the composability payoff. It is not a theoretical benefit. It is a practical consequence of treating every concern as a middleware layer with a clear interface, rather than weaving concerns together in application code.

---

## What the Next Post Covers

Post 3 walks through the first real CafeAI application from scratch — a customer support assistant backed by a knowledge base, with session memory, tool use, and guardrails. By the end, you will have made a real LLM call through the full CafeAI pipeline, without a Spring Boot dependency in sight.

The code is in `cafeai-capstone/support-agent`. The tests pass. The application runs.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
