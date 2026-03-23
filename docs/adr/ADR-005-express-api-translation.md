# ADR-005: Express 4.x API Translation Map

**Status:** Accepted  
**Date:** March 2026  
**Source:** https://expressjs.com/en/4x/api.html

---

## Purpose

This document is the formal translation contract between Express 4.x and CafeAI.
It is the authoritative reference for every API decision in `cafeai-core`.

For every Express API member, one of four verdicts applies:

| Verdict | Meaning |
|---|---|
| ✅ **Adopted** | Ported pound-for-pound. Identical name, identical semantics. |
| 🔄 **Translated** | Concept adopted, but adapted for Java paradigms with explicit rationale. |
| ➕ **Extended** | Express equivalent exists, but CafeAI adds AI-native behaviour on top. |
| ❌ **Omitted** | Deliberately excluded. Rationale provided. |

---

## 1. `express()` — Top-Level Factory

### Express
```javascript
const app = express()
```

### CafeAI
```java
var app = CafeAI.create()
```

**Verdict:** 🔄 **Translated**

**Rationale:** `express()` works because JavaScript modules export functions as
first-class values. Java has no equivalent of a callable module export. `CafeAI.create()`
is the idiomatic Java static factory equivalent — same intent, same result, correct paradigm.

---

## 2. Built-in Middleware (`express.*`)

### 2.1 `express.json()`

**Verdict:** ✅ **Adopted**

```java
CafeAI.json()          // global registration
app.use(CafeAI.json()) // explicit registration
```

Parses `application/json` request bodies. Populates `req.body()`.
Helidon SE's `JsonpSupport` / `JsonbSupport` powers this internally.

Options supported:
| Option | CafeAI equivalent |
|---|---|
| `inflate` | `JsonOptions.inflate(boolean)` |
| `limit` | `JsonOptions.limit(long bytes)` |
| `strict` | `JsonOptions.strict(boolean)` |
| `type` | `JsonOptions.type(String mediaType)` |
| `verify` | `JsonOptions.verify(BodyVerifier)` |
| `reviver` | ❌ Omitted — JavaScript-specific JSON.parse hook, no Java equivalent |

---

### 2.2 `express.raw()`

**Verdict:** ✅ **Adopted**

```java
app.use(CafeAI.raw())
app.use(CafeAI.raw(RawOptions.limit(1024 * 1024)))
```

Parses request body as raw `byte[]`. Direct equivalent of Express's `raw()`.
Java `byte[]` maps cleanly to Express's `Buffer`.

---

### 2.3 `express.Router()`

**Verdict:** ✅ **Adopted**

```java
var router = CafeAI.Router()         // matches express.Router() exactly
var router = CafeAI.Router(options)  // with options
```

Options:
| Express Option | CafeAI | Notes |
|---|---|---|
| `caseSensitive` | `RouterOptions.caseSensitive(boolean)` | ✅ Direct port |
| `mergeParams` | `RouterOptions.mergeParams(boolean)` | ✅ Direct port |
| `strict` | `RouterOptions.strict(boolean)` | ✅ Direct port |

---

### 2.4 `express.static()`

**Verdict:** 🔄 **Translated**

```java
app.use(CafeAI.static("public"))
app.use(CafeAI.static("public", StaticOptions.builder()
    .maxAge(Duration.ofDays(1))
    .etag(true)
    .build()))
```

Express delegates to `serve-static`. CafeAI delegates to Helidon SE's built-in
static file serving. All options port directly with one adaptation:

| Express Option | CafeAI | Notes |
|---|---|---|
| `maxAge` (ms number or string) | `StaticOptions.maxAge(Duration)` | 🔄 `Duration` is the Java-idiomatic type — no ambiguous ms/string duality |
| `dotfiles` | `StaticOptions.dotfiles(Dotfiles)` | ✅ enum: `ALLOW`, `DENY`, `IGNORE` |
| `etag` | `StaticOptions.etag(boolean)` | ✅ |
| `index` | `StaticOptions.index(String)` | ✅ |
| `redirect` | `StaticOptions.redirect(boolean)` | ✅ |
| `setHeaders` | `StaticOptions.setHeaders(HeaderSetter)` | 🔄 `@FunctionalInterface HeaderSetter` replaces the JS function |
| `extensions` | `StaticOptions.extensions(List<String>)` | 🔄 `List<String>` replaces JS array |
| `fallthrough` | `StaticOptions.fallthrough(boolean)` | ✅ |
| `immutable` | `StaticOptions.immutable(boolean)` | ✅ |
| `cacheControl` | `StaticOptions.cacheControl(boolean)` | ✅ |
| `acceptRanges` | `StaticOptions.acceptRanges(boolean)` | ✅ |
| `lastModified` | `StaticOptions.lastModified(boolean)` | ✅ |

