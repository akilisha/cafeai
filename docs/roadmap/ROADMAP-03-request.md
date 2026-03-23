# ROADMAP-03: `Request` — `req` Object

**Maps to:** Express `Request` — all properties and methods  
**Module:** `cafeai-core`  
**ADR Reference:** ADR-005 §4  
**Depends On:** ROADMAP-01 Phase 1, ROADMAP-02 Phase 3  
**Status:** 🔴 Not Started

---

## Objective

Implement the full `Request` object — the immutable, per-request view of the
incoming HTTP request. Every handler and middleware receives a `Request`.
This covers all 21 properties and 8 methods from Express, plus CafeAI-native
extensions (`req.stream()`, `req.attribute()`).

---

## Phases

---

### Phase 1 — Core Identity Properties

**Goal:** The fundamental properties every handler needs on every request.

#### Input
- Helidon SE `ServerRequest`
- `CafeAIApp` reference (for `req.app()`)

#### Tasks
- [ ] Implement `req.app()` — returns the `CafeAI` application instance
- [ ] Implement `req.method()` — returns `HttpMethod` enum (not raw string)
- [ ] Implement `req.path()` — returns path portion of URL (e.g. `/users/42`)
- [ ] Implement `req.originalUrl()` — full URL including query string
- [ ] Implement `req.baseUrl()` — path prefix at which router was mounted
- [ ] Implement `req.protocol()` — returns `"http"` or `"https"`
- [ ] Implement `req.secure()` — shorthand for `req.protocol().equals("https")`
- [ ] Implement `req.hostname()` — from `Host` header, strips port
- [ ] Implement `req.ip()` — remote IP, respects `X-Forwarded-For` when `TRUST_PROXY` enabled
- [ ] Implement `req.ips()` — returns `List<String>` of IPs from proxy chain
- [ ] Implement `req.subdomains()` — returns `List<String>`, offset by `SUBDOMAIN_OFFSET` setting
- [ ] Implement `req.xhr()` — detects `X-Requested-With: XMLHttpRequest`

#### Acceptance Criteria
- [ ] `req.method()` returns correct `HttpMethod` for each HTTP verb
- [ ] `req.path()` strips query string correctly
- [ ] `req.originalUrl()` includes query string
- [ ] `req.baseUrl()` reflects router mount path
- [ ] `req.secure()` is `true` for HTTPS requests
- [ ] `req.ip()` returns direct IP when `TRUST_PROXY=false`
- [ ] `req.ip()` returns forwarded IP when `TRUST_PROXY=true`
- [ ] `req.subdomains()` respects `SUBDOMAIN_OFFSET` setting
- [ ] Unit tests for each property

---

### Phase 2 — Parameters, Query & Body

**Goal:** Access to route parameters, query string, and parsed body.

#### Input
- Route parameter extraction (ROADMAP-02 Phase 3)
- Body parsing middleware (ROADMAP-01 Phases 2, 3, 6, 7)

#### Tasks
- [ ] Implement `req.params(String name)` — route path parameter (e.g. `:id`)
- [ ] Implement `req.params()` — returns `Map<String, String>` of all params
- [ ] Implement `req.query(String name)` — single query parameter
- [ ] Implement `req.query(String name, String defaultValue)` — with default
- [ ] Implement `req.queryMap()` — returns `Map<String, List<String>>` (multi-value aware)
- [ ] Implement `req.body()` — returns parsed body as `Map<String, Object>`
- [ ] Implement `req.body(String key)` — single key from parsed body
- [ ] Implement `req.body(Class<T> type)` — typed body deserialization via Jackson
- [ ] Implement `req.bodyBytes()` — raw body as `byte[]` (set by `CafeAI.raw()`)
- [ ] Implement `req.bodyText()` — body as `String` (set by `CafeAI.text()`)

#### Output
```java
// Route: /users/:id?include=profile
req.params("id")              // → "42"
req.query("include")          // → "profile"
req.query("missing", "none")  // → "none"
req.body(UserDto.class)       // → UserDto instance
```

#### Acceptance Criteria
- [ ] Path params correctly extracted from parameterised routes
- [ ] Multi-segment params work (e.g. `/:category/:id`)
- [ ] Query string parsed correctly including multi-value params
- [ ] `req.body(Class)` deserializes JSON body via Jackson
- [ ] `req.body(key)` returns null for missing keys (not exception)
- [ ] Body methods return null/empty when no body middleware registered
- [ ] Integration tests for params + query + body combinations

---

### Phase 3 — Headers & Content Negotiation

**Goal:** Header access and content negotiation methods.

#### Tasks
- [ ] Implement `req.header(String name)` — get header value (case-insensitive)
- [ ] Implement `req.headers()` — returns `Map<String, String>` of all headers
- [ ] Implement `req.is(String type)` — checks Content-Type matches
- [ ] Implement `req.accepts(String... types)` — content negotiation via Accept header
- [ ] Implement `req.acceptsCharsets(String... charsets)` — charset negotiation
- [ ] Implement `req.acceptsEncodings(String... encodings)` — encoding negotiation
- [ ] Implement `req.acceptsLanguages(String... languages)` — language negotiation

