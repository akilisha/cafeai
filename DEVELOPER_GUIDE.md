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
