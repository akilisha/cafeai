# Identity: CafeAI for more than one person

**Status:** Draft design, nothing built
**Date:** 7 October 2026
**Module:** `cafeai-identity` (new), plus changes to `cafeai-core`

---

## 1. The problem

CafeAI is *identityless*. It serves many concurrent requests, but every one of them reaches the
model under a single identity: whoever's API key is in the environment.

- Providers read a static key when their client is built
  (`.apiKey(resolveApiKey("OPENAI_API_KEY", provider))` in `LangchainBridge`), and the client is
  cached per provider (`LangchainBridge.modelFor`, a `Map<AiProvider, ChatModel>`).
- Core has no sign-in. `Attributes.AUTH_PRINCIPAL` is an untyped placeholder that nothing sets.
- `app.usage()` counts per route, not per person.
- Conversation memory is keyed by an id the client supplies (`.session(req.header("X-Session-Id"))`).

The two ways CafeAI reaches a model today don't survive a company:

| Today | Why it fails in a company |
|---|---|
| A static API key | No accountability or traceability per person, and no way to revoke one person's access |
| A local open model per user | Large memory per user; viable only centrally, behind the same sign-in as everything else |

Without identity, every other CafeAI feature (guardrails, usage, RAG, Flight, memory) serves
an audience of one. This design gives each request a verified identity, carries it through the AI
layer, and uses it for every outgoing call.

## 2. Goals and non-goals

**Goals**
- Every request has a verified identity, whether it came from a browser, a CLI or another service.
- Every model call, tool call, usage count, budget, guardrail event and audit record is
  attributable to that identity.
- Access is revoked centrally: disabling someone at the issuer cuts them off everywhere.
- Built only on open standards. Anything that implements them works.

**Non-goals**
- CafeAI is never an identity provider. It stores no users and no passwords.
- No code specific to any identity product. A product is named only where it is a defensible
  choice for testing (§12).
- Single-user use doesn't change (§11).

## 3. Standards

| Standard | Used for |
|---|---|
| OAuth 2.0 (RFC 6749) | The framework: clients, grants, tokens |
| Bearer tokens (RFC 6750) | Sending and rejecting access tokens (`WWW-Authenticate`) |
| OpenID Connect Core and Discovery | Sign-in, ID tokens, finding the issuer's endpoints and keys |
| JWT, JWS, JWK (RFC 7519, 7515, 7517) | Validating tokens locally against the issuer's published keys |
| PKCE (RFC 7636) | Protecting the browser sign-in's code exchange |
| Device authorization grant (RFC 8628) | CLI sign-in |
| Client credentials grant (RFC 6749 §4.4) | The app calling something as itself |
| Token exchange (RFC 8693) | The app calling something on behalf of the signed-in user |
| Protected resource metadata (RFC 9728) | Required by the MCP authorization spec (§6.5) |
| OpenTelemetry end-user attributes | Recording the identity on traces (§7.3) |

## 4. Overview

```
 browser ──(code flow + PKCE)──┐
 CLI ─────(device grant)───────┼──> Issuer (any OpenID provider)
 service ─(client credentials)─┘          │ tokens
                                          ▼
 request ──> [ identity: validate ] ──> Identity on the request
                                          │
              routes, guardrails, RAG, agents ...
              (memory bound to identity, retrieval filtered by permission)
                                          │
              outgoing call ──> [ credentials: per request ] ──> model / tool / MCP server
                                          │
              usage · quota · audit, all keyed by issuer + subject
```

Browser, CLI and service sign-in are the same thing from the app's side: three ways of getting a
token from the same issuer. The app only ever validates tokens, so there is **one** inbound
mechanism, not three.

## 5. `Identity` (core)

A small, immutable type in `cafeai-core`:

- `issuer`, `subject`: **together they are the key.** A subject is only unique within its issuer,
  so usage, quotas, memory and audit all use the pair.
- `name`, `scopes`, `groups`, `claims`, `expiresAt`.
- The raw access token. It isn't readable through the public API and is used only to obtain
  outgoing credentials (§8).

Access:
- `req.identity()` returns `Optional<Identity>`.
- `Identity.current()` returns it to code below the handler. It uses the per-request scope that
  `UsageMeter` already maintains, so it reaches streams (captured when they start) and agent
  tool loops.

It replaces the untyped `Attributes.AUTH_PRINCIPAL`.