#### Output
```java
req.header("Authorization")        // → "Bearer abc123"
req.is("application/json")         // → true / false
req.accepts("json", "html")        // → "json" (best match) or null
req.acceptsLanguages("en", "fr")   // → "en"
```

#### Acceptance Criteria
- [ ] Header lookup is case-insensitive
- [ ] `req.is()` handles mime type wildcards
- [ ] `req.accepts()` returns best match per q-factor weighting
- [ ] All negotiation methods return `null` when no acceptable match
- [ ] Unit tests for each method including edge cases (wildcard, q-factor)

---

### Phase 4 — Cookies & Cache

**Goal:** Cookie access and cache freshness detection.

#### Input
- Cookie parsing (from `CafeAI.cookieParser()` — see note)

#### Tasks
- [ ] Implement `req.cookies()` — returns `Map<String, String>` of all cookies
- [ ] Implement `req.cookie(String name)` — single cookie value
- [ ] Implement `req.signedCookies()` — verified signed cookies
- [ ] Implement `req.signedCookie(String name)` — single signed cookie
- [ ] Implement `req.fresh()` — cache freshness check (ETag + Last-Modified)
- [ ] Implement `req.stale()` — inverse of `req.fresh()`

> **Note:** Cookie parsing requires a separate `CafeAI.cookieParser()` middleware
> to be registered (mirrors Express's `cookie-parser` package). Cookie properties
> are empty until this middleware is applied.

#### Acceptance Criteria
- [ ] `req.cookies()` returns empty map when no cookie middleware registered
- [ ] `req.cookie(name)` returns correct value after cookie middleware applied
- [ ] Signed cookies verified using application secret
- [ ] Invalid signed cookie signatures return `false` (not the value)
- [ ] `req.fresh()` returns `true` when ETag / Last-Modified matches
- [ ] `req.stale()` is always `!req.fresh()`
- [ ] Unit + integration tests

---

### Phase 5 — Route Info & Range

**Goal:** Route metadata and byte-range request parsing.

#### Tasks
- [ ] Implement `req.route()` — returns current matched `Route` metadata
  (path pattern, HTTP method, stack of handlers)
- [ ] Implement `req.response()` — returns the paired `Response` object
  (translated from Express `req.res` — see ADR-005)
- [ ] Implement `req.range(long size)` — parses `Range` header
  Returns `List<Range>` where `Range` has `start` and `end` fields
  Returns `-1` for unsatisfiable range
  Returns `-2` for malformed `Range` header

#### Acceptance Criteria
- [ ] `req.route()` returns correct path pattern and method
- [ ] `req.response()` returns the same `Response` instance as the handler received
- [ ] `req.range()` parses single range correctly
- [ ] `req.range()` parses multi-range correctly
- [ ] `-1` returned for out-of-bounds range
- [ ] `-2` returned for malformed header
- [ ] Unit tests for range parsing edge cases

---

### Phase 6 — CafeAI Extensions

**Goal:** AI-native request properties and attribute carrier with no Express equivalent.

#### Tasks
- [ ] Implement `req.stream()` — returns `true` when `Accept: text/event-stream` present
- [ ] Implement `req.setAttribute(String key, Object value)` — set typed request attribute
- [ ] Implement `req.attribute(String key)` — untyped retrieve
- [ ] Implement `req.attribute(String key, Class<T> type)` — typed retrieve
- [ ] Implement `req.hasAttribute(String key)` — existence check
- [ ] Thread-safe attribute map — safe for use with virtual threads
- [ ] Pre-defined attribute key constants:
  `Attributes.RAG_DOCUMENTS`, `Attributes.GUARDRAIL_SCORE`,
  `Attributes.TOKEN_COUNT`, `Attributes.SESSION_ID`, `Attributes.AUTH_PRINCIPAL`

#### Output
```java
// In auth middleware:
req.setAttribute(Attributes.AUTH_PRINCIPAL, principal)

// In guardrail middleware:
req.setAttribute(Attributes.GUARDRAIL_SCORE, score)

// In handler:
Principal p = req.attribute(Attributes.AUTH_PRINCIPAL, Principal.class)
boolean streaming = req.stream() // → true for SSE clients
```

#### Acceptance Criteria
- [ ] Attributes set in middleware are available in downstream handlers
- [ ] Typed retrieval throws on type mismatch
- [ ] `req.stream()` returns `true` only when `Accept: text/event-stream` present
- [ ] Pre-defined constants accessible statically
- [ ] Thread-safe under concurrent virtual thread access
- [ ] Unit + integration tests

---

## Definition of Done

- [ ] All six phases complete
- [ ] All acceptance criteria passing
- [ ] Zero Checkstyle violations
- [ ] Javadoc on all public API members
- [ ] MILESTONE-03.md updated to reflect completion
