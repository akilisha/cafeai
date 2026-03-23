# ROADMAP-01: `CafeAI` — Top-Level Factory & Built-in Middleware

**Maps to:** Express `express()` + `express.json()` / `express.raw()` / `express.Router()` / `express.static()` / `express.text()` / `express.urlencoded()`  
**Module:** `cafeai-core`  
**ADR Reference:** ADR-001, ADR-002, ADR-005 §1–2  
**Status:** 🔴 Not Started

---

## Objective

Implement the CafeAI top-level factory (`CafeAI.create()`) and all six built-in
middleware equivalents. This is the entry point of every CafeAI application.
Nothing else works without this phase being complete.

---

## Phases

---

### Phase 1 — `CafeAI.create()` Factory

**Goal:** A working `CafeAI` instance that can be created and holds application state.

#### Input
- `CafeAI` interface (already defined in `cafeai-core`)
- `CafeAIApp` implementation class (stub)
- Helidon SE `WebServer` as the underlying runtime

#### Tasks
- [ ] Implement `CafeAIApp` as the concrete implementation of `CafeAI`
- [ ] Wire `WebServer.builder()` internally — not exposed to the user
- [ ] Implement `app.listen(int port)`
- [ ] Implement `app.listen(int port, Runnable onStart)`
- [ ] Application lifecycle: start, stop, graceful shutdown hook
- [ ] Verify virtual thread executor is configured on the Helidon server

#### Output
```java
var app = CafeAI.create();
app.listen(8080, () -> System.out.println("Running"));
// Server starts, accepts connections, gracefully shuts down on SIGTERM
```

#### Acceptance Criteria
- [ ] `CafeAI.create()` returns a non-null `CafeAI` instance
- [ ] `app.listen(8080)` starts Helidon SE on port 8080
- [ ] `app.listen(8080, callback)` invokes callback after server is ready
- [ ] JVM shutdown hook triggers graceful Helidon stop
- [ ] Virtual threads confirmed active (log output on startup)
- [ ] Unit test: factory returns correct implementation type
- [ ] Integration test: server starts and responds on configured port

---

### Phase 2 — `CafeAI.json()`

**Goal:** JSON body parsing middleware populating `req.body()`.

#### Input
- Helidon SE `JsonpSupport` / `JsonbSupport`
- `JsonOptions` builder class
- `Request` interface with `body()` method

#### Tasks
- [ ] Implement `CafeAI.json()` returning a `Middleware` instance
- [ ] Implement `CafeAI.json(JsonOptions)` overload
- [ ] Implement `JsonOptions` builder: `inflate`, `limit`, `strict`, `type`, `verify`
- [ ] Parse `application/json` bodies into `JsonObject` / mapped POJO
- [ ] Populate `req.body()` after parsing
- [ ] Return empty body object `{}` on missing/mismatched Content-Type (matches Express behaviour)
- [ ] Enforce `limit` — reject oversized bodies with HTTP 413
- [ ] Handle gzip/deflate inflation when `inflate=true`

#### Output
```java
app.filter(CafeAI.json());
app.post("/echo", (req, res, next) -> res.json(req.body()));

// POST /echo {"name":"cafeai"} → 200 {"name":"cafeai"}
// POST /echo (no body)        → req.body() returns empty map
// POST /echo (5MB body)       → 413 Payload Too Large
```

#### Acceptance Criteria
- [ ] Valid JSON body parsed and accessible via `req.body()`
- [ ] Typed retrieval: `req.body(MyDto.class)` deserializes correctly via Jackson
- [ ] Missing body returns empty map, not null
- [ ] Oversized body returns HTTP 413
- [ ] Non-JSON Content-Type passes through without parsing
- [ ] `strict=true` rejects non-object/non-array JSON root values
- [ ] Unit tests for each option
- [ ] Integration test: round-trip JSON POST → echo

---

### Phase 3 — `CafeAI.raw()`

**Goal:** Raw byte body parsing middleware.

#### Input
- `RawOptions` builder class
- `Request` interface with `bodyBytes()` method

#### Tasks
- [ ] Implement `CafeAI.raw()` returning a `Middleware`
- [ ] Implement `CafeAI.raw(RawOptions)` overload
- [ ] Implement `RawOptions` builder: `inflate`, `limit`, `type`
- [ ] Parse body into `byte[]` — accessible via `req.bodyBytes()`
- [ ] Enforce size limit, handle compression

#### Output
```java
app.filter(CafeAI.raw());
app.post("/upload", (req, res) -> {
    byte[] data = req.bodyBytes();
    res.send("Received " + data.length + " bytes");
}, (req, res, next) -> {
    // or inline:
    res.send("Received " + req.bodyBytes().length + " bytes");
});
```

#### Acceptance Criteria
- [ ] Body parsed as `byte[]` accessible via `req.bodyBytes()`
- [ ] Size limit enforced (HTTP 413)
- [ ] gzip/deflate inflation works when `inflate=true`
- [ ] Non-matching Content-Type passes through unparsed
- [ ] Unit + integration tests

---

### Phase 4 — `CafeAI.Router()`

**Goal:** Standalone router factory for modular route grouping.

#### Input
- `Router` interface (already defined)
- `SubRouter` implementation class (stub)
- `RouterOptions` builder