**Note:** Express recommends a reverse proxy for static assets in production.
CafeAI carries this recommendation forward in its documentation.

---

### 2.5 `express.text()`

**Verdict:** ✅ **Adopted**

```java
app.use(CafeAI.text())
app.use(CafeAI.text(TextOptions.charset(StandardCharsets.UTF_8)))
```

Parses body as `String`. Options map directly. `defaultCharset` becomes
`TextOptions.charset(Charset)` — typed `Charset` instead of a loose string.

---

### 2.6 `express.urlencoded()`

**Verdict:** ✅ **Adopted**

```java
app.use(CafeAI.urlencoded())
app.use(CafeAI.urlencoded(UrlEncodedOptions.extended(true)))
```

Parses `application/x-www-form-urlencoded` bodies. The `extended` option
(qs vs querystring) maps to `UrlEncodedOptions.extended(boolean)`. The `depth`
option (added in Express 4.20.0) maps to `UrlEncodedOptions.depth(int)`.

---

## 3. Application (`app`)

### 3.1 Properties

#### `app.locals`

**Verdict:** 🔄 **Translated — via Java 21 `ScopedValue`**

```javascript
// Express
app.locals.title = 'My App'
app.locals.helper = someFunction
```

```java
// CafeAI
app.local("title", "My App")
app.local("helper", someHelper)
Object title = app.local("title")
```

**Rationale:** `app.locals` in Express is a plain mutable JS object — a property
bag that persists for the life of the application and is visible to all middleware
and templates. In Java, a naked mutable `Map<String, Object>` is the mechanical
equivalent but misses the spirit: these are *scoped, application-lifetime values*.

Java 21's `ScopedValue` is the correct semantic equivalent for the request-scoped
case (see `res.locals`). For application-lifetime values, a typed, thread-safe
`ConcurrentHashMap`-backed store behind a clean `app.local(key, value)` API is
the right Java idiom. Type safety is added via a generic accessor:

```java
app.local("config", appConfig)
AppConfig cfg = app.local("config", AppConfig.class) // typed retrieval
```

**AI Extension:** In CafeAI, `app.locals` is the natural home for AI infrastructure
references — the registered `AiProvider`, the `MemoryStrategy`, the `VectorStore`.
These are application-lifetime, accessible from any middleware, exactly the pattern
`app.locals` was designed for.

---

#### `app.mountpath`

**Verdict:** ✅ **Adopted**

```java
String mountPath = app.mountpath()         // single path
List<String> paths = app.mountpaths()      // multiple mount paths
```

Returns the path pattern(s) on which a sub-app was mounted.
`mountpaths()` (plural) handles the multi-pattern case cleanly without
overloading a single method to return either `String` or `List<String>`.

---

#### `app.router` *(Express 4.x deprecated)*

**Verdict:** ❌ **Omitted**

`app.router` was deprecated in Express 4.x and removed in 5.x. It exposed the
internal router directly, which created coupling. CafeAI has no equivalent.
Sub-routing is handled via `app.use(path, router)`.

---

### 3.2 Events

#### `app.on('mount', callback)`

**Verdict:** 🔄 **Translated**

```javascript
// Express
admin.on('mount', function(parent) { ... })
```

```java
// CafeAI
admin.onMount(parent -> { ... })
```

**Rationale:** Express uses Node's `EventEmitter` pattern. Java has no built-in
equivalent of `EventEmitter`. `onMount(Consumer<CafeAI>)` is the idiomatic Java
callback equivalent — type-safe, no string-based event names, no magic.

---

### 3.3 Methods

