# ROADMAP-04: `Response` — `res` Object

**Maps to:** Express `Response` — all properties and methods  
**Module:** `cafeai-core` + `cafeai-streaming`  
**ADR Reference:** ADR-005 §5  
**Depends On:** ROADMAP-01 Phase 1, ROADMAP-03 Phase 1  
**Status:** 🔴 Not Started

---

## Objective

Implement the full `Response` object — the per-request outbound HTTP response
builder. Covers all 3 properties and 21 methods from Express, translated per
ADR-005, plus CafeAI-native streaming extensions (`res.stream()`, `res.streamJson()`).

---

## Phases

---

### Phase 1 — Core Send Methods

**Goal:** The fundamental response methods every handler needs.

#### Input
- Helidon SE `ServerResponse`
- Jackson for JSON serialization

#### Tasks
- [ ] Implement `res.send(String body)` — sends text response
- [ ] Implement `res.send(byte[] body)` — sends binary response
- [ ] Implement `res.json(Object body)` — serializes to JSON via Jackson, sets `Content-Type: application/json`
- [ ] Implement `res.sendStatus(int code)` — sends status code with default status message body
- [ ] Implement `res.end()` — terminates response with no body
- [ ] Implement `res.status(int code)` — sets status code, returns `this` for chaining
- [ ] Implement `res.headersSent()` — returns `true` if headers already flushed
- [ ] Guard: throw `IllegalStateException` if `send`/`json`/`end` called after `headersSent()`

#### Output
```java
res.status(201).json(created)      // 201 + JSON body
res.status(204).end()              // 204 No Content
res.sendStatus(404)                // 404 "Not Found"
res.send("Hello CafeAI")          // 200 text/html
```

#### Acceptance Criteria
- [ ] `res.json()` sets `Content-Type: application/json; charset=utf-8`
- [ ] `res.send(String)` sets `Content-Type: text/html` by default
- [ ] `res.status()` is fluent — returns `res` for chaining
- [ ] `res.sendStatus(404)` body is `"Not Found"` (standard HTTP reason phrase)
- [ ] `res.headersSent()` returns `true` after first `send`/`json`/`end` call
- [ ] Double-send throws `IllegalStateException`
- [ ] Unit tests for each method
- [ ] Integration tests: status + body combinations

---

### Phase 2 — Headers Management

**Goal:** Full control over response headers.

#### Tasks
- [ ] Implement `res.set(String field, String value)` — set single header
- [ ] Implement `res.set(Map<String, String> headers)` — set multiple headers
- [ ] Implement `res.header(String field)` — get current response header value
- [ ] Implement `res.append(String field, String value)` — append to header (comma-separated)
- [ ] Implement `res.type(String type)` — set `Content-Type` (shorthand + mime lookup)
- [ ] Implement `res.vary(String field)` — add field to `Vary` header
- [ ] Implement `res.links(Map<String, String> links)` — populate `Link` header
- [ ] Implement `res.location(String url)` — set `Location` header
- [ ] Implement `res.app()` — returns the `CafeAI` application instance
- [ ] Implement `res.locals()` — request-scoped local data store
- [ ] Implement `res.local(String key, Object value)` — set local
- [ ] Implement `res.local(String key, Class<T> type)` — typed get local

> **`res.locals` note:** Backed by `ScopedValue` for virtual-thread safety per ADR-005.

#### Output
```java
res.set("X-Custom-Header", "value")
res.set(Map.of("X-A", "1", "X-B", "2"))
res.type("json")                           // → Content-Type: application/json
res.vary("Accept-Encoding")
res.links(Map.of("next", "/page/2", "prev", "/page/0"))
// Link: </page/2>; rel="next", </page/0>; rel="prev"
```

#### Acceptance Criteria
- [ ] `res.set()` overwrites existing header value
- [ ] `res.append()` adds to existing value (comma-separated)
- [ ] `res.type("json")` resolves to full MIME type
- [ ] `res.type("application/json")` accepted directly
- [ ] `res.vary()` appends to existing `Vary` header, no duplicates
- [ ] `res.links()` formats `Link` header per RFC 5988
- [ ] `res.local()` values accessible from middleware and handlers in same request
- [ ] Unit tests for header formatting edge cases

---

### Phase 3 — Redirects & Status Helpers

**Goal:** Redirect responses and content-type-based dispatch.

#### Tasks
- [ ] Implement `res.redirect(String url)` — 302 redirect
- [ ] Implement `res.redirect(int status, String url)` — redirect with explicit status
- [ ] Implement `res.format(ContentMap contentMap)` — content-type negotiated dispatch
- [ ] `ContentMap` builder: `.text(Supplier<Void>)`, `.html(Supplier<Void>)`, `.json(Supplier<Void>)`, `.type(String, Supplier<Void>)`
- [ ] `res.format()` returns 406 Not Acceptable when no match found

#### Output
```java
res.redirect("/login")              // 302
res.redirect(301, "/new-location")  // 301 permanent

res.format(ContentMap.of()
    .text(() -> res.send("plain text"))
    .html(() -> res.send("<b>bold</b>"))
    .json(() -> res.json(Map.of("msg", "hello")))
    .build())
```

#### Acceptance Criteria
- [ ] Default redirect is 302
- [ ] 301, 302, 303, 307, 308 all accepted as redirect status codes
- [ ] `res.format()` selects best match per `Accept` header
- [ ] 406 returned when no registered type matches `Accept`
- [ ] `ContentMap` builder rejects duplicate type registrations
- [ ] Unit + integration tests

---

### Phase 4 — Cookies

**Goal:** Cookie setting and clearing.