## 6. Inbound: `cafeai-identity`

One module. The server side lives in the main package, and the CLI client helper in its own
package. Every part is ordinary `Middleware` (ADR-009), placed in a chain like everything else.

API names in this document are sketches, not settled:

```java
var issuer = Issuer.discover("https://issuer.example.com/");   // OpenID Connect Discovery

app.filter(Identity.bearer(issuer).audience("orders-api"));     // APIs, CLIs, services
app.filter(Identity.login(issuer, client));                      // browsers
app.get("/admin", Identity.require(scope("admin")), handler);
```

### 6.1 `bearer(issuer)`: resource server
- Validates JWT access tokens **locally**: signature against the issuer's JWK set, plus `iss`,
  `aud`, `exp` and `nbf`, with a small allowance for clock skew.
- The key set is cached. A token signed with an unknown key id triggers one rate-limited refresh,
  which handles key rotation. If the issuer is unreachable, the cached keys keep working.
- Failures get the standard RFC 6750 `401`/`403` responses with `WWW-Authenticate`.

### 6.2 `login(issuer, client)`: browser sign-in
- Authorization code flow with PKCE, `state` and `nonce`.
- **Tokens are held server-side in `cafeai-session`. The cookie holds only the session id.**
  Tokens never reach the browser, so signing out deletes them for good, and they're on the
  server when outgoing calls need them (§8). See §9 for why we don't use Helidon's OIDC provider
  here.
- Access tokens are refreshed server-side using the refresh token.
- Session cookies are `HttpOnly`, `Secure` and `SameSite`, and requests that change state are
  protected against CSRF (§10).
- Sign-out ends the session and calls the issuer's end-session endpoint.

### 6.3 `require(...)`: authorization
Checks a scope, group or claim. It's ordinary middleware, so it goes on a route, a router or a
filter.

### 6.4 CLI client helper
Device authorization grant: the CLI shows a code and a link, and the user signs in in any browser
on any device. This also works over SSH with no local browser. Tokens are cached for the user and
refreshed. It's for any Java tool or JBang script that calls a CafeAI service.

### 6.5 MCP
The MCP authorization spec uses OAuth 2.1 and requires protected resource metadata (RFC 9728).
`cafeai-mcp` gets both from this module: `bearer(issuer)` protects the MCP endpoint, and the
metadata tells MCP clients which issuer to sign in with.

## 7. Identity in the AI layer

### 7.1 Usage
`app.usage()` reports per identity as well as per route. The existing `UsageMeter` tally gains the
identity's key.

### 7.2 Quotas
A per-identity quota (tokens or cost in a time window) **refuses** a call once it's exceeded, with
`429` and `Retry-After`. That's a different job from `TokenBudget`, which paces the whole app by
pausing before a call. Both can be used together.

### 7.3 Audit
One record per model call and per tool call: issuer + subject, route, model, tokens, cost,
guardrail outcome, and time. It's emitted through the existing observability bridge with
OpenTelemetry's end-user attributes.

Audit records are personal data. **By default they hold metadata only.** Prompt and answer text
is opt-in, redacted (the `Redactor` in `cafeai-sentinel` is a starting point), and has its own
retention setting.

### 7.4 Conversation memory: bound to identity
Today the memory key is whatever id the client sends, so anyone with someone's id reads and
continues their conversation. With identity on, the key becomes **issuer + subject + conversation
id**. A request whose identity doesn't own the conversation is refused, never served.

### 7.5 RAG: filtered by permission
Company documents are shared, but by permission, not by person. Without filtering, RAG is a way to
read any indexed document through the model.

- At ingestion, each document carries permission metadata: the groups or principals allowed to
  read it.
- At retrieval, results are filtered against the current identity's groups **inside the vector
  store query**, not after it. Filtering after retrieval returns too few results and is easy to
  get wrong.
- With identity on, a document without permission metadata is visible to no one, unless the
  app explicitly marks it public.

This is the largest change in the design. It touches `RagDocument`, `RagIngestion`, `Retriever`
and each `VectorStore`.

