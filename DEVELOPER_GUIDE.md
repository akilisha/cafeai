# CafeAI Developer Guide

**Version:** 0.1.0-SNAPSHOT  
**Java:** 21+  
**Runtime:** Helidon SE 4.x  
**Last updated:** March 2026

---

## Table of Contents

1. [The Mental Model](#1-the-mental-model)
2. [Standing Up an Application](#2-standing-up-an-application)
3. [Two Registration Surfaces](#3-two-registration-surfaces)
4. [The Request/Response Lifecycle](#4-the-requestresponse-lifecycle)
5. [Working With Requests](#5-working-with-requests)
6. [Working With Responses](#6-working-with-responses)
7. [Sub-routers: Modular Route Organisation](#7-sub-routers-modular-route-organisation)
8. [Application Settings and Locals](#8-application-settings-and-locals)
9. [Error Handling](#9-error-handling)
10. [How Architecture Shapes Your Usage](#10-how-architecture-shapes-your-usage)
11. [The AI Layer — Talking to LLMs](#11-the-ai-layer--talking-to-llms)
    - [11.1 Registering a Provider](#111-registering-a-provider)
    - [11.2 Your First LLM Call](#112-your-first-llm-call)
    - [11.3 The System Prompt](#113-the-system-prompt--your-ais-persona)
    - [11.4 Prompt Templates](#114-prompt-templates--separating-engineering-from-execution)
    - [11.5 Conversation Memory](#115-conversation-memory--sessions-that-remember)
    - [11.6 Putting It All Together](#116-putting-it-all-together)
    - [11.7 What's Coming Next](#117-whats-coming-next)
12. [File Uploads, Downloads, and SSE](#12-file-uploads-downloads-and-sse)
    - [12.1 Body handler registration](#121-body-handler-registration)
    - [12.2 Multipart file upload](#122-multipart-file-upload)
    - [12.3 Binary upload](#123-binary-upload)
    - [12.4 File download](#124-file-download)
    - [12.5 Server-Sent Events](#125-server-sent-events-sse)
13. [WebSocket](#13-websocket)
    - [13.1 HTTP and WebSocket on the same port](#131-http-and-websocket-on-the-same-port)
    - [13.2 The WsHandler interface](#132-the-wshandler-interface)
    - [13.3 Multi-client patterns](#133-multi-client-patterns)
14. [Tiered Memory](#14-tiered-memory)
    - [14.1 Choosing a memory tier](#141-choosing-a-memory-tier)
    - [14.2 The cafeai-memory module](#142-the-cafeai-memory-module)
15. [RAG — Grounding the LLM in Your Data](#15-rag--grounding-the-llm-in-your-data)
    - [15.1 The setup](#151-the-setup)
    - [15.2 Sources](#152-sources)
    - [15.3 Embedding models](#153-embedding-models)
    - [15.4 Retrieval strategies](#154-retrieval-strategies)
    - [15.5 Accessing retrieved documents](#155-accessing-retrieved-documents-in-handlers)
16. [Module Structure and Dependencies](#16-module-structure-and-dependencies)
17. [cafeai-connect — Out-of-Process Service Connectivity](#17-cafeai-connect--out-of-process-service-connectivity)
    - [17.1 Two kinds of extension](#171-two-kinds-of-extension)
    - [17.2 Adding cafeai-connect](#172-adding-cafeai-connect)
    - [17.3 The four built-in connectors](#173-the-four-built-in-connectors)
    - [17.4 Fallback policies](#174-fallback-policies--operational-intelligence-at-the-connection-level)
    - [17.5 Environment-driven configuration](#175-environment-driven-configuration)
    - [17.6 Health checks](#176-health-checks)
    - [17.7 Custom connections](#177-implementing-a-custom-connection)
    - [17.8 Why this boundary matters](#178-why-this-boundary-matters)
18. [Chains — Composable AI Processing Pipelines](#18-chains--composable-ai-processing-pipelines)
    - [18.1 What a chain is](#181-what-a-chain-is)
    - [18.2 Registering and invoking a chain](#182-registering-and-invoking-a-chain)
    - [18.3 Built-in steps](#183-built-in-steps)
    - [18.4 Chains are immutable and composable](#184-chains-are-immutable-and-composable)
    - [18.5 Accessing chain results](#185-accessing-chain-results-in-the-final-handler)
19. [Guardrails — Ethical and Safety Middleware](#19-guardrails--ethical-and-safety-middleware)
    - [19.1 Guardrails are middleware](#191-guardrails-are-middleware)
    - [19.2 Activating real implementations](#192-activating-real-implementations)
    - [19.3 Available guardrails](#193-available-guardrails)
    - [19.4 Guardrail position](#194-guardrail-position)
    - [19.5 Guardrail violations](#195-guardrail-violations)
    - [19.6 Composing guardrails](#196-composing-guardrails)

---

## 1. The Mental Model

Before anything else, internalize one idea. In CafeAI, there is exactly one abstraction:

```java
(req, res, next) -> { ... }
```

That's it. A route handler is a middleware that doesn't call `next.run()`. A body parser is a
middleware that calls `next.run()`. An auth check is a middleware. A guardrail is a middleware.
A PII scrubber is a middleware. An LLM call is a middleware. There is no other type.

This isn't a simplification — it's the architectural center of gravity that everything else
follows from. If you understand this one idea, everything else in CafeAI is a consequence of it.

The type is `Middleware`, defined as:

```java
@FunctionalInterface
public interface Middleware {
    void handle(Request req, Response res, Next next);
}
```

- **Call `next.run()`** to pass control to the next middleware in the chain.
- **Don't call `next.run()`** to terminate the chain and send the response.
- **Call `next.fail(err)`** to route the request to the error-handling chain.

---

## 2. Standing Up an Application

```java
var app = CafeAI.create();

app.filter(CafeAI.json());               // parse JSON bodies before any route sees them
app.filter(Middleware.requestLogger());   // log every request

app.get("/health",
    (req, res, next) -> res.json(Map.of("status", "ok")));

app.listen(8080, () -> System.out.println("☕ Running on :8080"));
```

- `CafeAI.create()` — creates the application, discovers modules via ServiceLoader
- `filter()` — registers cross-cutting middleware (see §3)
- Route methods (`get`, `post`, `put`, etc.) — register handlers
- `listen(port)` — starts Helidon SE, blocks until the server is ready to accept connections
- `listen(port, callback)` — same, but invokes the callback once the server is up

The entire configuration — filters, routes, settings — must be registered **before** `listen()`.
Attempting to register routes after the server starts throws `IllegalStateException`.

---

## 3. Two Registration Surfaces

This is where CafeAI differs most visibly from Express. There are two distinct ways to register
middleware, and the distinction matters deeply.

### `app.filter()` — Cross-cutting pre-processing

Runs **before route dispatch**, for every request (or every request matching a path prefix).
Use this for concerns that must execute regardless of which route is eventually matched:

```java
app.filter(CafeAI.json());                     // parses every request body
app.filter(Middleware.cors());                  // CORS headers on every response
app.filter(Middleware.requestLogger());         // logs every request
app.filter("/api", Middleware.rateLimit(100));  // rate-limits /api/** only
app.filter(GuardRail.pii());                   // scrubs PII before any handler sees it
```

Filter middleware runs in its own call frame, before Helidon even attempts to match a route.
If no route matches (404), filter middleware still ran. This is where you put anything that
must always happen.

### Route methods — Per-route pipelines

Route methods accept **variadic middleware**, forming an inline pipeline per route:

```java
app.get("/users/:id",
    authenticate,             // checks JWT, calls next.run() if valid
    authorize("admin"),       // checks role, calls next.run() if permitted
    (req, res, next) ->       // terminates — doesn't call next.run()
        res.json(userService.find(req.params("id"))));
```

Each element is a `Middleware`. The first two pass control forward; the last one terminates.
The array **is** the per-route architecture — readable top to bottom, no ceremony required.

### The decision rule

| Use | When |
|---|---|
| `app.filter(mw)` | Runs for every request, regardless of route |
| `app.filter("/path", mw)` | Runs for every request to a path prefix |
| `app.get("/path", mw1, mw2, handler)` | Route-specific pipeline |

A good mental check: if removing the middleware would change the behavior for *all* requests,
it belongs in `filter()`. If it only makes sense for *this route*, it belongs in the route array.

---

## 4. The Request/Response Lifecycle

Here is exactly what happens from the moment an HTTP request arrives:

```
HTTP Request arrives
        │
        ▼
┌─────────────────────────────────────────────┐
│  Filter Chain  (app.filter() entries)       │
│                                             │
│  1. CafeAI.json()         parses body       │
│  2. requestLogger()       logs start        │
│  3. cors()                sets headers      │
│  4. rateLimit("/api")     checks budget     │
│                                             │
│  Each calls next.run() to continue.         │
│  Not calling next.run() short-circuits      │
│  everything — no route handler executes.    │
└──────────────────┬──────────────────────────┘
                   │  chain.proceed() →
                   ▼
┌─────────────────────────────────────────────┐
│  Route Matching                             │
│                                             │
│  GET /users/42  →  matched                  │
│  No match       →  404                      │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Route Handler Chain  (variadic array)      │
│                                             │
│  1. authenticate       validates JWT        │
│  2. authorize("admin") checks role          │
│  3. handler            calls res.json()     │
│                                             │
│  Last handler terminates (no next.run()).   │
│  Response is committed here.                │
└──────────────────┬──────────────────────────┘
                   │  response committed
                   ▼
┌─────────────────────────────────────────────┐
│  Post-processing                            │
│                                             │
│  Code written AFTER next.run() in filter    │
│  middleware executes here, as the call      │
│  stack unwinds in reverse registration      │
│  order.                                     │
└─────────────────────────────────────────────┘
```

### Post-processing — a genuine CafeAI advantage

The post-processing model deserves special attention. In Express you need `async/await` to
write post-processing middleware correctly:

```javascript
// Express — must await or post-code runs before the response
app.use(async (req, res, next) => {
    const start = Date.now();
    await next();                   // must await
    console.log(Date.now() - start);
});
```

In CafeAI, `next.run()` is a real **blocking call** on a Java 21 virtual thread. The thread
parks, the entire downstream chain executes, and control returns to the line after `next.run()`.
No async ceremony. No `await`. No promise chaining:

```java
app.filter((req, res, next) -> {
    long start = System.nanoTime();
    next.run();                               // blocks — full downstream executes here
    long ms = (System.nanoTime() - start) / 1_000_000;
    log.info("{} {} — {}ms", req.method(), req.path(), ms);  // post-processing, naturally
});
```

This pattern also works for finally-style cleanup:

```java
app.filter((req, res, next) -> {
    MDC.put("requestId", UUID.randomUUID().toString());
    try {
        next.run();
    } finally {
        MDC.clear();   // always runs, even if an exception propagates
    }
});
```

The virtual thread model makes the interceptor pattern trivially correct by default.

### One request, one pair of objects

A single `Request` and `Response` instance is created when the first filter runs and flows
through every filter and handler for that request. This is what makes body-parsing filters
work — `CafeAI.json()` populates `req.body()` in the filter chain, and the route handler
reads the same populated object.

---

## 5. Working With Requests

`req` exposes the full incoming HTTP request. The key principle: **middleware earlier in the
chain populates state that middleware later in the chain reads.**

### Identity and routing

```java
req.method()       // → "GET", "POST", etc.
req.path()         // → "/users/42"  (no query string)
req.originalUrl()  // → "/users/42?include=profile"
req.hostname()     // → "api.example.com"  (strips port)
req.ip()           // → "203.0.113.1"  (respects TRUST_PROXY setting)
req.secure()       // → true if HTTPS
req.xhr()          // → true if X-Requested-With: XMLHttpRequest
req.stream()       // → true if Accept: text/event-stream  (SSE clients)
req.app()          // → the CafeAI application instance
```

### Parameters and query

```java
// Route: GET /orgs/:org/repos/:repo?page=2
req.params("org")          // → "cafeai"
req.params("repo")         // → "core"
req.params()               // → Map { "org": "cafeai", "repo": "core" }

req.query("page")          // → "2"
req.query("sort", "asc")   // → "asc"  (default when absent)
req.queryMap()             // → Map<String, List<String>>  (multi-value aware)
```

### Body access — requires body-parsing filter

Body methods return populated state only after the corresponding `app.filter(CafeAI.json())`
(or `.text()`, `.raw()`, `.urlencoded()`) has run earlier in the chain.

```java
// JSON body (requires app.filter(CafeAI.json()))
req.body()                     // → Map<String, Object>
req.body("name")               // → single value as String
req.body(UserDto.class)        // → typed deserialization via Jackson (FAIL_ON_UNKNOWN=false)

// Text body (requires app.filter(CafeAI.text()))
req.bodyText()                 // → raw body as String

// Binary body (requires app.filter(CafeAI.raw()))
req.bodyBytes()                // → raw body as byte[]
```

### Headers and content negotiation

```java
req.header("Authorization")              // → "Bearer abc123"  (case-insensitive)
req.headers()                            // → Map<String, String>

req.is("application/json")               // → true if Content-Type matches
req.accepts("json", "html")              // → "json"  (best match from Accept header)
req.acceptsLanguages("en", "fr")         // → "en"  or null if no match
```

### Middleware-to-handler data passing

This is the typed, thread-safe replacement for Express's ad-hoc property assignment
(`req.user = ...`):

```java
// Auth middleware — sets the authenticated principal
Middleware authenticate = (req, res, next) -> {
    String token = req.header("Authorization");
    User user = tokenService.verify(token);
    if (user == null) {
        res.sendStatus(401);
        return;                           // short-circuit — next.run() not called
    }
    req.setAttribute("user", user);       // passes data downstream
    next.run();
};

// Route handler — reads it
app.get("/profile", authenticate,
    (req, res, next) -> {
        User user = req.attribute("user", User.class);  // typed retrieval
        res.json(user.profile());
    });
```

Pre-defined attribute key constants avoid magic strings:

```java
req.setAttribute(Attributes.AUTH_PRINCIPAL, principal);
req.setAttribute(Attributes.GUARDRAIL_SCORE, score);
req.setAttribute(Attributes.RAG_DOCUMENTS, docs);

Principal p = req.attribute(Attributes.AUTH_PRINCIPAL, Principal.class);
```

---

## 6. Working With Responses

`res` follows a fluent builder pattern until you call a terminal method that commits the
response. Once committed, attempting to send again throws `IllegalStateException`.

### Fluent (non-committing) methods — chainable

```java
res.status(201)                              // set status code
   .set("X-Request-Id", requestId)           // set a single header
   .set(Map.of("X-A", "1", "X-B", "2"))     // set multiple headers
   .type("json")                             // shorthand Content-Type
   .cookie("session", token, cookieOpts)     // set a cookie
   .location("/new/path");                   // set Location header
```

### Terminal methods — commit the response

```java
res.json(body);              // serialises to JSON, sets Content-Type: application/json
res.send("text");            // sends string, defaults to text/html
res.send(bytes);             // sends binary, defaults to application/octet-stream
res.sendStatus(204);         // status only — 204 and 304 send no body (HTTP spec)
res.redirect("/new-path");   // 302 redirect
res.redirect(301, "/moved"); // explicit status redirect
res.end();                   // empty body, current status
```

### Content negotiation

```java
res.format(ContentMap.of()
    .json(() -> res.json(user))
    .html(() -> res.send("<h1>" + user.name() + "</h1>"))
    .text(() -> res.send(user.name()))
    .build());
// → selects handler based on Accept header; sends 406 if no match
```

### Checking response state

```java
if (!res.headersSent()) {
    res.status(500).json(Map.of("error", "Something went wrong"));
}
```

---

## 7. Sub-routers: Modular Route Organisation

As your application grows, group related routes into routers and compose them:

```java
// UserRouter.java
public class UserRouter {
    public static Router create(UserService userService) {
        var router = CafeAI.Router();

        router.get("/",
            (req, res, next) -> res.json(userService.findAll()));

        router.get("/:id",
            (req, res, next) -> res.json(userService.find(req.params("id"))));

        router.post("/",
            (req, res, next) ->
                res.status(201).json(userService.create(req.body(CreateUserDto.class))));

        router.delete("/:id",
            (req, res, next) -> {
                userService.delete(req.params("id"));
                res.sendStatus(204);
            });

        return router;
    }
}

// Main application
app.use("/users", UserRouter.create(userService));
// → GET  /users/       → findAll
// → GET  /users/42     → find one
// → POST /users/       → create
// → DELETE /users/42   → delete
```

Sub-routers compose cleanly. You can nest them, pass them between files, and test them
in isolation without starting a server. The fluent `app.route()` builder avoids path
string repetition when registering multiple methods on the same path:

```java
app.route("/books")
   .get((req, res, next)  -> res.json(books.findAll()))
   .post((req, res, next) -> res.status(201).json(books.create(req.body(BookDto.class))))
   .put((req, res, next)  -> res.json(books.update(req.body(BookDto.class))));
```

---

## 8. Application Settings and Locals

### Settings — typed Express-compatible configuration

```java
app.set(Setting.ENV, "production");
app.enable(Setting.TRUST_PROXY);        // boolean settings
app.disable(Setting.X_POWERED_BY);

String env      = app.setting(Setting.ENV, String.class);     // → "production"
boolean trusted = app.enabled(Setting.TRUST_PROXY);           // → true
boolean hidden  = app.disabled(Setting.X_POWERED_BY);         // → true
```

Calling `app.set(setting, null)` resets the setting to its default value.

| Setting | Default | Type | Notes |
|---|---|---|---|
| `ENV` | `"development"` | String | Set to `"production"` for production |
| `TRUST_PROXY` | `false` | Object | `true` or hop count for proxy chains |
| `X_POWERED_BY` | `true` | Boolean | Disable in production to reduce fingerprinting |
| `CASE_SENSITIVE_ROUTING` | `false` | Boolean | `/Foo` vs `/foo` |
| `STRICT_ROUTING` | `false` | Boolean | `/foo/` vs `/foo` |
| `VIEWS` | `"views"` | String | Template directory |
| `VIEW_ENGINE` | `null` | String | Must be set explicitly |

### Locals — application-lifetime data

Store service instances, configuration, and shared data in `app.local()`. Everything stored
here is accessible from any middleware via `req.app().local()`:

```java
// At startup
app.local("db",     dataSource);
app.local("config", appConfig);
app.local("cache",  cacheClient);

// In any middleware or handler
DataSource db     = req.app().local("db",     DataSource.class);
AppConfig  config = req.app().local("config", AppConfig.class);
```

`app.locals()` returns an unmodifiable snapshot of all user-defined locals, excluding
internal CafeAI infrastructure keys (`__cafeai.*`).

---

## 9. Error Handling

Register error handlers with `app.onError()`. They fire when any middleware calls
`next.fail(err)` or when an uncaught exception propagates up the chain.

```java
// Trigger from any handler
app.get("/users/:id",
    (req, res, next) -> {
        try {
            res.json(userService.find(req.params("id")));
        } catch (NotFoundException e) {
            next.fail(e);   // → routed to error handler below
        }
    });

// Handle centrally — registered after routes
app.onError((err, req, res, next) -> {
    if (err instanceof NotFoundException) {
        res.status(404).json(Map.of("error", err.getMessage()));
    } else if (err instanceof AuthException) {
        res.status(401).json(Map.of("error", "Unauthorized"));
    } else {
        log.error("Unhandled error on {} {}", req.method(), req.path(), err);
        if (!res.headersSent()) {
            res.status(500).json(Map.of("error", "Internal server error"));
        }
    }
});
```

Multiple error handlers are chained. Call `next.run()` inside an error handler to pass to
the next one. If no registered handler handles the error, CafeAI's default handler sends
a 500 response.

---

## 10. How Architecture Shapes Your Usage

Three decisions that directly influence how you write CafeAI applications:

### Virtual threads: you never write async code

Database calls, HTTP calls to other services, LLM calls — write them all synchronously.
The virtual thread parks while waiting and is descheduled by the JVM at zero cost.
You get the simplicity of blocking code with the throughput of non-blocking I/O.

`next.run()` is synchronous. Everything is synchronous. This is a feature.

```java
// This is correct and efficient — no CompletableFuture, no reactive types
app.post("/chat",
    (req, res, next) -> {
        String response = llmClient.complete(req.body("message")); // blocks — that's fine
        res.json(Map.of("response", response));
    });
```

### The `filter`/`use` distinction makes architecture readable

When you register middleware, you make an explicit statement about execution scope.
Scanning `filter()` calls top to bottom tells you everything that runs unconditionally
for every request. Route arrays tell you what's specific to each route.

This makes large applications readable in a way that Express's single `app.use()` surface
doesn't guarantee — in CafeAI, the architecture is visible in the registration calls.

### Everything being `Middleware` unifies HTTP and AI logic

A guardrail that blocks PII, an LLM call that generates a response, and a rate limiter that
rejects overuse are all `(req, res, next) -> {}`. You chain them, test them in isolation,
reorder them, and reason about them uniformly.

This becomes the load-bearing insight when the AI primitives arrive. A RAG retrieval step,
a prompt injection guardrail, a token budget enforcer, and a streaming LLM response all
slot into the exact same pipeline you've already been building:

```java
app.post("/chat",
    Middleware.rateLimit(60),         // HTTP concern — same shape
    GuardRail.pii(),                  // AI concern — same shape
    GuardRail.jailbreak(),            // AI concern — same shape
    Rag.retrieve(vectorStore),        // AI concern — same shape
    (req, res, next) ->               // terminates
        res.stream(llm.stream(req.body("message"))));
```

The pipeline is the application. Reading it top to bottom describes the entire request
lifecycle — what runs, in what order, for what purpose. That readability is the design.

---

## 11. The AI Layer — Talking to LLMs

Everything in sections 1–10 was the HTTP foundation. This is where CafeAI stops being a
web framework and becomes a Gen AI framework. The AI primitives — provider registration,
prompt invocation, templates, conversation memory — follow the exact same design philosophy
as everything that came before. They slot into the pipeline you already know.

---

### 11.1 Registering a Provider

The first thing you do in any AI-powered CafeAI application:

```java
var app = CafeAI.create();
app.ai(OpenAI.gpt4o());
```

That's it. One line. You've declared "this application speaks to GPT-4o." All subsequent
`app.prompt()` calls in any handler, anywhere in your application, will use this provider.

**Switching providers requires zero application code changes.** You change one line at startup
and every prompt in your app automatically uses the new model:

```java
// Development — local, free, no data leaves your machine
app.ai(Ollama.llama3());

// Production — OpenAI
app.ai(OpenAI.gpt4o());

// Production — Anthropic
app.ai(Anthropic.claude35Sonnet());
```

**Cost-aware routing** with `ModelRouter` lets you automatically send simple queries to a
cheaper model and complex queries to a more capable one:

```java
app.ai(ModelRouter.smart()
    .simple(OpenAI.gpt4oMini())    // fast + cheap — classification, short answers
    .complex(OpenAI.gpt4o()));     // powerful — reasoning, long context, tool use
```

The router currently uses message length as a heuristic. A message under 500 characters goes
to the `simple` model; longer messages go to `complex`. Future phases will add explicit
complexity hints and token-count-based routing.

**API keys come from environment variables** — never from code:

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
```

If the key is absent and you try to call the provider, CafeAI throws an `IllegalStateException`
with an exact message telling you which variable to set — including a suggestion to use
Ollama locally if you don't have a key.

---

### 11.2 Your First LLM Call

```java
app.get("/ask", (req, res, next) -> {
    String question = req.query("q");
    PromptResponse response = app.prompt(question).call();
    res.send(response.text());
});
```

`app.prompt(message)` returns a `PromptRequest` — a fluent builder that does nothing until
you call `.call()`. That call is synchronous. It blocks the virtual thread, the LLM responds,
and control returns to the next line. No `CompletableFuture`, no callbacks, no reactive
chains. Just a method call that returns a value.

`PromptResponse` carries everything you need:

```java
PromptResponse response = app.prompt("What is 42?").call();

response.text()          // → "42 is the answer to life, the universe, and everything."
response.promptTokens()  // → 12   (tokens you paid for the input)
response.outputTokens()  // → 23   (tokens you paid for the output)
response.totalTokens()   // → 35   (promptTokens + outputTokens)
response.modelId()       // → "gpt-4o"   (which model actually answered)
response.fromCache()     // → false  (semantic cache — future phase)
response.toString()      // → same as .text() — usable directly as a String
```

A real handler that returns structured JSON:

```java
app.post("/analyze",
    (req, res, next) -> {
        PromptResponse response = app.prompt(req.body("text")).call();
        res.json(Map.of(
            "result",       response.text(),
            "model",        response.modelId(),
            "promptTokens", response.promptTokens(),
            "outputTokens", response.outputTokens()
        ));
    });
```

---

### 11.3 The System Prompt — Your AI's Persona

The system prompt is the first message in every LLM call. It defines the AI's role,
constraints, and personality. You set it once at startup:

```java
app.system("""
    You are a helpful customer service agent for Acme Corp.
    You are concise, accurate, and empathetic.
    Never discuss competitor products.
    If you cannot help with something, say so clearly and offer to escalate.
    """);
```

Every `app.prompt()` call automatically prepends this as a `SystemMessage` before the user's
message. You don't need to include it manually in each prompt.

**Calling `app.system()` multiple times — last call wins.** This lets you configure the
system prompt based on environment:

```java
if (app.enabled(Setting.ENV) && app.setting(Setting.ENV, String.class).equals("production")) {
    app.system("You are a production customer service agent...");
} else {
    app.system("You are a test assistant. Be verbose about what you're doing.");
}
```

**Per-call override** lets a single handler use a different persona without affecting the
rest of the application:

```java
app.post("/translate",
    (req, res, next) -> {
        String targetLang = req.body("language");
        PromptResponse response = app.prompt(req.body("text"))
            .system("You are a professional translator. Translate to " + targetLang +
                    ". Output only the translation, nothing else.")
            .call();
        res.send(response.text());
    });
```

The override applies only to this one call. `app.system()` is unchanged for all other requests.

---

### 11.4 Prompt Templates — Separating Engineering from Execution

A prompt template is a reusable prompt body with `{{variable}}` placeholders. Templates
separate *prompt engineering* — which you do once, carefully, at startup — from *prompt
execution* — which happens per request with different data.

**Register at startup:**

```java
app.template("classify",
    "Classify the following customer message into exactly one category: {{categories}}.\n" +
    "Respond with only the category name, nothing else.\n" +
    "Message: {{message}}");

app.template("summarize",
    "Summarize the following text in {{maxWords}} words or fewer.\n" +
    "Preserve the key facts. Do not add opinions.\n\n{{content}}");

app.template("extract-intent",
    "Given this customer message: \"{{message}}\"\n" +
    "Extract: intent (string), urgency (low/medium/high), sentiment (positive/neutral/negative).\n" +
    "Respond in JSON with keys: intent, urgency, sentiment.");
```

**Three ways to use templates:**

```java
// 1. Shorthand — render and prompt in one call
app.post("/classify", (req, res, next) -> {
    PromptResponse response = app.prompt("classify", Map.of(
        "categories", "billing, shipping, returns, general-inquiry",
        "message",    req.body("message")
    )).call();
    res.json(Map.of("category", response.text().trim().toLowerCase()));
});

// 2. Explicit retrieval — render yourself, then prompt
app.post("/summarize", (req, res, next) -> {
    String rendered = app.template("summarize").render(Map.of(
        "maxWords", req.body("maxWords") != null ? req.body("maxWords") : "100",
        "content",  req.body("text")
    ));
    PromptResponse response = app.prompt(rendered).call();
    res.send(response.text());
});

// 3. Strict rendering — throws if any variable is missing
app.post("/extract", (req, res, next) -> {
    try {
        String rendered = app.template("extract-intent").renderStrict(
            Map.of("message", req.body("message"))
        );
        PromptResponse response = app.prompt(rendered).call();
        res.json(response.text()); // model returns JSON
    } catch (Template.TemplateException e) {
        res.status(400).json(Map.of("error", e.getMessage()));
    }
});
```

`render()` is permissive — missing variables stay as `{{var}}` in the output. Use it when
some variables have defaults. `renderStrict()` is explicit — it throws `TemplateException`
with the name of the missing variable. Use it when all variables are required — it fails at
render time rather than sending a half-rendered prompt to the LLM.

**Asking for a template that doesn't exist throws immediately:**

```java
app.template("typo-in-name"); // → IllegalArgumentException:
                               // "No template registered with name 'typo-in-name'.
                               //  Register one at startup: app.template("typo-in-name", "...")"
```

The error message tells you exactly what to add. CafeAI never silently uses an empty string
or a default — configuration errors surface immediately at the point of misuse.

---

### 11.5 Conversation Memory — Sessions That Remember

By default, every LLM call is stateless. `app.memory()` gives your application conversational
context — the history of a session is automatically included in each new prompt, making
the AI aware of what was said before.

**Register the memory strategy at startup:**

```java
app.memory(MemoryStrategy.inMemory());   // JVM memory — zero config, zero deps
                                          // sessions survive requests but not restarts
```

**Use `.session()` on a prompt to activate memory for that call:**

```java
app.post("/chat", (req, res, next) -> {
    String message   = req.body("message");
    String sessionId = req.header("X-Session-Id");   // client sends this

    PromptResponse response = app.prompt(message)
        .session(sessionId)
        .call();

    res.json(Map.of("response", response.text()));
});
```

What happens under the hood on each `.session(id).call()`:

1. CafeAI looks up `sessionId` in the memory store
2. If a prior conversation exists, all its messages are prepended to the LLM context in order
3. The LLM receives: `[SystemMessage, ...history..., UserMessage("current message")]`
4. The LLM responds
5. CafeAI appends both the user message and the AI response to the session
6. The updated session is written back to the memory store

The session evolves automatically. The client just needs to send the same `X-Session-Id`
header on every request:

```bash
# First message — no history yet
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -H "X-Session-Id: user-42" \
     -d '{"message": "My name is Ada and I am a software engineer."}'
# → "Nice to meet you, Ada! How can I help you today?"

# Second message — AI remembers the first exchange
curl -X POST http://localhost:8080/chat \
     -H "Content-Type: application/json" \
     -H "X-Session-Id: user-42" \
     -d '{"message": "What do you know about me so far?"}'
# → "You told me your name is Ada and that you are a software engineer."
```

**Without `.session()`** the call is stateless — the AI has no memory of prior exchanges.
This is correct for one-shot use cases like classification or translation where history
would add noise and cost.

**Without `app.memory()`** registered, calling `.session(id)` still works — it just has
no store to read from or write to. No exception is thrown. This makes it safe to always
call `.session(id)` and let the memory strategy determine whether history is preserved.

---

### 11.6 Putting It All Together

Here is a complete, realistic AI-powered CafeAI application showing all four primitives
working together:

```java
var app = CafeAI.create();

// ── Provider — cost-aware routing ────────────────────────────────────────────
app.ai(ModelRouter.smart()
    .simple(OpenAI.gpt4oMini())    // classification, simple queries
    .complex(OpenAI.gpt4o()));     // chat, reasoning, complex analysis

// ── System Prompt — the AI's persona ─────────────────────────────────────────
app.system("""
    You are a helpful support assistant for a software company.
    You are concise, technical, and friendly.
    When you don't know something, say so — don't guess.
    """);

// ── Templates — prompt engineering, done once ─────────────────────────────────
app.template("triage",
    "Classify this support message into one category: bug, feature-request, billing, other.\n" +
    "Respond with only the category.\nMessage: {{message}}");

app.template("respond",
    "The customer's issue has been categorised as: {{category}}.\n" +
    "Compose a brief, empathetic response to: {{message}}");

// ── Memory — per-user conversation history ────────────────────────────────────
app.memory(MemoryStrategy.inMemory());

// ── Filters ────────────────────────────────────────────────────────────────────
app.filter(CafeAI.json());
app.filter(Middleware.requestLogger());
app.filter(GuardRail.pii());           // scrub PII before any LLM call
app.filter(GuardRail.jailbreak());

// ── Routes ─────────────────────────────────────────────────────────────────────

// One-shot triage — stateless, uses the simple model (short message)
app.post("/triage",
    (req, res, next) -> {
        PromptResponse triage = app.prompt("triage",
            Map.of("message", req.body("message"))
        ).call();

        res.json(Map.of(
            "category", triage.text().trim().toLowerCase(),
            "tokens",   triage.totalTokens()
        ));
    });

// Full support chat — stateful, uses session memory
app.post("/support",
    (req, res, next) -> {
        String message   = req.body("message");
        String sessionId = req.header("X-Session-Id");

        // Step 1: classify (stateless, cheap)
        String category = app.prompt("triage",
            Map.of("message", message)
        ).call().text().trim().toLowerCase();

        // Step 2: generate response (session-aware, remembers prior exchanges)
        PromptResponse response = app.prompt("respond",
            Map.of("category", category, "message", message)
        ).session(sessionId).call();

        res.json(Map.of(
            "response", response.text(),
            "category", category,
            "model",    response.modelId(),
            "tokens",   response.totalTokens()
        ));
    });

// Per-call persona override — translation doesn't need the support persona
app.post("/translate",
    (req, res, next) -> {
        String lang = req.body("language");
        PromptResponse response = app.prompt(req.body("text"))
            .system("You are a professional translator. Translate to " + lang +
                    ". Output only the translation.")
            .call();
        res.send(response.text());
    });

app.listen(8080);
```

The pipeline reads as a description of the application. Triage → respond is two prompt calls,
each using a named template, one stateless and one session-aware. The per-call `.system()`
override gives the translation endpoint its own persona without polluting the global one.
`GuardRail.pii()` and `GuardRail.jailbreak()` protect all three endpoints because they're
registered as filters.

---

### 11.7 What's Coming Next

The AI layer in this release covers Phases 1 and 2 of ROADMAP-07. Here's what's ahead:

**Phase 3 — Memory tiers.** `MemoryStrategy.inMemory()` is fully functional. The remaining
tiers — `mapped()` (SSD-backed, survives restarts), `redis()` (distributed, multi-instance),
and `hybrid()` (warm SSD + cold Redis) — are stubbed and will be implemented next. For most
development work, `inMemory()` is all you need.

**Phase 4 — RAG pipeline.** `app.vectordb()`, `app.embed()`, `app.ingest()`, `app.rag()`.
Ingest a PDF or a directory of documents, and CafeAI automatically retrieves the most
relevant chunks and injects them into the LLM context before every prompt. This is where
LLMs stop making things up and start answering from your actual data.

**Phase 5 — Tools and MCP.** `app.tool()` lets you register Java methods as LLM tools —
the model can invoke them as part of its reasoning. `app.mcp()` connects to any MCP server
and makes its capabilities available to the LLM alongside your Java tools.

**Phase 7 — Guardrails.** `GuardRail.pii()`, `GuardRail.jailbreak()`, etc. are currently
pass-through stubs. Full implementations using NLP and classifier models arrive in Phase 7.
Register them now — the API doesn't change, only the behaviour becomes real.

---

## 12. File Uploads, Downloads, and SSE

### 12.1 Body handler registration

CafeAI ships six body parsers. Each is a middleware — register the ones your
application needs as global filters at startup. They stack safely: each checks
`Content-Type` and only activates for matching requests, passing everything else
through with `next.run()`.

```java
app.filter(CafeAI.json());          // application/json       → req.body()
app.filter(CafeAI.urlencoded());    // application/x-www-form-urlencoded → req.body()
app.filter(CafeAI.text());          // text/plain             → req.bodyText()
app.filter(CafeAI.raw());           // application/octet-stream → req.bodyBytes()
app.filter(CafeAI.multipart());     // multipart/form-data    → req.file(), req.files()
app.filter(CafeAI.serveStatic("public"));  // serves /public/** as static files
```

Register all of them globally and let the parsers self-select — there's no
performance cost to registering parsers for content types your app doesn't use.

### 12.2 Multipart file upload

```java
app.filter(CafeAI.multipart());    // 10 MB per part, default
// app.filter(CafeAI.multipart(50 * 1024 * 1024));  // 50 MB per part

app.post("/upload", (req, res, next) -> {
    UploadedFile doc = req.file("document");     // the file field
    String note      = req.body("note");          // a text field from the same form

    if (doc == null) {
        res.status(400).json(Map.of("error", "no file in field 'document'"));
        return;
    }

    try {
        Path saved = doc.saveToDirectory(Path.of("/uploads"));
        res.status(201).json(Map.of(
            "name", doc.originalName(),
            "size", doc.size(),
            "type", doc.mimeType(),
            "path", saved.toString()
        ));
    } catch (java.io.IOException e) {
        next.fail(new RuntimeException("Save failed: " + e.getMessage(), e));
    }
});
```

`req.file(fieldName)` returns the first uploaded file for that field, or `null`.
`req.files(fieldName)` returns all files for that field (for multi-file inputs).
Text fields in the same form are accessible via `req.body(key)` as usual.

**The `IOException` rule:** any file I/O inside a middleware lambda must be wrapped in
`try-catch` — the `Middleware.handle()` signature does not declare `throws IOException`.
Catch it and route to `next.fail()` so the error handler sends a proper response.

### 12.3 Binary upload

When a client sends raw bytes (programmatic uploads, mobile apps, CLI tools), use
`application/octet-stream` and `req.bodyBytes()`:

```java
app.post("/upload/binary", (req, res, next) -> {
    byte[] bytes   = req.bodyBytes();
    String filename = req.header("X-Filename");    // client sends filename in a header

    if (bytes == null || bytes.length == 0) {
        res.status(400).send("empty body"); return;
    }

    // Sanitise: never trust client-provided filenames for path construction
    String safeName = Path.of(filename).getFileName().toString();

    try {
        Files.write(Path.of("/uploads/" + safeName), bytes);
        res.status(201).json(Map.of("bytes", bytes.length));
    } catch (java.io.IOException e) {
        next.fail(new RuntimeException(e));
    }
});
```

```bash
curl --data-binary @photo.jpg \
     -H "Content-Type: application/octet-stream" \
     -H "X-Filename: photo.jpg" \
     http://localhost:8080/upload/binary
```

### 12.4 File download

```java
app.get("/download/:filename", (req, res, next) -> {
    Path file = Path.of("/uploads/" + req.params("filename"));
    if (!Files.exists(file)) { res.sendStatus(404); return; }

    // res.download() sets Content-Disposition: attachment
    res.download(file);

    // Or send with a custom display name:
    // res.download(file, "report-" + LocalDate.now() + ".pdf");

    // Or serve inline (browser renders it instead of downloading):
    // res.sendFile(file);
});
```

### 12.5 Server-Sent Events (SSE)

SSE is a one-way stream from server to browser over a normal HTTP connection.
Use it for live feeds, progress updates, LLM token streaming — anything where
the server needs to push data without the overhead of WebSocket.

```java
app.get("/stream/events", (req, res, next) -> {
    // req.stream() is true when the client sends Accept: text/event-stream
    if (!req.stream()) {
        res.json(Map.of("hint", "Use Accept: text/event-stream"));
        return;
    }

    var publisher = new java.util.concurrent.SubmissionPublisher<String>();

    // res.stream() sets SSE headers and subscribes the publisher.
    // Returns immediately — the virtual thread is not blocked.
    res.stream(publisher);

    // Push events from a virtual thread — any source works here:
    // database changes, message queues, LLM token streams.
    Thread.ofVirtual().start(() -> {
        try {
            for (int i = 1; i <= 10; i++) {
                publisher.submit("event-" + i);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            publisher.close();   // sends data: [DONE]\n\n and closes
        }
    });
});
```

```bash
curl -H "Accept: text/event-stream" http://localhost:8080/stream/events
# data: event-1
# data: event-2
# ...
# data: [DONE]
```

LLM response streaming uses the same mechanism — `res.stream()` accepts any
`Flow.Publisher<String>`. Once the RAG and streaming phases are complete,
`app.prompt().stream()` will return one directly.

---

## 13. WebSocket

### 13.1 HTTP and WebSocket on the same port

`app.ws()` registers a WebSocket endpoint. The Helidon runtime upgrades
WebSocket handshake requests automatically — HTTP routes and WebSocket endpoints
coexist on the same port with no configuration. There is no separate WebSocket
server to start.

```java
var app = CafeAI.create();

app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));

app.ws("/ws/echo",
    WsHandler.onMessage((session, msg) -> session.send("Echo: " + msg)));

app.listen(8080);
// GET  http://localhost:8080/health  → works
// WS   ws://localhost:8080/ws/echo  → works
// Same port. No extra config.
```

### 13.2 The WsHandler interface

`WsHandler` has five lifecycle methods, all with safe default implementations.
Override only what you need:

```java
app.ws("/ws/chat", new WsHandler() {

    @Override
    public void onOpen(WsSession session) {
        // A new client connected — session is open and ready
        session.send("Welcome! Your session: " + session.id());
    }

    @Override
    public void onMessage(WsSession session, String message) {
        // A text message arrived from this client
        session.send("You said: " + message);
    }

    @Override
    public void onBinaryMessage(WsSession session, byte[] data) {
        // A binary frame arrived — handle file transfer, audio, etc.
    }

    @Override
    public void onClose(WsSession session, int statusCode, String reason) {
        // Client disconnected — 1000 = normal closure
        System.out.println("Closed: " + reason);
    }

    @Override
    public void onError(WsSession session, Throwable error) {
        // An error occurred on the connection
        System.err.println("WS error: " + error.getMessage());
    }
});
```

For message-only handlers, the lambda shorthand is cleaner:

```java
app.ws("/ws/echo", WsHandler.onMessage((session, msg) -> session.send(msg)));
```

### 13.3 Multi-client patterns

`WsHandler` is a single instance shared across all connections — one handler
object, many concurrent `WsSession`s. Use a thread-safe collection to track them:

```java
// Thread-safe session registry
private static final Set<WsSession> connected = ConcurrentHashMap.newKeySet();

app.ws("/ws/chat", new WsHandler() {

    @Override
    public void onOpen(WsSession session) {
        connected.add(session);
        broadcast("[" + session.id() + " joined] " + connected.size() + " online");
    }

    @Override
    public void onMessage(WsSession session, String message) {
        broadcast("[" + session.id() + "] " + message);
    }

    @Override
    public void onClose(WsSession session, int statusCode, String reason) {
        connected.remove(session);
        broadcast("[" + session.id() + " left]");
    }

    private void broadcast(String message) {
        connected.forEach(s -> {
            try { s.send(message); }
            catch (Exception e) { connected.remove(s); }
        });
    }
});
```

---

## 14. Tiered Memory

### 14.1 Choosing a memory tier

CafeAI's memory hierarchy mirrors hardware — start at the cheapest tier and
escalate only when the problem demands it:

| Tier | Method | Survives restart | Multi-instance | Requires |
|---|---|---|---|---|
| 1 | `inMemory()` | No | No | Nothing |
| 2 | `mapped()` | **Yes** | No | `cafeai-memory` |
| 4 | `redis(config)` | Yes | **Yes** | `cafeai-memory` + Redis |
| 5 | `hybrid()` | Yes | Yes | `cafeai-memory` + Redis |

For most development work, `inMemory()` is all you need:

```java
app.memory(MemoryStrategy.inMemory());
```

For production single-instance deployments where sessions should survive
a restart (crash recovery):

```java
app.memory(MemoryStrategy.mapped());                       // default dir
app.memory(MemoryStrategy.mapped(Path.of("/var/cafeai/sessions")));  // custom dir
```

For production multi-instance deployments:

```java
app.memory(MemoryStrategy.redis(
    RedisConfig.builder()
        .host("redis.prod.internal")
        .port(6379)
        .sessionTtl(Duration.ofHours(24))
        .build()));
```

For the best of both (fast local reads, distributed durability):

```java
app.memory(MemoryStrategy.hybrid()
    .warm(MemoryStrategy.mapped())
    .cold(MemoryStrategy.redis(redisConfig))
    .demoteAfter(Duration.ofMinutes(30))
    .build());
```

### 14.2 The cafeai-memory module

Tiers 2, 4, and 5 require `cafeai-memory` on the classpath. Without it, calling
`MemoryStrategy.mapped()` or `MemoryStrategy.redis()` throws
`MemoryModuleNotFoundException` with an exact message telling you what to add:

```groovy
// build.gradle
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-memory'   // unlocks mapped, redis, hybrid
}
```

`inMemory()` is always available with zero extra dependencies.

---

## 15. RAG — Grounding the LLM in Your Data

RAG (Retrieval Augmented Generation) is the difference between an LLM that
makes things up and one that answers from your actual documents. CafeAI makes it
four method calls at startup.

### 15.1 The setup

```java
// Requires cafeai-rag on the classpath
app.vectordb(VectorStore.inMemory())         // where vectors are stored
app.embed(EmbeddingModel.local())            // how text becomes vectors (no API key)
app.ingest(Source.pdf("docs/handbook.pdf"))  // what knowledge to load
app.ingest(Source.directory("knowledge/"))   // can ingest multiple sources
app.rag(Retriever.semantic(5))               // retrieve top-5 relevant chunks per query
```

Once registered, every `app.prompt().call()` automatically retrieves the most
relevant chunks from the vector store and injects them into the LLM context
before the user's message. The LLM sees: system prompt → relevant documents →
conversation history → user question.

```groovy
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-rag'    // unlocks vectordb, embed, ingest, rag
}
```

### 15.2 Sources

```java
Source.text("CafeAI is a Gen AI framework for Java.", "cafeai-intro")  // inline text
Source.file("README.md")                // plain text or markdown file
Source.pdf("docs/handbook.pdf")         // PDF via Apache Tika
Source.directory("knowledge/")          // all .txt, .md, .pdf files recursively
Source.url("https://docs.example.com")  // web page via Java's built-in HttpClient
```

Re-ingesting the same source is safe — CafeAI deletes existing chunks for that
source before re-ingesting. You get idempotent upserts, not duplicates.

### 15.3 Embedding models

```java
EmbeddingModel.local()                  // ONNX all-MiniLM-L6-v2, 384d, no API key
EmbeddingModel.openAi()                 // text-embedding-ada-002, 1536d, OPENAI_API_KEY
EmbeddingModel.openAi("text-embedding-3-large")  // higher quality, 3072d
```

`EmbeddingModel.local()` is the right default for most applications. It runs
entirely on the JVM, produces no network traffic, and the model quality is
sufficient for most document retrieval tasks.

### 15.4 Retrieval strategies

```java
Retriever.semantic(5)    // cosine similarity — good for conceptual questions
Retriever.hybrid(5)      // dense + BM25 keyword — better for exact terms and codes
```

### 15.5 Accessing retrieved documents in handlers

Retrieved documents are stored in `req.attribute(Attributes.RAG_DOCUMENTS)` so
handlers can reference them:

```java
app.post("/ask", (req, res, next) -> {
    PromptResponse response = app.prompt(req.body("question")).call();

    @SuppressWarnings("unchecked")
    List<RagDocument> sources = (List<RagDocument>)
        req.attribute(Attributes.RAG_DOCUMENTS);

    res.json(Map.of(
        "answer",  response.text(),
        "sources", sources == null ? List.of()
                   : sources.stream().map(RagDocument::sourceId).toList()
    ));
});
```

---

## 16. Module Structure and Dependencies

CafeAI is intentionally split into modules. You only pay the dependency cost for
what you use. The dependency graph flows in one direction — modules depend on
`cafeai-core`, and `cafeai-core` depends on nothing CafeAI-specific:

```
cafeai-core          ← always required; HTTP + AI primitives
cafeai-memory        ← optional; unlocks mapped, redis, hybrid memory
cafeai-rag           ← optional; unlocks vectordb, embed, ingest, rag
cafeai-examples      ← reference; not a runtime dependency
```

**The design rule:** `cafeai-core` never imports from `cafeai-rag` or
`cafeai-memory`. The optional modules register themselves via Java's
`ServiceLoader` — adding the JAR to the classpath activates the feature, zero
code changes required. This is the same mechanism Java uses for JDBC drivers.

```groovy
// Minimal — HTTP framework only
dependencies {
    implementation 'io.cafeai:cafeai-core'
}

// Add AI memory tiers
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-memory'
}

// Add RAG pipeline
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-memory'
    implementation 'io.cafeai:cafeai-rag'
}
```

When an optional module is absent and you call a method that requires it,
CafeAI throws an exception with an exact message telling you which dependency
to add and what the import should look like. It never silently degrades.

---

## 17. cafeai-connect — Out-of-Process Service Connectivity

### 17.1 Two kinds of extension

Before using `cafeai-connect`, understand the distinction it formalises.

CafeAI has two kinds of optional capability:

**In-process optional modules** run inside your JVM. They share CafeAI's
lifecycle. They are either present (JAR on classpath) or absent — nothing
in between. You register them with module-specific methods:

```java
app.memory(MemoryStrategy.inMemory());         // cafeai-memory in-process
app.vectordb(VectorStore.inMemory());          // cafeai-rag in-process
app.tool(new MyTools());                       // cafeai-tools in-process
```

**Out-of-process connections** run in separate processes. Redis doesn't start
when your JVM starts. Ollama doesn't stop when your JVM stops. They have
independent lifecycles, independent scale, and three possible states:
reachable, unreachable, or degraded. You register them all through one surface:

```java
app.connect(Redis.at("redis:6379"));
app.connect(Ollama.at("http://ollama:11434").model("llama3"));
app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai"));
app.connect(McpEndpoint.at("http://mcp-server:3000"));
```

This distinction is not cosmetic. A binary present/absent model is wrong for
out-of-process services. Redis can be running but refusing connections. Ollama
can be reachable but missing the model you need. `cafeai-connect` models this
correctly with three reachability states and an explicit degradation policy.

### 17.2 Adding cafeai-connect

```groovy
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-connect'   // unlocks app.connect()
    implementation 'io.cafeai:cafeai-memory'    // for Redis.at() to register memory
    implementation 'io.cafeai:cafeai-rag'       // for PgVector.at() to register vectordb
    implementation 'io.cafeai:cafeai-tools'     // for McpEndpoint.at() to register tools
}
```

`cafeai-connect` only hard-requires `cafeai-core`. The other modules are
needed if you use connectors that register into them (`Redis` → `cafeai-memory`,
`PgVector` → `cafeai-rag`, `McpEndpoint` → `cafeai-tools`).

### 17.3 The four built-in connectors

**Redis** — connects to a Redis server and registers it as the memory strategy:

```java
app.connect(Redis.at("redis:6379"));
app.connect(Redis.at("redis.prod:6379").withPassword("secret").withTtl(Duration.ofHours(24)));
```

**Ollama** — connects to an Ollama server and registers it as the AI provider:

```java
app.connect(Ollama.at("http://ollama:11434").model("llama3"));
app.connect(Ollama.at("http://localhost:11434").model("mistral"));
```

**PgVector** — connects to PostgreSQL with the pgvector extension and registers
it as the vector store (requires `cafeai-rag`):

```java
app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai"));
app.connect(PgVector.at("jdbc:postgresql://pgvector:5432/cafeai").credentials("user", "pass"));
```

**McpEndpoint** — connects to an MCP server and registers its tools (requires
`cafeai-tools`):

```java
app.connect(McpEndpoint.at("http://github-mcp:3000"));
app.connect(McpEndpoint.at("http://filesystem-mcp:3001"));
```

### 17.4 Fallback policies — operational intelligence at the connection level

Every connection carries a degradation policy. The default is `warnAndContinue` —
log a warning if the service is unreachable at startup, continue without it.

Override it with `.onUnavailable()`:

```java
// Abort startup — pgvector is non-negotiable for this application
app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai")
    .onUnavailable(Fallback.failFast()));

// Fall back to in-memory vector store in development
app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai")
    .onUnavailable(Fallback.use(VectorStore.inMemory())));

// Use OpenAI if local Ollama isn't running
app.connect(Ollama.at("http://localhost:11434").model("llama3")
    .onUnavailable(Fallback.use(OpenAI.gpt4oMini())));

// Try another Redis instance if primary is down
app.connect(Redis.at("redis-primary:6379")
    .onUnavailable(Fallback.connectInstead(Redis.at("redis-replica:6379"))));

// Completely optional service — silently skip if absent
app.connect(McpEndpoint.at("http://experimental-mcp:3000")
    .onUnavailable(Fallback.ignore()));
```

This encodes operational policy — what the application does when a dependency
isn't available — at exactly the right level. Not in infrastructure scripts,
not in application logic, but at the connection registration.

### 17.5 Environment-driven configuration

`Connect.fromEnv()` reads standard environment variables and returns a list
of configured connections. Pass each to `app.connect()`:

```java
var app = CafeAI.create();
Connect.fromEnv().forEach(app::connect);
app.listen(8080);
```

Variables recognised:

| Variable | Effect |
|---|---|
| `CAFEAI_AI_PROVIDER=ollama` | Creates `Ollama.at(OLLAMA_BASE_URL).model(CAFEAI_AI_MODEL)` |
| `OLLAMA_BASE_URL` | Ollama base URL (default: `http://localhost:11434`) |
| `CAFEAI_AI_MODEL` | Model ID (default: `llama3`) |
| `CAFEAI_MEMORY=redis` | Creates `Redis.at(REDIS_HOST:REDIS_PORT)` |
| `REDIS_URL` | Full Redis URL: `redis://host:port` |
| `REDIS_HOST` / `REDIS_PORT` | Redis host and port separately |
| `CAFEAI_VECTOR_DB=pgvector` | Creates `PgVector.at(DATABASE_URL)` |
| `DATABASE_URL` | PostgreSQL JDBC URL |
| `CAFEAI_MCP_SERVERS` | Comma-separated MCP server URLs |

Missing variables are silently skipped. The returned list is empty if nothing
is configured. This is not Spring-style autoconfiguration — nothing happens
unless you call `Connect.fromEnv()` explicitly and pass the result to `app.connect()`.

A complete docker-compose application:

```yaml
services:
  app:
    build: .
    environment:
      CAFEAI_AI_PROVIDER: ollama
      CAFEAI_AI_MODEL: llama3
      OLLAMA_BASE_URL: http://ollama:11434
      CAFEAI_MEMORY: redis
      REDIS_HOST: redis
      REDIS_PORT: 6379
      CAFEAI_VECTOR_DB: pgvector
      DATABASE_URL: jdbc:postgresql://pgvector:5432/cafeai
      CAFEAI_MCP_SERVERS: http://github-mcp:3000,http://filesystem-mcp:3001
    depends_on: [redis, pgvector, ollama]

  redis:
    image: redis:7-alpine

  pgvector:
    image: pgvector/pgvector:pg16

  ollama:
    image: ollama/ollama
```

Application code:

```java
public static void main(String[] args) {
    var app = CafeAI.create();
    Connect.fromEnv().forEach(app::connect);  // reads the environment

    app.get("/health", Connect.healthCheck(app));
    app.post("/chat", (req, res, next) ->
        res.json(Map.of("response",
            app.prompt(req.body("message")).call().text())));

    app.listen(8080);
}
```

The same code runs locally (no environment variables set → no connections → falls
back to whatever defaults are configured), in CI (partial environment), and in
production (full environment). Zero code changes between environments.

### 17.6 Health checks

`Connect.healthCheck(app)` returns a middleware that probes all registered
connections and reports their status:

```java
app.get("/health", Connect.healthCheck(app));
```

Response when all services are reachable:
```json
{
  "status": "healthy",
  "connections": {
    "Redis(redis:6379)":              { "state": "REACHABLE", "latencyMs": 2 },
    "Ollama(http://ollama:11434)":    { "state": "REACHABLE", "latencyMs": 45 },
    "PgVector(jdbc:postgresql://...)":{ "state": "REACHABLE", "latencyMs": 8 }
  }
}
```

Response when a service is down (HTTP 503):
```json
{
  "status": "degraded",
  "connections": {
    "Redis(redis:6379)":              { "state": "REACHABLE",   "latencyMs": 2 },
    "PgVector(jdbc:postgresql://...")":{ "state": "UNREACHABLE", "detail": "Connection refused" }
  }
}
```

The three health states:
- `REACHABLE` — service responded within the probe timeout
- `UNREACHABLE` — could not connect; try a fallback
- `DEGRADED` — connected but the service reported a problem (e.g. Ollama running
  but the requested model isn't pulled)

For Kubernetes liveness/readiness probes:
```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 15
readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

### 17.7 Implementing a custom Connection

The `Connection` interface is open. Any service that can be probed over a
network and that can register a capability with CafeAI can be a `Connection`:

```java
public class Qdrant implements Connection {

    private final String url;

    public static Qdrant at(String url) { return new Qdrant(url); }

    @Override public String name()      { return "Qdrant(" + url + ")"; }
    @Override public ServiceType type() { return ServiceType.VECTOR_DB; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try {
            // Qdrant exposes GET /healthz
            var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url + "/healthz")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200)
                return HealthStatus.reachable(name(), System.currentTimeMillis() - start);
            return HealthStatus.degraded(name(), "HTTP " + response.statusCode());
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    @Override
    public void register(CafeAI app) {
        // Wire in a QdrantVectorStore adapter from cafeai-rag or your own
        app.vectordb(new QdrantVectorStoreAdapter(url));
    }
}

// Usage — identical to any built-in connector
app.connect(Qdrant.at("http://qdrant:6333")
    .onUnavailable(Fallback.use(VectorStore.inMemory())));
```

The same `probe`/`register`/`fallback` model applies regardless of protocol.
HTTP, TCP, JDBC, gRPC — any transport works. The `Connection` abstraction
is deliberately protocol-agnostic.

### 17.8 Why this boundary matters

The in-process vs out-of-process distinction is not just organisational — it
reflects a real difference in how capabilities behave at runtime.

In-process modules are binary: present or absent. If `cafeai-memory` is on
the classpath, Redis support works. If it isn't, you get a clear error.
There is no middle ground.

Out-of-process connections are probabilistic: they might be there, they might
not, they might be partially working. The `Connection` model acknowledges this
and gives you a way to express what the application should do in each case.

This boundary also keeps `cafeai-core` small. The decision of what to include
as an in-process module is a deliberate one: it should be code that genuinely
needs to run in the same JVM as CafeAI, that benefits from shared memory and
lifecycle, and that has no reasonable out-of-process equivalent. Chunking
documents? In-process. A Redis server? Out-of-process.

As CafeAI's ecosystem grows, new capabilities should be evaluated against this
boundary. Services we cannot predict today — new vector databases, new LLM
providers, new protocol servers — will fit naturally as `Connection`
implementations without any changes to the core framework.

---

## 18. Chains — Composable AI Processing Pipelines

### 18.1 What a chain is

A chain is a named sequence of steps that processes a request through a defined
pipeline. Chains are middleware — they implement `Middleware` and can be used
anywhere middleware is accepted: as route handlers, as filters, and as steps
inside other chains.

The mental model: if a middleware is a single transformation, a chain is a named,
reusable pipeline of transformations. You register the pipeline once and invoke
it by name from any handler.

### 18.2 Registering and invoking a chain

```java
// Registration at startup
app.chain("classify-and-route",
    Steps.guard(GuardRail.pii()),          // block PII before anything runs
    Steps.prompt("classify"),              // run the "classify" template
    Steps.branch(
        req -> "billing".equals(req.attribute(Attributes.LAST_RESPONSE_TEXT)),
        Steps.chain("billing-handler"),    // true branch — forward reference, fine
        Steps.chain("general-handler")     // false branch
    ));

app.chain("billing-handler",
    Steps.guard(GuardRail.regulatory().hipaa()),
    Steps.prompt("billing-response"));

app.chain("general-handler",
    Steps.prompt("general-response"));

// Invocation from a handler
app.post("/support", (req, res, next) ->
    app.chain("classify-and-route").run(req, res, next));

// A chain is also middleware — use it directly in route arrays
app.post("/support", app.chain("classify-and-route"), myFinalHandler);
```

### 18.3 Built-in steps

**`Steps.prompt(templateName)`** — renders a named template with `req.body()` as
the variable map, calls the LLM, and stores the result:
- `req.attribute(Attributes.PROMPT_RESPONSE)` — the full `PromptResponse` object
- `req.attribute(Attributes.LAST_RESPONSE_TEXT)` — just the text, for use in predicates

**`Steps.prompt(Function<Request, String>)`** — for inline prompts that need
request data:
```java
Steps.prompt(req -> "Summarise in one sentence: " + req.bodyText())
```

**`Steps.guard(GuardRail...)`** — wraps guardrails as a step. Multiple guardrails
compose in order:
```java
Steps.guard(GuardRail.pii(), GuardRail.jailbreak(), GuardRail.toxicity())
```

**`Steps.branch(predicate, trueBranch, falseBranch)`** — conditional routing. The
chosen branch continues the chain; the other is skipped:
```java
Steps.branch(
    req -> req.header("X-Premium") != null,
    Steps.prompt("premium-response"),
    Steps.prompt("standard-response")
)
```

**`Steps.when(predicate, step)`** — one-sided branch. Executes the step only when
the predicate matches; otherwise passes through:
```java
Steps.when(
    req -> req.attribute("flagged") != null,
    Steps.guard(GuardRail.jailbreak())
)
```

**`Steps.chain(name)`** — lazy forward reference to another chain. Resolved at
execution time, so chains can reference each other and themselves:
```java
Steps.chain("billing-handler")   // billing-handler registered after this chain — fine
```

**`Steps.transform(Function<String, String>)`** — post-processes the last LLM
response text before the next step sees it:
```java
Steps.transform(text -> text.trim().toLowerCase())
```

### 18.4 Chains are immutable and composable

`Chain` is immutable — `app.chain()` creates a fixed pipeline. The `use()` method
returns a new chain with the step appended:

```java
// Extend a chain programmatically
Chain base    = app.chain("base-pipeline");
Chain premium = base.use(Steps.prompt("premium-addon"));
// base is unchanged; premium is a new chain
```

### 18.5 Accessing chain results in the final handler

Steps communicate via request attributes. After a chain runs, the handler
reads what was set:

```java
app.post("/support", (req, res, next) -> {
    app.chain("triage").run(req, res, next);

    // After the chain, read what was set by Steps.prompt()
    var response = (PromptResponse) req.attribute(Attributes.PROMPT_RESPONSE);
    var text     = (String)         req.attribute(Attributes.LAST_RESPONSE_TEXT);

    res.json(Map.of(
        "answer",  text,
        "tokens",  response.totalTokens(),
        "sources", response.ragDocuments().size()
    ));
});
```

---

## 19. Guardrails — Ethical and Safety Middleware

### 19.1 Guardrails are middleware

Every guardrail implements `Middleware`. There is no special guardrail pipeline —
they compose with everything else: filters, route arrays, chain steps.

```java
// Global filter — applies to every request
app.filter(GuardRail.pii());
app.filter(GuardRail.jailbreak());

// Route-scoped — applies only to this endpoint
app.post("/chat", GuardRail.toxicity(), myHandler);

// Chain step — applies at a specific point in a pipeline
app.chain("support",
    Steps.guard(GuardRail.pii(), GuardRail.jailbreak()),
    Steps.prompt("respond"));
```

### 19.2 Activating real implementations

Guardrails in `cafeai-core` are pass-through stubs by default. Add
`cafeai-guardrails` to activate real implementations:

```groovy
dependencies {
    implementation 'io.cafeai:cafeai-core'
    implementation 'io.cafeai:cafeai-guardrails'   // activates real enforcement
}
```

Without `cafeai-guardrails`, every guardrail logs a one-time warning and calls
`next.run()`. Your application compiles and runs — guardrails just don't enforce
anything. Adding the JAR activates enforcement with zero code changes.

### 19.3 Available guardrails

**`GuardRail.pii()`** — detects personally identifiable information in both
the user's prompt (pre-LLM) and the model's response (post-LLM). Detects emails,
phone numbers, SSNs, credit card numbers, IPv4 addresses. Default action: BLOCK.

```java
// Block on PII detection (default)
app.guard(GuardRail.pii());

// Redact PII in-place rather than blocking
app.guard(GuardRail.pii().scrubbing());  // replaces PII with [EMAIL], [PHONE], etc.
```

**`GuardRail.jailbreak()`** — detects adversarial prompts attempting to bypass
the system prompt, extract model internals, or manipulate the model. Uses
weighted pattern scoring across seventeen known attack vectors. Configurable
confidence threshold:

```java
app.guard(GuardRail.jailbreak());              // default threshold 0.7
app.guard(GuardRail.jailbreak().threshold(0.5)); // more sensitive
app.guard(GuardRail.jailbreak().threshold(0.9)); // stricter — fewer false positives
```

**`GuardRail.promptInjection()`** — detects injection attempts in both user input
and RAG-retrieved documents. The indirect injection vector (malicious instructions
hidden in a document in your vector store) is checked automatically.

**`GuardRail.toxicity()`** — detects threats, incitement, hate speech, and
harmful instruction requests in both input and output:

```java
app.guard(GuardRail.toxicity());                           // BLOCK (default)
app.guard(GuardRail.toxicity().action(Action.WARN));       // log but allow through
```

**`GuardRail.topicBoundary()`** — keeps the LLM on-topic. When `allow` topics
are set, off-topic requests are blocked. Denied topics block regardless:

```java
app.guard(GuardRail.topicBoundary()
    .allow("customer service", "orders", "returns", "shipping")
    .deny("politics", "medical advice", "competitor products"));
```

**`GuardRail.regulatory()`** — compliance guardrail for regulated industries.
Additive — enable only the regulations relevant to your deployment:

```java
app.guard(GuardRail.regulatory().gdpr());
app.guard(GuardRail.regulatory().hipaa().gdpr());
app.guard(GuardRail.regulatory().hipaa().fcra().ccpa());
```

### 19.4 Guardrail position

Each guardrail has a `Position` that determines when it runs relative to the LLM call:

| Position | When it runs | Typical use |
|---|---|---|
| `PRE_LLM` | Before the LLM is called | Input validation, jailbreak detection |
| `POST_LLM` | After the LLM responds | Output filtering, bias detection |
| `BOTH` | Both before and after | PII (scrub input, check output) |

Position is determined by the guardrail implementation — you don't set it manually.

### 19.5 Guardrail violations

When a guardrail triggers, by default it responds with HTTP 400:

```json
{
  "error":     "Request blocked by guardrail",
  "guardrail": "pii",
  "reason":    "PII detected in input: EMAIL, PHONE"
}
```

The violation is also recorded in request attributes for observability:
- `req.attribute(Attributes.GUARDRAIL_NAME)` — which guardrail triggered
- `req.attribute(Attributes.GUARDRAIL_SCORE)` — confidence score (0.0–1.0)

### 19.6 Composing guardrails

Guardrails compose naturally because they're middleware:

```java
// Via app.guard() — registered as global filters
app.guard(GuardRail.pii());
app.guard(GuardRail.jailbreak());

// Via Steps.guard() in a chain — applied at a specific pipeline stage
app.chain("secure-chat",
    Steps.guard(GuardRail.pii(), GuardRail.jailbreak(), GuardRail.toxicity()),
    Steps.prompt("respond"),
    Steps.guard(GuardRail.pii())); // check output too

// Via Middleware.then() — inline composition
Middleware safetyStack = GuardRail.pii()
    .then(GuardRail.jailbreak())
    .then(GuardRail.toxicity());

app.post("/chat", safetyStack, myHandler);
```