#### `app.all(path, handler)`

**Verdict:** ✅ **Adopted**

```java
app.all("/secret", (req, res, next) -> {
    log.info("Accessing secret section...");
    next.run();
});
```

Matches all HTTP methods for a path. Direct port.

---

#### `app.delete(path, handler)`

**Verdict:** ✅ **Adopted**

```java
app.delete("/user/:id", (req, res) -> {
    res.send("DELETE user " + req.params("id"));
});
```

**Note:** `delete` is a reserved keyword in Java. This would ordinarily require
renaming — however, Java allows reserved words as method names when called on an
object reference (it is only reserved as a statement keyword). This is legal:
`app.delete(...)`. IntelliJ and all major IDEs handle this correctly. We keep
the name for pound-for-pound parity.

---

#### `app.disable(name)` / `app.disabled(name)`

**Verdict:** 🔄 **Translated**

```javascript
// Express
app.disable('x-powered-by')
app.disabled('x-powered-by') // → true
```

```java
// CafeAI
app.disable(Setting.X_POWERED_BY)
app.disabled(Setting.X_POWERED_BY) // → true
```

**Rationale:** Express uses string keys for settings — `app.disable('trust proxy')`,
`app.disable('x-powered-by')`. Loose strings are error-prone in Java and offer no
IDE support. CafeAI uses a `Setting` enum for all boolean settings. Fully type-safe,
autocomplete-friendly, zero typo risk.

---

#### `app.enable(name)` / `app.enabled(name)`

**Verdict:** 🔄 **Translated** — same rationale as `app.disable()`.

```java
app.enable(Setting.TRUST_PROXY)
app.enabled(Setting.TRUST_PROXY) // → true
```

---

#### `app.engine(ext, callback)`

**Verdict:** 🔄 **Translated — repurposed for AI response formatters**

```javascript
// Express — registers a template engine
app.engine('html', require('ejs').renderFile)
```

```java
// CafeAI — registers a response formatter / renderer
app.engine("html", HtmlFormatter.ejs())       // traditional template
app.engine("markdown", MarkdownFormatter.of()) // AI responses as Markdown
app.engine("schema", JsonSchemaFormatter.of()) // structured AI output
```

**Rationale:** Express's `app.engine()` registers template engines for `res.render()`.
CafeAI preserves this but extends it naturally: AI responses are not always HTML.
An AI response might be Markdown, a JSON Schema-validated object, or a structured
domain type. `app.engine()` becomes the registration point for *all* response
formatters — traditional templates and AI-native output formats alike.

---

#### `app.get(name)` *(settings getter)*

**Verdict:** 🔄 **Translated**

```javascript
// Express — gets a setting value
app.get('trust proxy')
```

```java
// CafeAI — typed setting retrieval
app.setting(Setting.TRUST_PROXY)
```

**Rationale:** Express overloads `app.get()` for two purposes: HTTP GET route
registration AND settings retrieval. This is a known Express quirk — distinguished
only by argument count and type. Java does not permit this kind of duck-typed
overloading cleanly. CafeAI separates the two concerns:

- `app.get(path, handler)` → HTTP GET route (as expected)
- `app.setting(Setting)` → settings retrieval (unambiguous)

This is one of the cleaner deviations from Express — the separation is actually
*better* API design.

---

#### `app.get(path, handler)` *(HTTP GET)*

**Verdict:** ✅ **Adopted**

```java
app.get("/user/:id", (req, res) -> {
    res.json(userService.find(req.params("id")));
});
```

---

#### `app.listen(port)` / `app.listen(port, callback)`

**Verdict:** ✅ **Adopted**

```java
app.listen(8080)
app.listen(8080, () -> System.out.println("☕ CafeAI brewing on :8080"))
```

Helidon SE's `WebServer.start()` powers this internally. Virtual threads mean
the blocking-style API is cost-free.

---

#### `app.METHOD(path, handler)`

**Verdict:** ✅ **Adopted**

All standard HTTP methods are supported:
`app.get()`, `app.post()`, `app.put()`, `app.patch()`, `app.delete()`,
`app.head()`, `app.options()`, `app.trace()`

---

#### `app.param(name, callback)`

