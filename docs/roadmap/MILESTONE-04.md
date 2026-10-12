# MILESTONE-04: `Response` — `res` Object

**Roadmap:** ROADMAP-04
**Module:** `cafeai-core`
**Started:** March 2026
**Completed:** March 2026
**Current Status:** 🟢 Complete

---

## Progress Tracker

| Phase | Description | Status | Completed |
|---|---|---|---|
| Phase 1 | Core send methods | 🟢 Complete | March 2026 |
| Phase 2 | Headers management | 🟢 Complete | March 2026 |
| Phase 3 | Redirects & status helpers | 🟢 Complete | March 2026 |
| Phase 4 | Cookies | 🟢 Complete | March 2026 |
| Phase 5 | File responses | 🟢 Complete | March 2026 |
| Phase 6 | Rendering & content negotiation | 🟢 Complete | March 2026 |
| Phase 7 | CafeAI streaming extensions | 🟢 Complete | March 2026 |

**Legend:** 🔴 Not Started · 🟡 In Progress · 🟢 Complete · 🔵 Revised

---

## Completed Items

**Phase 1 — Core Send Methods (March 2026)**

- `res.send(String)` — sends string body; defaults Content-Type to `text/html; charset=utf-8`
- `res.send(byte[])` — sends binary body; defaults Content-Type to `application/octet-stream`
- `res.json(Object)` — serializes via Jackson, sets `application/json; charset=utf-8`
- `res.end()` — empty body response
- `res.sendStatus(int)` — sends status with reason phrase; 204 and 304 send **no body**
  (HTTP spec compliance — Java's HttpClient rejects bodies on 204 responses)
- `committed` flag — throws `IllegalStateException` on double-send

**Phase 2 — Headers Management (March 2026)**

- `res.set(field, value)` — single header via `HeaderValues.create(field, value)`
- `res.set(Map)` — multiple headers
- `res.append(field, value)` — appends to existing value with `, ` separator
- `res.header(field)` — reads response header via `HeaderNames.create(lc, name)`
- `res.type(type)` — shorthand aliases: `"json"`, `"html"`, `"text"`, `"xml"`, `"form"`, `"bin"`
- `res.vary(field)` — adds to Vary header; deduplicates
- `res.links(Map)` — builds RFC 5988 `Link` header
- `res.location(url)` — sets `Location` header
- `res.headersSent()` — returns `committed` flag

**Phase 3 — Redirects & Status (March 2026)**

- `res.status(int)` — fluent status setter via `Status.create(code)`
- `res.redirect(url)` — 302 redirect
- `res.redirect(status, url)` — explicit status redirect

**Phase 4 — Cookies (March 2026)**

- `res.cookie(name, value)` — basic cookie
- `res.cookie(name, value, CookieOptions)` — full options: `maxAge`, `domain`, `path`,
  `secure`, `httpOnly`, `sameSite`, `signed`
- `res.clearCookie(name)` — sets `Max-Age=0`
- `res.clearCookie(name, CookieOptions)` — with path scoping
- Multiple cookies via repeated `Set-Cookie` headers via `headers().add()`

**Phase 5 — File Responses (March 2026)**

- `res.sendFile(Path)` — reads bytes via `Files.readAllBytes()`, sends 404 on `IOException`
- `res.download(Path)` — sets `Content-Disposition: attachment`, delegates to `sendFile()`
- `res.download(Path, filename)` — custom download filename
- `res.attachment(filename)` — fluent; sets header without sending

**Phase 6 — Rendering & Content Negotiation (March 2026)**

- `res.format(ContentMap)` — content negotiation; sends 406 if no match
- `res.render(view)` / `res.render(view, locals)` — delegates to `app.render()`
- `res.local(key, value)` / `res.local(key)` / `res.local(key, Class<T>)` — request-scoped locals

**Phase 7 — SSE Streaming Extensions (March 2026)**

- `res.stream(Flow.Publisher<String>)` — sets SSE headers (`text/event-stream`,
  `no-cache`, `keep-alive`), subscribes to publisher, writes `data: token\n\n` events,
  sends `data: [DONE]\n\n` on completion, `data: [ERROR]\n\n` on error

---

## Decisions & Design Updates

**March 2026 — 204/304 body prohibition**

Initial `sendStatus()` sent the reason phrase string for all status codes.
Java's `HttpClient` rejects a body on 204 responses per HTTP spec. Fixed: 204 and 304
call `helidonRes.send()` with no body. All other codes send the reason phrase.

**March 2026 — committed flag before helidonRes.send()**

`commit()` sets the `committed` flag *before* `helidonRes.send()` to prevent re-entrancy
issues if `send()` itself throws. The committed state is logically "we decided to send"
not "we successfully sent."

---

## Timeline

| Milestone Event | Target Date | Actual Date | Notes |
|---|---|---|---|
| Phases 1–3 complete | — | March 2026 | |
| Phases 4–7 complete | — | March 2026 | |
| Integration tests passing | — | March 2026 | Covered in MILESTONE-01 integration suite |
| MILESTONE-04 closed | — | March 2026 | |
