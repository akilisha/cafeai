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
[ Semantic cache ]          — if enabled
    ↓
[ Session memory read ]     — load conversation history for this session ID
    ↓
[ RAG retrieval ]           — semantic search against the registered vector store
    ↓
[ LLM call ]                — waits for the token budget, retries on rate limit, observed
    ↓
[ POST_LLM guardrails ]     — PII, toxicity, secrets, prompt leaks
    ↓
[ Session memory write ]    — store prompt + response for next turn
    ↓
Response
```

Each layer is independent. Register it or leave it out and the others do not change. The LLM call does not know whether RAG ran before it. The guardrail does not know what the session history contains. The observability layer records the call and does not affect what the caller receives.

`app.vision()` and `app.audio()` run the same guardrail, memory and observability steps around their own model call.

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
    void handle(Request req, Response res, Next next);
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
    res.set("X-Powered-By", "CafeAI");
    next.run();
});
```

A Java developer who has never written Express reads the CafeAI version and understands it. An Express developer who has never written Java reads both and sees the same structure. This is not accidental. The naming, the contract, the composition model — all deliberately mirrored.

---

## Post-Processing Middleware

The Express mental model has one nuance that CafeAI preserves: middleware can run both before and after the downstream handler.

```java
app.filter((req, res, next) -> {
    long start = System.nanoTime();

    next.run();  // downstream runs here — including the LLM call

    log.info("{} took {} ms", req.path(), (System.nanoTime() - start) / 1_000_000);
});
```

The call to `next.run()` blocks until all downstream middleware and the final handler have completed. Everything before it is pre-processing; everything after it is post-processing. By then the response has already gone to the client, so post-processing is for logging, metrics and cleanup — not for changing what the caller receives.

POST_LLM guardrails are not built this way. They run inside `app.prompt()` on the model's answer, before it is returned to your handler, so they can replace or block it.

---

## HTTP Sessions

The pre/post split above has a sharp edge, and the built-in session middleware is the cleanest place to see it. A cookie is a response header, and headers set after `next.run()` returns do not reach the client — by then the terminal handler has almost certainly already called `res.send()`/`res.json()`, which commits the response. So `Middleware.session(...)` doesn't set the cookie in ordinary post-processing:

```java
app.filter(Middleware.session(SessionStore.sqlite()));

app.post("/login", (req, res, next) -> {
    req.session().set("userId", user.id());
    res.json(Map.of("status", "ok"));
});

app.post("/logout", (req, res, next) -> {
    req.session().invalidate();
    res.json(Map.of("status", "ok"));
});
```

One disambiguation before going further: this is not the "session" from [Post 5](05-context-memory-without-cloud-tax.md) — `MemoryStrategy`'s tiered rungs are LLM chat history, keyed by a `sessionId` you choose (usually an `X-Session-Id` header). This is the other, older meaning of the word: the classic Express-style HTTP session, a cookie pointing at a server-side bag of attributes — login state, cart contents, flash messages. Same English word, unrelated concept. The cookie name defaults to `cafeai.sid` rather than something session-sounding, on purpose, so a request trace showing both an `X-Session-Id` header and a `cafeai.sid` cookie doesn't read as a contradiction — it's two different features that happen to share a name.

`SessionStore.inMemory()` is the zero-dependency rung — fine for development, gone on the next restart, same caveat `MemoryStrategy.inMemory()` carries. `SessionStore.sqlite()` is the real default: WAL-mode SQLite via `cafeai-session`, real concurrent connections, sessions that survive a restart. Neither is chosen for you — every store is explicit at the call site, so what you get is never a surprise depending on what happens to be on the classpath.

`SqliteSessionStore` is single-instance only, and deliberately so — this is where the story diverges from Post 5's Redis rung. `MemoryStrategy.redis(...)` is a maintained CafeAI rung: add `cafeai-memory`, call `MemoryStrategy.redis(config)`, done. There is no equivalent `SessionStore.redis(...)`. A multi-instance deployment needs a session store shared across pods, and CafeAI does not ship one — `SessionStore` is a five-method interface, and `RedisSessionExample` in `cafeai-examples` shows the ~40 lines it takes to back it with Lettuce yourself. The asymmetry is deliberate: worth calling out precisely because a reader who just finished Post 5 will expect symmetry and not find it.

So where *does* the cookie get set? Not before `next.run()` either — a fixed ID could be decided that early, but committing to it before the handler runs would mean the middleware can't tell yet whether the handler will call `invalidate()` a moment later. CafeAI resolves this with a third point in a response's lifecycle, alongside pre- and post-processing: `res.beforeSend(...)`, a hook that runs immediately before whichever terminal call (`send`/`json`/`end`/`redirect`/...) actually commits the response — late enough to reflect everything the handler did, early enough that a header set there still reaches the client:

```java
res.beforeSend(() -> {
    session.touch();
    store.save(session);
    res.cookie("cafeai.sid", session.id(), cookieOptions);
});
```