#### Tasks
- [ ] Implement `res.cookie(String name, String value)` — set cookie
- [ ] Implement `res.cookie(String name, String value, CookieOptions options)`
- [ ] `CookieOptions` builder: `maxAge(Duration)`, `expires(Instant)`, `httpOnly(boolean)`,
  `secure(boolean)`, `sameSite(SameSite)`, `domain(String)`, `path(String)`, `signed(boolean)`
- [ ] `SameSite` enum: `STRICT`, `LAX`, `NONE`
- [ ] Signed cookies: HMAC-SHA256 signed with application secret
- [ ] Implement `res.clearCookie(String name)` — expires cookie immediately
- [ ] Implement `res.clearCookie(String name, CookieOptions options)`

#### Acceptance Criteria
- [ ] Cookie `Set-Cookie` header formatted per RFC 6265
- [ ] `httpOnly` and `secure` flags set correctly
- [ ] `maxAge(Duration)` sets `Max-Age` in seconds
- [ ] Signed cookies have verifiable HMAC signature
- [ ] `clearCookie()` sets `Max-Age=0` and past `Expires`
- [ ] Unit tests for cookie header formatting

---

### Phase 5 — File Responses

**Goal:** File download, attachment, and static file send.

#### Input
- `java.nio.file.Path` for file references
- Helidon SE file serving

#### Tasks
- [ ] Implement `res.download(Path file)` — sends file as download attachment
- [ ] Implement `res.download(Path file, String filename)` — with custom filename
- [ ] Implement `res.download(Path file, String filename, DownloadOptions options)`
- [ ] Implement `res.attachment()` — sets `Content-Disposition: attachment` with no file
- [ ] Implement `res.attachment(String filename)` — sets disposition + filename + Content-Type
- [ ] Implement `res.sendFile(Path file)` — sends file inline (not as download)
- [ ] Implement `res.sendFile(Path file, SendFileOptions options)`
- [ ] `SendFileOptions`: `maxAge(Duration)`, `root(Path)`, `headers(Map)`, `dotfiles(Dotfiles)`
- [ ] Path traversal protection — reject paths outside configured root

#### Acceptance Criteria
- [ ] `res.download()` sets `Content-Disposition: attachment; filename="..."`
- [ ] `res.sendFile()` sets `Content-Disposition: inline`
- [ ] `Content-Type` inferred from file extension
- [ ] Path traversal attempt returns 403
- [ ] Missing file returns 404
- [ ] `ETag` and `Last-Modified` headers set for `sendFile`
- [ ] Unit + integration tests including security test for path traversal

---

### Phase 6 — Rendering & JSONP Omission

**Goal:** `res.render()` integration with app.engine() and formal JSONP omission.

#### Input
- `app.engine()` registration (ROADMAP-02 Phase 8)

#### Tasks
- [ ] Implement `res.render(String view)` — renders view with `res.locals` as data
- [ ] Implement `res.render(String view, Map<String, Object> locals)` — with extra data
- [ ] `res.render()` merges `res.locals`, `app.locals`, and provided locals (in that order)
- [ ] Document `res.jsonp()` omission formally in code — `@Deprecated` annotation
  with message: *"JSONP is a legacy CORS workaround. Use CORS headers instead. Not implemented in CafeAI."*

#### Acceptance Criteria
- [ ] `res.render()` uses view engine registered for the file extension
- [ ] Template data correctly merges locals sources
- [ ] Missing template returns 500 with clear error
- [ ] `res.jsonp()` method exists but throws `UnsupportedOperationException` with the documented message
- [ ] Unit + integration tests

---

### Phase 7 — CafeAI Streaming Extensions

**Goal:** Token streaming with SSE backpressure — the AI-native response primitive.

#### Input
- Helidon SE reactive pipeline (SSE support)
- `cafeai-streaming` module
- `java.util.concurrent.Flow.Publisher`

#### Tasks
- [ ] Implement `res.stream(Publisher<String> tokens)` — stream string tokens as SSE
- [ ] Implement `res.stream(AiResponse response)` — stream LLM response tokens as SSE
- [ ] Implement `res.streamJson(Publisher<Object> objects)` — NDJSON stream (newline-delimited JSON)
- [ ] Set `Content-Type: text/event-stream` automatically
- [ ] Set `Cache-Control: no-cache` automatically
- [ ] Set `Connection: keep-alive` automatically
- [ ] Backpressure: slow client does not block the virtual thread pool
- [ ] On client disconnect: cancel the upstream publisher
- [ ] `res.stream()` is terminal — cannot call other send methods after

#### Output
```java
app.post("/chat", (req, res) -> {
    Publisher<String> tokens = aiService.streamChat(req.body("message"));
    res.stream(tokens);
    // Tokens flow to client as SSE events: data: <token>\n\n
});
```

SSE wire format:
```
data: Hello\n\n
data:  world\n\n
data: [DONE]\n\n
```

#### Acceptance Criteria
- [ ] Tokens emitted as individual SSE `data:` events
- [ ] `[DONE]` sentinel event sent on stream completion
- [ ] Client disconnect cancels upstream publisher (no resource leak)
- [ ] Backpressure: 1000 concurrent streams do not exhaust thread pool
- [ ] `res.headersSent()` returns `true` immediately after `res.stream()` called
- [ ] Calling any other send method after `res.stream()` throws `IllegalStateException`
- [ ] Integration test: full SSE stream end-to-end with a mock publisher
- [ ] Load test: 100 concurrent streaming connections

---

## Definition of Done

- [ ] All seven phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public API members
- [ ] MILESTONE-04.md updated to reflect completion