**Verdict:** ✅ **Adopted**

```java
app.param("userId", (req, res, next, value) -> {
    req.setAttribute("user", userService.find(value));
    next.run();
});
```

Route parameter pre-processing middleware. Direct port. The callback receives
`(req, res, next, paramValue)` — identical argument shape to Express.

---

#### `app.path()`

**Verdict:** ✅ **Adopted**

```java
String absolutePath = app.path() // → "/admin" for a sub-app mounted at /admin
```

---

#### `app.post()`, `app.put()`

**Verdict:** ✅ **Adopted** — see `app.METHOD`.

---

#### `app.render(view, [locals], callback)`

**Verdict:** 🔄 **Translated**

```javascript
// Express
app.render('email', { name: 'Tobi' }, function(err, html) { ... })
```

```java
// CafeAI
app.render("email", Map.of("name", "Tobi"), (err, html) -> { ... })
// or with CompletableFuture
app.render("email", Map.of("name", "Tobi"))
   .thenAccept(html -> sendEmail(html))
```

**Rationale:** The callback pattern in Express is Node's standard async pattern.
Java has `CompletableFuture` as the idiomatic async equivalent. Both forms are
supported: callback style for Express familiarity, `CompletableFuture` for Java idiom.

---

#### `app.route(path)`

**Verdict:** ✅ **Adopted**

```java
app.route("/book")
   .get((req, res) -> res.send("Get a book"))
   .post((req, res) -> res.send("Add a book"))
   .put((req, res) -> res.send("Update a book"));
```

Fluent chainable route builder for a single path. Direct port. Avoids duplicate
path string repetition — same motivation as in Express.

---

#### `app.set(name, value)`

**Verdict:** 🔄 **Translated**

```java
app.set(Setting.TRUST_PROXY, true)
app.set(Setting.ENV, "production")
app.set(Setting.JSON_SPACES, 2)
```

Same `Setting` enum as `app.disable()` / `app.enable()`. Typed, safe, autocomplete-friendly.

---

#### `app.use([path], middleware)`

**Verdict:** ✅ **Adopted**

```java
app.use(Middleware.cors())              // global
app.use("/api", Middleware.auth())      // path-scoped
app.use("/api/v1", apiRouter)           // sub-router
```

The most important method in CafeAI. Everything flows through `use()`.

---

## 4. Request (`req`)

### 4.1 Properties

| Express | CafeAI | Verdict | Notes |
|---|---|---|---|
| `req.app` | `req.app()` | ✅ Adopted | Method not field — Java convention |
| `req.baseUrl` | `req.baseUrl()` | ✅ Adopted | |
| `req.body` | `req.body()` / `req.body(Class<T>)` | 🔄 Translated | Typed retrieval — `req.body(MyDto.class)` is idiomatic Java |
| `req.cookies` | `req.cookies()` / `req.cookie(String)` | ✅ Adopted | |
| `req.fresh` | `req.fresh()` | ✅ Adopted | Cache freshness check |
| `req.hostname` | `req.hostname()` | ✅ Adopted | |
| `req.ip` | `req.ip()` | ✅ Adopted | |
| `req.ips` | `req.ips()` | 🔄 Translated | Returns `List<String>` not JS array |
| `req.method` | `req.method()` | ✅ Adopted | Returns `HttpMethod` enum, not raw string |
| `req.originalUrl` | `req.originalUrl()` | ✅ Adopted | |
| `req.params` | `req.params(String)` | 🔄 Translated | `req.params("id")` — keyed access, no naked object |
| `req.path` | `req.path()` | ✅ Adopted | |
| `req.protocol` | `req.protocol()` | ✅ Adopted | Returns `"http"` or `"https"` |
| `req.query` | `req.query(String)` / `req.queryMap()` | 🔄 Translated | Keyed access + full map |
| `req.res` | `req.response()` | 🔄 Translated | `res` is too terse as a Java method name |
| `req.route` | `req.route()` | ✅ Adopted | |
| `req.secure` | `req.secure()` | ✅ Adopted | Shorthand for `req.protocol().equals("https")` |
| `req.signedCookies` | `req.signedCookies()` / `req.signedCookie(String)` | ✅ Adopted | |
| `req.stale` | `req.stale()` | ✅ Adopted | Opposite of `req.fresh()` |
| `req.subdomains` | `req.subdomains()` | 🔄 Translated | Returns `List<String>` |
| `req.xhr` | `req.xhr()` | ✅ Adopted | Detects `X-Requested-With: XMLHttpRequest` |

