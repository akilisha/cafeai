# MILESTONE-03: `Request` — `req` Object

**Roadmap:** ROADMAP-03
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | Core identity properties | 🟢 Complete | March 2026 |
| Phase 2 | Parameters, query & body | 🟢 Complete | March 2026 |
| Phase 3 | Headers & content negotiation | 🟢 Complete | March 2026 |
| Phase 4 | Cookies & cache freshness | 🟡 Stubbed | March 2026 |
| Phase 5 | Route info & range | 🟡 Stubbed | March 2026 |
| Phase 6 | CafeAI extensions (stream, attributes) | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised · 🟡 Stubbed (API present, impl deferred)

---

## Completed Items

**Phase 1 — Core Identity (March 2026)**

- `req.app()` — returns the `CafeAI` application instance
- `req.method()` — HTTP method string ("GET", "POST", etc.)
- `req.path()` — path without query string
- `req.originalUrl()` — full URL including query string
- `req.baseUrl()` — mount prefix (set via `_baseUrl` attribute)
- `req.protocol()` — `"http"` or `"https"`
- `req.secure()` — `true` for HTTPS
- `req.hostname()` — from `Host` header; falls back to `helidonReq.authority()`
  when `HOST` header is absent in Helidon filter context; strips port
- `req.ip()` — remote peer host
- `req.ips()` — list from `X-Forwarded-For`; single-element list of `ip()` when absent
- `req.subdomains()` — segments before TLD, respects `SUBDOMAIN_OFFSET` setting
- `req.xhr()` — detects `X-Requested-With: XMLHttpRequest`

**Phase 2 — Parameters, Query & Body (March 2026)**

- `req.params(name)` — single path parameter via `pathParameters().first(name)`
- `req.params()` — all params as `Map<String, String>` via names() iteration
- `req.query(name)` — single query parameter via `query().first(name)`
- `req.query(name, default)` — with default value
- `req.queryMap()` — multi-value `Map<String, List<String>>`
- `req.body()` — parsed body as `Map<String, Object>` (populated by body-parsing filter)
- `req.body(key)` — single key as `String`
- `req.body(Class<T>)` — typed Jackson deserialization via `MAPPER.convertValue()`;
  `FAIL_ON_UNKNOWN_PROPERTIES = false` for graceful DTO mapping
- `req.bodyBytes()` — raw body as `byte[]`
- `req.bodyText()` — raw body as `String`

**Phase 3 — Headers & Content Negotiation (March 2026)**

- `req.header(name)` — case-insensitive via `HeaderNames.create(name.toLowerCase(), name)`
- `req.headers()` — all headers as `Map<String, String>` via `headerName().defaultCase()`
- `req.is(type)` — Content-Type match with wildcard support
- `req.accepts(types...)` — best match from Accept header; first type wins on `*/*`
- `req.acceptsCharsets()`, `req.acceptsEncodings()`, `req.acceptsLanguages()` — stub
  implementations returning the first preference (sufficient for current use cases)

**Phase 4 — Cookies & Cache (Stubbed, March 2026)**

- `req.cookies()` / `req.cookie(name)` — return empty (cookie parser middleware not yet implemented)
- `req.signedCookies()` / `req.signedCookie(name)` — return empty
- `req.fresh()` / `req.stale()` — return `false`/`true` (ETag matching not yet implemented)

**Phase 5 — Route Info & Range (Stubbed, March 2026)**

- `req.route()` — returns `RouteImpl(path, method)` built from `_routePattern` attribute
- `req.response()` — returns paired `Response` from `_response` attribute
- `req.range(size)` — returns `null` (Range header parsing not yet implemented)

**Phase 6 — CafeAI Extensions (March 2026)**

- `req.stream()` — `true` when `Accept: text/event-stream` present (SSE detection)
- `req.setAttribute(key, value)` — stores in `ConcurrentHashMap<String, Object>`
- `req.attribute(key)` — untyped retrieval
- `req.attribute(key, Class<T>)` — typed retrieval; `ClassCastException` on mismatch
- `req.hasAttribute(key)` — existence check
- Package-private setters for middleware: `setParsedBody()`, `setRawBody()`, `setTextBody()`
- `helidonServerRequest()` — package-private accessor for body-reading in middleware

---

## Decisions & Design Updates

**March 2026 — authority() fallback for hostname()**

`HeaderNames.HOST` lookup in Helidon's filter context may not expose the `Host` header
directly (filter receives `RoutingRequest`, which differs from handler `ServerRequest`).
Added `helidonReq.authority()` as a fallback when the HOST header value is blank.
`authority()` is always populated by Helidon for all request types.

**March 2026 — body() populated via shared RequestContext**

Body-parsing middleware sets state on the `HelidonRequest` instance via `setParsedBody()`.
This only works correctly because `RequestContext` (WeakHashMap in `CafeAIApp`) ensures
all filters and the route handler operate on the **same** `HelidonRequest` instance for
a given HTTP request. See MILESTONE-01 decisions.

**March 2026 — Phases 4 and 5 intentionally stubbed**

Cookie parsing requires a `cookieParser()` middleware with HMAC signing infrastructure.
Range header parsing is an edge case not needed until file-serving and media use cases arise.
Both APIs are present and return safe empty/null values. Neither throws. Full implementation
deferred to a future milestone once the AI layer is in place.

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phases 1–3 complete | — | March 2026 | |
| Phase 6 complete | — | March 2026 | |
| Phases 4–5 stubbed | — | March 2026 | Full impl deferred |
| Integration tests passing | — | March 2026 | Covered in MILESTONE-01 integration suite |
| MILESTONE-03 closed | — | March 2026 | |