#### Tasks
- [ ] Implement `SubRouter` as concrete `Router`
- [ ] Implement `CafeAI.Router()` factory method
- [ ] Implement `CafeAI.Router(RouterOptions)` overload
- [ ] Implement `RouterOptions`: `caseSensitive`, `mergeParams`, `strict`
- [ ] Sub-router mounting via `app.use(path, router)`
- [ ] `mergeParams` — child inherits parent path params when true
- [ ] `caseSensitive` — `/Foo` vs `/foo` treated distinctly when true
- [ ] `strict` — `/foo` vs `/foo/` treated distinctly when true

#### Output
```java
var api = CafeAI.Router();
api.get("/users", (req, res) -> res.json(users));
api.post("/users", (req, res) -> res.json(create(req.body())));
app.use("/api/v1", api);

// GET /api/v1/users → 200 [...]
```

#### Acceptance Criteria
- [ ] Sub-router handles routes scoped to its mount path
- [ ] `req.baseUrl` reflects the mount path inside sub-router handlers
- [ ] `mergeParams=true` makes parent params available in child router
- [ ] `caseSensitive` option enforced correctly
- [ ] `strict` option enforced correctly
- [ ] Nested routers (router inside router) work correctly
- [ ] Unit + integration tests for each option

---

### Phase 5 — `CafeAI.static()`

**Goal:** Static file serving middleware.

#### Input
- Helidon SE static content support
- `StaticOptions` builder class

#### Tasks
- [ ] Implement `CafeAI.static(String root)` factory method
- [ ] Implement `CafeAI.static(String root, StaticOptions)` overload
- [ ] Implement `StaticOptions` builder: `maxAge(Duration)`, `etag`, `index`,
  `dotfiles(Dotfiles)`, `redirect`, `fallthrough`, `immutable`, `cacheControl`,
  `acceptRanges`, `lastModified`, `extensions(List<String>)`, `setHeaders(HeaderSetter)`
- [ ] `dotfiles` enum: `ALLOW`, `DENY`, `IGNORE`
- [ ] `setHeaders` functional interface for custom header injection
- [ ] Fall-through to `next()` on 404 (when `fallthrough=true`)

#### Output
```java
app.use(CafeAI.static("public", StaticOptions.builder()
    .maxAge(Duration.ofDays(1))
    .etag(true)
    .dotfiles(Dotfiles.IGNORE)
    .build()));
// GET /index.html → serves public/index.html
// GET /.env       → 404 (dotfiles ignored)
```

#### Acceptance Criteria
- [ ] Files served from the configured root directory
- [ ] `Cache-Control` header reflects `maxAge` setting
- [ ] ETag generated and validated on subsequent requests
- [ ] Dotfile requests handled per `dotfiles` option
- [ ] `fallthrough=false` returns 404 immediately; `true` calls `next()`
- [ ] `extensions` fallback works (request `/page` serves `/page.html`)
- [ ] `setHeaders` callback invoked for each file response
- [ ] `immutable` directive added to Cache-Control when enabled
- [ ] Unit + integration tests

---

### Phase 6 — `CafeAI.text()`

**Goal:** Plain text body parsing middleware.

#### Input
- `TextOptions` builder

#### Tasks
- [ ] Implement `CafeAI.text()` returning a `Middleware`
- [ ] Implement `CafeAI.text(TextOptions)` overload
- [ ] Implement `TextOptions`: `charset(Charset)`, `inflate`, `limit`, `type`
- [ ] Parse body as `String` — accessible via `req.bodyText()`
- [ ] Default charset UTF-8

#### Acceptance Criteria
- [ ] Body parsed as `String` via `req.bodyText()`
- [ ] Charset correctly applied from `Content-Type` header or default
- [ ] Size limit enforced
- [ ] Unit + integration tests

---

### Phase 7 — `CafeAI.urlencoded()`

**Goal:** URL-encoded form body parsing middleware.

#### Input
- `UrlEncodedOptions` builder

#### Tasks
- [ ] Implement `CafeAI.urlencoded()` returning a `Middleware`
- [ ] Implement `CafeAI.urlencoded(UrlEncodedOptions)` overload
- [ ] Implement `UrlEncodedOptions`: `extended(boolean)`, `inflate`, `limit`,
  `parameterLimit(int)`, `type`, `depth(int)`
- [ ] `extended=true` allows nested objects (qs-style)
- [ ] `extended=false` allows only flat key-value pairs
- [ ] Enforce `parameterLimit` — reject requests exceeding parameter count

#### Output
```java
app.use(CafeAI.urlencoded(UrlEncodedOptions.extended(true)));
app.post("/form", (req, res) -> {
    String name = req.body("name");
    res.send("Hello " + name);
});
```

#### Acceptance Criteria
- [ ] Flat form data parsed correctly (`extended=false`)
- [ ] Nested objects parsed correctly (`extended=true`)
- [ ] `parameterLimit` enforced (HTTP 413 on excess)
- [ ] `depth` limit enforced
- [ ] Size limit enforced
- [ ] Unit + integration tests

---

## Dependencies Between Phases

```
Phase 1 (create + listen)
    └── Phase 2 (json)       ← req.body() needed by all subsequent phases
    └── Phase 4 (Router)     ← needed before Application routing (ROADMAP-02)
    └── Phase 3 (raw)
    └── Phase 5 (static)
    └── Phase 6 (text)
    └── Phase 7 (urlencoded)
```

Phase 1 must be completed before any other phase begins.
Phases 2–7 are independently parallelisable after Phase 1.

---

## Definition of Done

- [ ] All seven phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public API members
- [ ] `HelloCafeAI.java` example runs end-to-end
- [ ] MILESTONE-01.md updated to reflect completion