### 7.6 Semantic cache: one rule to keep
ADR-013 already restricts the cache to answers that depend on nothing but the prompt. A call with
a conversation or with RAG bypasses it (`CafeAIApp.cacheable`), so sharing between identities is
safe. That rule now has one more case: **any input that depends on the identity must bypass the
cache.** Any later feature that adds per-identity input to a prompt call (tools called with the
user's credentials, a per-user system prompt) must make the call bypass the cache.

### 7.7 Guardrail and security events
`SecurityEvent`s and guardrail outcomes carry the identity, so a blocked prompt is attributable.

## 8. Outbound credentials

### 8.1 Requirement: one shared client per provider, credential per request

**This is a requirement, not an option.** A provider's LangChain4j client stays shared and cached
as it is today. The credential is resolved **per request** from the current identity.

A client cache keyed by identity is rejected:
- **Tokens rotate, identities don't.** A client cached for one person holds a token that expires
  while the client lives on, so the cache key would have to be the token, which means a new
  client at every refresh.
- **It grows with the user count.** Every LangChain4j client creates its own HTTP client with its
  own connection pool. A thousand active users and two providers means two thousand pools, plus
  the eviction and cleanup that brings.
- **It weakens revocation.** A revoked person's credential sits in a long-lived object until
  something evicts it.

The mechanism exists in LangChain4j 1.20, the version we're on: the OpenAI and Anthropic
builders both take `customHeaders(Supplier<Map<String, String>>)`. Checked in the 1.20 sources:

- **The supplier is called on every request.** `DefaultOpenAiClient.buildRequestHeaders()` calls
  it for each chat, streaming, completion and embedding request. `DefaultAnthropicClient` does
  the same.
- **Supplied headers replace the defaults.** Headers are a map where the last value wins, and the
  supplied ones are applied after the client's defaults. The map is **case-sensitive**, though:
  the header must be spelled exactly `Authorization`, or both credentials are sent. With no API
  key set, the OpenAI client sends no default `Authorization` at all.
- **Anthropic always sends `x-api-key`** with the builder's key. A per-request credential for an
  Anthropic-protocol endpoint overrides that exact header name, and the builder still needs a key
  value at build time.
- **The supplier runs in the call that builds the request**, on the caller's thread, so
  `Identity.current()` sees the request's identity. **Still to test:** that this holds for
  streaming calls.

No HTTP-client wrapper is needed. `httpClientBuilder(HttpClientBuilder)` remains available if a
future provider needs one.

A provider whose credential can't be expressed as a header (a request signed as a whole, for
example) may cache per identity, as a documented exception for that provider only.

### 8.2 `Credentials`
The seam covers **any** outgoing call made on someone's behalf: model endpoints, agent tools,
MCP servers, downstream APIs. It isn't specific to models.

| Strategy | Standard | Use |
|---|---|---|
| `staticKey(...)` | — | Today's behaviour; single-user mode |
| `clientCredentials(...)` | Client credentials grant | The app calls as itself, e.g. background work |
| `tokenExchange(audience)` | RFC 8693 | The app swaps the user's token for one scoped to the target and marked as acting for that user. The traceable option. |

Passing the user's own token on to another service is **not** offered. OAuth's security guidance
discourages it, and the MCP authorization spec forbids it. Token exchange covers the legitimate
case.

Token exchange is a standard, but an optional one. Not every issuer implements it, and the docs
must say so. Where it's missing, `clientCredentials` plus the audit record (§7.3) is the fallback:
the call is made as the app, and the audit record ties it to the person.

Obtained tokens are cached per (identity, audience) until shortly before they expire. This
caches a short-lived string, not a client, so it doesn't conflict with §8.1.

### 8.3 Fail closed
Some work has no request behind it: agents running on, scheduled work, RAG ingestion, calls at
startup. There `Identity.current()` is empty. A call configured for per-user credentials
**refuses** to run without an identity. It never falls back to a static key, which would turn
anonymous calls into calls with the app's authority. Work that legitimately belongs to the app
uses `clientCredentials`, chosen explicitly.

### 8.4 Open models in a company
A shared open-model server (any OpenAI-compatible inference server) behind the same issuer is an
ordinary endpoint with `Credentials`. This is how open models become viable in a company: one
central server, not one per laptop.

## 9. Libraries

**Token validation uses Helidon's JWT/JWK library** (`io.helidon.security.jwt`: `SignedJwt`,
`JwkKeys`, and validators for expiry, issuer, audience and not-before). It's on the same release
train as the web server CafeAI runs on (4.5.5) and is tested with Helidon's native-image builds.
Validation is never hand-rolled.