**AI Extension — `req.stream()`:**

CafeAI adds one property with no Express equivalent:

```java
req.stream()  // → boolean — true if client expects SSE / streaming response
```

Detected via `Accept: text/event-stream` header. The AI-native companion to `req.xhr()`.

---

### 4.2 Methods

| Express | CafeAI | Verdict | Notes |
|---|---|---|---|
| `req.accepts(types)` | `req.accepts(String...)` | ✅ Adopted | |
| `req.acceptsCharsets(charset)` | `req.acceptsCharsets(String...)` | ✅ Adopted | |
| `req.acceptsEncodings(encoding)` | `req.acceptsEncodings(String...)` | ✅ Adopted | |
| `req.acceptsLanguages(lang)` | `req.acceptsLanguages(String...)` | ✅ Adopted | |
| `req.get(headerName)` | `req.header(String)` | 🔄 Translated | `req.get()` conflicts with getter convention in Java beans. `req.header("Authorization")` is clearer and matches HTTP semantics |
| `req.is(type)` | `req.is(String)` | ✅ Adopted | Content-type check |
| `req.param(name)` | ❌ Omitted | Express deprecated this in 4.x — use `req.params()`, `req.body()`, `req.query()` explicitly. CafeAI does not resurrect deprecated APIs. |
| `req.range(size)` | `req.range(long size)` | ✅ Adopted | Returns `List<Range>` |

**AI Extension — `req.attribute()`:**

```java
// Set by upstream middleware (e.g. app.param(), auth middleware)
User user = req.attribute("user", User.class)

// Set in middleware, read in handler
req.setAttribute("retrievedDocs", docs)
List<Document> docs = req.attribute("retrievedDocs", List.class)
```

This is the Java-idiomatic replacement for ad-hoc property assignment on the
request object (`req.user = ...`) which works in JavaScript but not in Java.
In CafeAI, `req.attribute()` is the typed, thread-safe carrier for middleware-to-handler
data — RAG documents, auth principals, guardrail scores, token counts.

---

## 5. Response (`res`)

### 5.1 Properties

| Express | CafeAI | Verdict | Notes |
|---|---|---|---|
| `res.app` | `res.app()` | ✅ Adopted | Method not field |
| `res.headersSent` | `res.headersSent()` | ✅ Adopted | |
| `res.locals` | `res.locals()` / `res.local(String, Object)` | 🔄 Translated — via `ScopedValue` | See rationale below |

**`res.locals` rationale:**

In Express, `res.locals` is an object scoped to the *current request/response cycle*.
It is the standard way to pass data from middleware to route handlers.

In CafeAI, this maps to Java 21's **`ScopedValue`** — a request-scoped, immutable,
inheritable value carrier that is explicitly designed for exactly this use case.
The difference from `ThreadLocal` is important: `ScopedValue` has a defined scope
lifetime (the request), is safe with virtual threads, and is inherited by child
threads in a `StructuredTaskScope` — critical for CafeAI's multi-agent pipelines.

```java
// Middleware sets a local
res.local("user", authenticatedUser)
res.local("ragDocs", retrievedDocuments)
res.local("guardrailScore", score)

// Handler reads it
User user = res.local("user", User.class)
```

---

### 5.2 Methods