`store.save(...)`/`store.destroy(...)` — plain persistence, not HTTP output — could safely happen in ordinary post-processing; the cookie write is what needs `beforeSend`. The one case that needs to touch *this* response's cookie even earlier than that is `invalidate()`: a `/logout` route calls it, then sends its own `res.json(...)`, all before the handler returns and `beforeSend` fires. So invalidation doesn't wait either — it clears the cookie synchronously, the moment it's called, and the `beforeSend` hook checks for that and skips re-setting it.

### The stateless alternative: `cookieSession`

`Middleware.session(store)` mirrors Express's `express-session` — the cookie carries only an ID, the data lives server-side. Express ships a second package, `cookie-session`, with the opposite architecture: no server-side store at all, the entire attribute bag is HMAC-signed straight into the cookie value. CafeAI has both — `Middleware.cookieSession(secret)` is the second one:

```java
app.filter(Middleware.cookieSession(System.getenv("SESSION_SECRET")));
```

`beforeSend` isn't just convenient for `cookieSession` — it's the only place this can work at all. For `Middleware.session(store)`, the cookie only ever needs to carry a fixed, pre-decided ID, so setting it earlier was always an option (an earlier draft of this middleware did exactly that, before `beforeSend` existed, at the cost of an occasional redundant `Set-Cookie` header on the same response). `cookieSession` has no such escape: the cookie's *content* is the session data, and that data isn't final until the handler has finished calling `req.session().set(...)` — which happens during `next.run()`. There is no point in the old pre/post model where a correct value was ever available. `beforeSend` is what makes the feature possible, not just cleaner.

Signing proves the cookie wasn't tampered with; it does not hide its contents — anything in a `cookieSession` is readable by the client, so it's the wrong tool for anything secret. A tampered, expired, or wrong-secret cookie is treated exactly like no cookie at all: a fresh session, never an error response. And because there's no store, `SessionStore.redis(...)`-style horizontal scaling isn't a question here the way it is for `Middleware.session(...)` — a signed cookie is inherently shareable across any number of instances that hold the same secret.

---

## Guardrails as Middleware

The most important application of this pattern in CafeAI is guardrails. In most frameworks, safety checks are an afterthought — a library you call, a function you wrap around the LLM invocation, something that lives outside the pipeline and gets forgotten when deadlines arrive.

In CafeAI, a guardrail is registered once and has a position:

```java
// Registered at startup — applied by the engine on every prompt, vision and audio call
app.guard(GuardRail.jailbreak());           // PRE_LLM — blocks before the call
app.guard(GuardRail.pii());                 // BOTH — checks input and output
app.guard(GuardRail.toxicity());            // BOTH — checks input and output
app.guard(GuardRail.regulatory().gdpr());   // PRE_LLM — screens the input only
```

The developer never calls these explicitly. They are registered once and the pipeline executes them on every call. Removing a guardrail is removing one line from startup registration. Adding one is adding one line. The LLM call doesn't change. The routes don't change. The guardrails are the pipeline, not the wrapper around it.

The PRE_LLM position runs before the LLM sees the prompt — blocking jailbreak attempts and PII before they are sent. The POST_LLM position runs after the response arrives — checking the response for PII, toxic content, or a leaked system prompt. The BOTH position runs in both places.

This is why guardrails being part of the pipeline matters: they are not a function call the developer has to remember to make. The engine applies them at every call site, and a test in the framework checks that it does.

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
app.use("/api", apiRouter);
```

Path parameters, query strings, wildcard routes, sub-routers — the full Express routing model is present. A developer who knows Express knows CafeAI routing. A developer who knows CafeAI routing knows Express.

The sub-router pattern is particularly useful for versioned AI APIs:

```java
var v1 = CafeAI.Router();
v1.post("/chat",  chatHandler);
v1.post("/embed", embedHandler);
v1.get("/health", healthHandler);

app.use("/api/v1", v1);
```

---

## The Composability Payoff

The real payoff of the middleware model appears when you need to change something.

Suppose you have a production AI application and you need to add PII detection. In a typical implementation, you find every place where prompts are assembled and add a scrub call. You pray you didn't miss any. You write tests for each call site.

In CafeAI:

```java
app.guard(GuardRail.pii());
```

One line, added to startup. PII detection now runs on every prompt call, every vision call, every audio call — automatically, without touching any of the call sites.

Suppose you need to add observability. Same story:

```java
app.observe(ObserveStrategy.otel());
```

One line. OpenTelemetry traces now appear on every LLM call, with model ID, token counts, latency, and RAG documents retrieved. Nothing else changes.

Suppose you need to swap providers from OpenAI to Anthropic:

```java
// Before
app.ai(OpenAI.of("gpt-4o"));

// After
app.ai(Anthropic.of("claude-sonnet-5"));
```

One line. The routes, the guardrails, the memory strategy, the RAG pipeline — unchanged.

This is the composability payoff. It is not a theoretical benefit. It is a practical consequence of treating every concern as a middleware layer with a clear interface, rather than weaving concerns together in application code.

---

## What the Next Post Covers

Post 3 walks through the first real CafeAI application from scratch — a customer support assistant backed by a knowledge base, with session memory and guardrails. By the end, you will have made a real LLM call through the full CafeAI pipeline, without a Spring Boot dependency in sight.

The code is in `capstones/support-desk`, and it runs as a normal Gradle application.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