**Helidon's security framework is not used** (`SecurityFeature`, providers, the OIDC provider):
- **It would be a second pipeline.** It makes its decisions on Helidon's routing, while CafeAI
  builds its own chain and only registers the result with Helidon (`CafeAIApp.buildRouting`).
  Authentication would happen outside the chain, with its own ordering and error handling, and
  couldn't be placed as a step in a route (ADR-009).
- **Its own model of the user.** Its `Subject` and `SecurityContext` would have to be translated
  into `Identity`, and Helidon types would leak into CafeAI's public API.
- **Its own configuration tree,** separate from `cafeai-config`.
- **It's server-side only.** It doesn't cover device grant or token exchange.
- **Its OIDC provider keeps tokens in the browser** (checked in the 4.5.5 sources,
  `OidcConfig` and `OidcCookieHandler`): the access token in a cookie confusingly named
  `JSESSIONID`, the ID token in `JSESSIONID_2`, the refresh token in `JSESSIONID_3`. They're
  encrypted and `HttpOnly` by default, but `Secure` is off by default. With no key configured
  it writes a random password to `.helidon-oidc-secret`, which only one instance can use. It has
  no server-side token store. So a long-lived refresh token goes to the browser, signing out
  can't revoke anything, and the server gets the tokens back only by decrypting cookies on each
  request. §6.2 keeps them server-side.

The OAuth flows themselves (code exchange, refresh, device grant, client credentials, token
exchange) are plain HTTP exchanges defined by the RFCs, built on CafeAI's HTTP stack. Their
security checks (`state`, `nonce`, PKCE, issuer and audience) are tested against both test
issuers (§12).

## 10. Security requirements

- **CSRF.** Browser sessions are cookies, which the browser sends automatically. Requests that
  change state need CSRF protection beyond `SameSite`.
- **Cookies** are `HttpOnly`, `Secure` and `SameSite` by default. Turning off `Secure` is a
  development-only setting.
- **WebSockets and long streams** are checked when they open, but can outlive the token. A
  WebSocket is closed (or asked to re-authenticate) when its identity expires. A stream carries
  the credential obtained when it started, and a new stream needs a valid identity.
- **Revocation.** Access tokens are short-lived and validated locally. Disabling someone at the
  issuer cuts them off within the token lifetime, and signing out ends the server-side session
  immediately.
- **Logs never contain tokens.** Tokens and session ids are redacted from logs, errors and audit
  records.

## 11. Compatibility

- **Identity is opt-in.** With no issuer configured, CafeAI behaves exactly as it does today:
  static key, no identity, memory keyed by the supplied id.
- **Once identity is on, it's enforced, not just available.** Memory binding (§7.4), permission
  filtering (§7.5), fail-closed credentials (§8.3) and the cache rule (§7.6) all apply
  automatically, and switching any of them off is explicit and logged at startup.

## 12. Development and testing

- **A fake issuer** that runs in-process: it publishes discovery and a JWK set and signs tokens
  with its own key. It serves unit tests and **local development**, so examples run without a real
  identity provider.
- **Keycloak for integration tests.** It's open source, a CNCF project, OpenID-certified, and
  supports token exchange and device grant, so every flow is tested against a real issuer.
  This is a test choice only; nothing in CafeAI depends on it.
- **Security tests:** expired, not-yet-valid, wrong-issuer, wrong-audience and unsigned tokens;
  a forged `state`; a replayed code; a conversation id belonging to someone else; a document
  outside the caller's groups; a call with no identity under per-user credentials.

## 13. Deferred

These can be added later without changing the design:
- Token introspection (RFC 7662), for opaque tokens or instant revocation.
- Logout initiated by the issuer (OpenID Connect back-channel logout).
- CLI sign-in through a browser on the same machine with a localhost redirect (RFC 8252).
- Protected resource metadata for ordinary routes, outside MCP.
- Trusting several issuers at once.

## 14. Open questions

1. **Quota semantics:** tokens, cost or both, and over which windows?
2. **RAG permission metadata:** groups only, or groups plus individual subjects, and where does
   it come from at ingestion?
3. **Which claim carries groups?** OpenID Connect doesn't standardise one. Make the claim name
   configurable, with no default?
4. **Audit sink:** OpenTelemetry only, or also a dedicated, append-only audit log?
5. **Order of work.** `Identity` + `bearer` + the fake issuer is the smallest useful slice. The
   streaming-thread test in §8.1 belongs to the first outbound work.