| Express | CafeAI | Verdict | Notes |
|---|---|---|---|
| `res.append(field, value)` | `res.append(String, String)` | ✅ Adopted | |
| `res.attachment([filename])` | `res.attachment(String)` | ✅ Adopted | Sets `Content-Disposition: attachment` |
| `res.cookie(name, value, [options])` | `res.cookie(String, String, CookieOptions)` | ✅ Adopted | |
| `res.clearCookie(name, [options])` | `res.clearCookie(String)` | ✅ Adopted | |
| `res.download(path, [filename], [options])` | `res.download(Path, String, DownloadOptions)` | 🔄 Translated | `java.nio.file.Path` replaces string path |
| `res.end([data])` | `res.end()` | ✅ Adopted | Terminates response with no body |
| `res.format(object)` | `res.format(ContentMap)` | 🔄 Translated | See rationale below |
| `res.get(field)` | `res.header(String)` | 🔄 Translated | Same rationale as `req.get()` → `req.header()` |
| `res.json([body])` | `res.json(Object)` | ✅ Adopted | Serializes via Jackson |
| `res.jsonp([body])` | ❌ Omitted | JSONP is a legacy CORS workaround, obsolete since CORS headers. Not carried forward. |
| `res.links(links)` | `res.links(Map<String, String>)` | 🔄 Translated | `Map` replaces JS object literal |
| `res.location(path)` | `res.location(String)` | ✅ Adopted | Sets `Location` header |
| `res.redirect([status], url)` | `res.redirect(String)` / `res.redirect(int, String)` | ✅ Adopted | |
| `res.render(view, [locals])` | `res.render(String, Map<String, Object>)` | ✅ Adopted | |
| `res.send([body])` | `res.send(String)` / `res.send(byte[])` | ✅ Adopted | |
| `res.sendFile(path, [options])` | `res.sendFile(Path, SendFileOptions)` | 🔄 Translated | `java.nio.file.Path` replaces string |
| `res.sendStatus(statusCode)` | `res.sendStatus(int)` | ✅ Adopted | |
| `res.set(field, value)` | `res.set(String, String)` / `res.set(Map<String,String>)` | ✅ Adopted | |
| `res.status(code)` | `res.status(int)` | ✅ Adopted | Fluent — returns `res` for chaining |
| `res.type(type)` | `res.type(String)` | ✅ Adopted | Sets `Content-Type` |
| `res.vary(field)` | `res.vary(String)` | ✅ Adopted | Adds field to `Vary` header |

**`res.format()` rationale:**

```javascript
// Express
res.format({
  'text/plain': () => res.send('hey'),
  'text/html': () => res.send('<p>hey</p>'),
  'application/json': () => res.json({ message: 'hey' })
})
```

```java
// CafeAI
res.format(ContentMap.of()
    .text(() -> res.send("hey"))
    .html(() -> res.send("<p>hey</p>"))
    .json(() -> res.json(Map.of("message", "hey")))
    .build())
```

JS object literals as dispatch maps have no direct Java equivalent. `ContentMap`
is a fluent builder that constructs the same dispatch table in an idiomatic Java style.

**AI Extension — `res.stream()`:**

```java
// CafeAI only — no Express equivalent
res.stream(app.prompt(message))         // stream LLM tokens as SSE
res.stream(publisher)                   // stream any reactive publisher as SSE
res.streamJson(publisher)               // stream JSON objects (newline-delimited)
```

`res.stream()` is CafeAI's most important AI-native response method. It accepts
a token stream from an LLM call and emits it as Server-Sent Events with correct
backpressure via Helidon SE's reactive pipeline. No Express equivalent exists
because Express has no native SSE streaming concept.

---

## 6. Router

### 6.1 Methods

#### `router.all(path, handler)`

**Verdict:** ✅ **Adopted**

```java
router.all("/events", (req, res, next) -> {
    // runs for GET, POST, PUT, DELETE, etc.
    next.run();
});
```

---

#### `router.METHOD(path, handler)`

**Verdict:** ✅ **Adopted**

All HTTP methods: `router.get()`, `router.post()`, `router.put()`,
`router.patch()`, `router.delete()`, `router.head()`, `router.options()`

---

#### `router.param(name, callback)`

**Verdict:** ✅ **Adopted**

```java
router.param("id", (req, res, next, id) -> {
    req.setAttribute("item", itemService.find(id));
    next.run();
});
```

---

#### `router.route(path)`

**Verdict:** ✅ **Adopted**

```java
router.route("/users/:id")
      .get((req, res) -> res.json(req.attribute("user", User.class)))
      .put((req, res) -> userService.update(req.body(UserDto.class)))
      .delete((req, res) -> userService.delete(req.params("id")));
```

---

#### `router.use([path], middleware)`

**Verdict:** ✅ **Adopted**

```java
router.use(Middleware.auth())
router.use("/admin", Middleware.requireRole("ADMIN"))
router.use("/api", apiSubRouter)
```

---

## 7. Deviations Summary

The following table consolidates all points where CafeAI deviates from Express,
for quick reference:

| Express | CafeAI | Category | Reason |
|---|---|---|---|
| `express()` | `CafeAI.create()` | Language | No callable module exports in Java |
| `app.locals.x = v` | `app.local(key, value)` | Language | No dynamic property assignment in Java |
| `res.locals.x = v` | `res.local(key, value)` + `ScopedValue` | Language + Java 21 | Thread-safe, virtual-thread-compatible scoped state |
| `app.get(setting)` | `app.setting(Setting)` | API clarity | `app.get` overload conflict with HTTP GET |
| `app.set(name, v)` | `app.set(Setting, value)` | Type safety | Enum over loose strings |
| `app.disable(name)` | `app.disable(Setting)` | Type safety | Enum over loose strings |
| `app.on('mount', fn)` | `app.onMount(Consumer<CafeAI>)` | Language | No EventEmitter in Java |
| `req.get(header)` | `req.header(String)` | API clarity | Avoids getter naming convention conflict |
| `res.get(header)` | `res.header(String)` | API clarity | Same as above |
| `res.download(path)` | `res.download(Path, ...)` | Type safety | `java.nio.file.Path` over string |
| `res.sendFile(path)` | `res.sendFile(Path, ...)` | Type safety | `java.nio.file.Path` over string |
| `res.format({...})` | `res.format(ContentMap)` | Language | No JS object literals in Java |
| `res.links({...})` | `res.links(Map<String,String>)` | Language | `Map` over JS object literal |
| `req.params` (object) | `req.params(String)` | Language | No dynamic property access in Java |
| `req.ips` (array) | `req.ips()` → `List<String>` | Language | `List` over JS array |
| `req.method` (string) | `req.method()` → `HttpMethod` | Type safety | Enum over raw string |
| `req.res` | `req.response()` | API clarity | `res` too terse as Java method name |
| `app.render` callback | `CompletableFuture` variant | Language | Java async idiom |

## 8. Intentional Omissions

| Express API | Reason |
|---|---|
| `res.jsonp()` | JSONP is a legacy CORS workaround. Obsolete. Not carried forward. |
| `req.param()` | Deprecated in Express 4.x. CafeAI does not resurrect deprecated APIs. |
| `app.router` property | Deprecated in Express 4.x. |
| `JSON reviver` option | JavaScript-specific `JSON.parse` hook. No Java equivalent. |

---

## 9. CafeAI-Only Additions (No Express Equivalent)

These are new API members introduced by CafeAI with no Express counterpart.
They are AI-native extensions that follow the same design philosophy.

| Method | Purpose |
|---|---|
| `app.ai(provider)` | Register LLM provider |
| `app.system(prompt)` | Set system prompt / AI persona |
| `app.template(name, tmpl)` | Named prompt templates |
| `app.memory(strategy)` | Tiered context memory |
| `app.vectordb(store)` | Register vector store |
| `app.embed(model)` | Register embedding model |
| `app.ingest(source)` | Ingest knowledge sources |
| `app.rag(retriever)` | Attach retrieval pipeline |
| `app.tool(tool)` | Register Java LLM tool |
| `app.mcp(server)` | Register MCP server connection |
| `app.chain(name, steps)` | Named composable pipeline |
| `app.guard(guardRail)` | Attach guardrail middleware |
| `app.agent(name, def)` | Register agent definition |
| `app.orchestrate(name, agents)` | Multi-agent topology |
| `app.observe(strategy)` | Attach observability |
| `app.eval(harness)` | Attach eval harness |
| `req.stream()` | Detect SSE streaming client |
| `req.attribute(key, type)` | Typed request attribute carrier |
| `res.stream(publisher)` | Stream LLM tokens as SSE |
| `res.streamJson(publisher)` | Stream JSON objects (NDJSON) |

---

*ADR-005 — CafeAI v0.1.0-SNAPSHOT — Express 4.x API Translation Map*
