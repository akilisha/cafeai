# Identity: CafeAI beyond the API key

**Status:** Design complete; being built (ROADMAP-19)
**Date:** 7 October 2026
**Module:** `cafeai-identity` (new), plus changes to `cafeai-core`

---

## 1. The problem

CafeAI was built for one way of accessing models: an API key. Companies ban static API keys
for security reasons, so as it stands **CafeAI itself is unusable in a company**, however good
its other features are. It works on a solo developer's laptop and nowhere that has access
requirements.

The cause is that CafeAI has no notion of identity at all. It serves many concurrent requests,
but every one of them reaches the model under a single credential: whoever's API key is in the
environment.

- Providers read a static key when their client is built
  (`.apiKey(resolveApiKey("OPENAI_API_KEY", provider))` in `LangchainBridge`), and the client is
  cached per provider (`LangchainBridge.modelFor`, a `Map<AiProvider, ChatModel>`).
- Core has no sign-in. `Attributes.AUTH_PRINCIPAL` is an untyped placeholder that nothing sets.
- `app.usage()` counts per route, not per person.
- Conversation memory is keyed by an id the client supplies (`.session(req.header("X-Session-Id"))`).

The two ways CafeAI reaches a model today are both unacceptable in a shared environment:

| Today | Why it is unacceptable |
|---|---|
| A static API key | Banned: no accountability or traceability per person, and no way to revoke one person's access |
| A local open model per user | Large memory per user; viable only centrally, behind the same sign-in as everything else |

**The goal is to make CafeAI usable beyond a solo developer's laptop:** in collaborative, shared
environments whose access requirements it meets, which means access through anything other than
API keys. This design gives each request a verified identity, carries it through the AI layer,
and uses it for every outgoing call.

## 2. Goals and non-goals

**Goals**
- Every request has a verified identity, whether it came from a browser, a CLI or another service.
- Every model call, tool call, usage count, guardrail event and audit record is
  attributable to that identity.
- Access is revoked centrally: disabling someone at the issuer cuts them off everywhere.
- Built only on open standards. Anything that implements them works.

**Non-goals**
- CafeAI is never an identity provider. It stores no users and no passwords.
- **All access policy is external.** Roles live in the user's token, and data access is enforced
  by the service that holds the data. CafeAI carries identity faithfully; it doesn't decide who
  may see what.
- No quotas. CafeAI is not the custodian or provider of any model; it reflects what the API
  exposes (§7.2).
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
| JWT access token profile (RFC 9068) | Where roles are in a token: `scope`, `groups`, `roles`, `entitlements` (§6.3) |
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
              (memory bound to identity, retrieval under the user's identity)
                                          │
              outgoing call ──> [ credentials: per request ] ──> model / tool / MCP server
                                          │
              usage · audit, keyed by issuer + subject
```

Browser, CLI and service sign-in are the same thing from the app's side: three ways of getting a
token from the same issuer. The app only ever validates tokens, so there is **one** inbound
mechanism, not three.

## 5. `Identity` (core)

A small, immutable type in `cafeai-core`:

- `issuer`, `subject`: **together they are the key.** A subject is only unique within its issuer,
  so usage, memory and audit all use the pair.
- `name`, `expiresAt`, all `claims`, and the RFC 9068 `scope`, `groups`, `roles` and
  `entitlements` (§6.3).
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
- **Protected resource metadata (RFC 9728), opt-in:** `.resourceMetadata(app, resourceUri,
  scopes...)` serves the API's metadata at `/.well-known/oauth-protected-resource` plus the
  resource's path, outside CafeAI's filters (a client needs it before it has a token), and every
  `401` and `403` challenge, from `bearer`, `require` and `signedIn`, names it in
  `resource_metadata`. The same document `Auth.mcp` serves for the MCP endpoint (§6.5).
- **Several issuers, opt-in:** `.or(issuer, audiences...)` trusts another issuer, with its
  own audiences: employees through one identity provider and partners through another, or the
  old and the new one during a move. A JWT goes to the issuer its `iss` names (read unverified,
  only to choose; that issuer then checks everything, signature first), and a token naming
  none of them is refused. An opaque token goes to each issuer that does introspection, until one
  says it's active. Callers stay apart because an identity is issuer and subject together. The
  resource metadata names every issuer.
- **Token introspection (RFC 7662), opt-in:** `.introspect(clientId, clientSecret)` also asks
  the issuer's `introspection_endpoint` about each token, as a confidential client. A JWT that
  passed the local checks is still refused once the issuer says it's no longer active, so a
  revoked token, or one whose holder was disabled, stops working at once rather than at its
  expiry. An opaque token (not a JWT) is accepted on the issuer's word: its answer must be
  active, name this issuer if it names one, this service among its audiences, a subject and an
  expiry ahead, and the caller is built from it. Any other local failure is final, and the
  issuer isn't asked. Answers are cached by the token's SHA-256 (30 seconds by default,
  `.introspectionCache(...)`, never past the token's expiry), which is how late a revocation can
  be noticed. An issuer that can't be asked gets `503`. Tested against the fake issuer and
  against Keycloak, whose revoked token is refused before it expires.

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

As built, `Auth.login(issuer, clientId, clientSecret, redirectUri)`:
- **Serves three paths:** `GET /auth/login?return=/path` starts sign-in; the redirect URI's
  path finishes it; `POST /auth/logout` signs out. `return` must be a path on this site, and
  anything else returns to `/`, so there is no open redirect.
- **Renews each refresh token once.** A session is loaded when a request starts and saved when
  its response is sent, so two requests of one session can hold the same refresh token, and a
  slow request can save tokens older than another's renewal. Where the issuer rotates refresh
  tokens (each is good once), either would sign the session out. So the first request to need
  a renewal makes it and the others holding that token wait for its result, and a request's
  copy is brought up to date before it's saved (`Session.beforeSave`, new in core). Results
  are kept ten minutes, enough for the requests in flight during a renewal.
- **Signs out in three steps:** the session goes; its refresh token is revoked at the issuer's
  `revocation_endpoint` (RFC 7009), so a copy of it can't renew anything; and the browser is
  sent to the issuer's `end_session_endpoint`, which ends the issuer's own session. Each step
  the issuer doesn't publish an endpoint for is skipped, and a failed revocation is logged
  without stopping the sign-out.
- **Signed out by the issuer, opt-in (OpenID Connect Back-Channel Logout 1.0):**
  `.backChannelLogout(app)` serves `POST /auth/backchannel-logout`, the URL registered at the
  issuer, at the Helidon level (the issuer brings no cookie, CSRF token or body parser). When
  the person signs out at the issuer or in another app, or an administrator ends their session,
  the issuer posts a signed `logout_token`. It is checked like any token from the issuer
  (signature, algorithm, `iss`), then: `aud` is this client, the back-channel event is there,
  there is no `nonce` (an ID token can't pass for one), `iat` is within ten minutes, `exp` (if
  any) is ahead, and its `jti` hasn't been seen. CafeAI doesn't reach into the session store:
  each session records the issuer's `sid`, the subject and when it signed in, and is signed out
  the next time it's used. A `sid` ends that session; a `sub` alone ends every session of that
  person signed in before the logout. Logouts are remembered for a day, in this process: with
  several instances, only the one the issuer reached knows. Tested against the fake issuer
  and against Keycloak, whose admin-ended session ends the app's.
- **Several side by side**, one per issuer ("sign in with Entra", "sign in with Okta"), each on
  its own paths and redirect URI. A session records the issuer it signed in with, and only that
  issuer's middleware renews it, checks its CSRF token and signs it out; the others let it pass.
  `signInRequired()` can only send people to its own issuer and can't know the others' paths, so
  it goes on the one registered last, or routes use `Auth.signedIn()` and the app offers a page
  to choose from.
- **Starts a new session at sign-in.** `session.regenerate()`, new in core, moves the session
  to a new id and destroys the old one, so an id planted or seen before sign-in never becomes
  signed in (session fixation). A cookie-only session can't be regenerated, so sign-in refuses
  to start without a server-side store.
- **The caller's identity comes from the validated ID token** (issuer, audience = this client,
  signature, expiry, nonce), and the access token is kept for token exchange (§8.2).
- **CSRF:** a signed-in session's `POST`, `PUT`, `PATCH` and `DELETE` must carry the session's
  synchronizer token in `X-CSRF-Token` or a `_csrf` form field (`Auth.csrfToken(req)`). A
  request with an `Authorization` header is left to `Auth.bearer`, since it isn't
  authenticated by cookie.
- **`signInRequired()`:** a browser navigation is sent to sign in and back; other requests get
  `401`.
- **Limits:**
  - Renewals of one session are shared across this process only. Several instances behind a
    shared session store can still exchange the same refresh token twice; where the issuer
    rotates refresh tokens, the later exchange fails and that session is signed out. Route a
    session's requests to one instance (sticky sessions), or have the issuer not rotate.
  - The session cookie's `Secure` flag is the session middleware's setting, off by default for
    local development; production must turn it on.

### 6.3 `require(...)`: authorization
Checks a scope or role carried in the token. It's ordinary middleware, so it goes on a route, a
router or a filter.

The policy stays external: the issuer decides what a token carries, and `require` only reads it,
as any OAuth-protected API does. It reads the RFC 9068 claims (`scope`, `groups`, `roles`,
`entitlements`) and nothing else, so there is no claim-name setting. An issuer that puts roles
elsewhere maps them to these claims in its own configuration.

### 6.4 CLI client helper
Device authorization grant: the CLI shows a code and a link, and the user signs in in any browser
on any device. This also works over SSH with no local browser. Tokens are cached for the user and
refreshed. It's for any Java tool or JBang script that calls a CafeAI service.

As built, `DeviceLogin.of(issuer, clientId).scope(...).accessToken()`:
- **A public client** (no secret: a program on someone's machine can't keep one), naming itself
  with `client_id` (RFC 6749 2.3.1).
- **Polls as RFC 8628 3.5 says:** at the issuer's interval; `authorization_pending` keeps
  waiting, `slow_down` adds five seconds to every later wait, and `access_denied` and
  `expired_token` end it. It also stops by itself when the code's lifetime runs out.
- **Signs in once:** tokens are cached in `~/.cafeai/tokens/` (one file per issuer, client and
  scope, with a hashed name), written to a temporary file and moved into place, and readable by
  the owner only where the file system has permissions. On Windows the user's profile directory
  is already private, so the owner-only test is skipped there. An expired token is renewed with
  the refresh token, and a sign-in that can't be renewed asks the user again.
- **`signOut()` revokes, then forgets:** the refresh token is revoked at the issuer (RFC 7009)
  and the file deleted. It returns what happened: `REVOKED`, `FORGOTTEN` (deleted here only,
  because the issuer has no revocation endpoint or couldn't be reached) or `NOT_SIGNED_IN`. It
  doesn't end the user's session in their browser. That session is shared by every app they
  use, and a CLI has no business ending it; the next sign-in may be approved by it without a
  password, so signing in as someone else takes a private window.
- **The prompt is pluggable** (`onPrompt`): by default two lines on standard error, with
  `verification_uri_complete` too when the issuer gives one.

Through the browser on the same machine (OAuth 2.0 for Native Apps, RFC 8252), as built,
`LoopbackLogin.of(issuer, clientId).scope(...).accessToken()`: no code to type, as `gh` and `az`
do on a desktop.
- **The authorization code flow with PKCE (`S256`) and `state`, as a public client.** The
  program listens on `127.0.0.1` (the IP literal, not `localhost`, RFC 8252 8.3) on a port of
  its own choosing, only until the sign-in completes, and opens the system browser at the
  issuer's sign-in page with `redirect_uri=http://127.0.0.1:<port>/callback`. The issuer must
  accept any port on the registered `http://127.0.0.1/callback` (RFC 8252 7.3); Keycloak does.
- **Only this sign-in's `state` counts:** any other request that reaches the port is answered
  `400` and changes nothing, so a page that guesses the port can't plant a code. A refusal at
  the issuer fails the sign-in; nothing back within five minutes (`timeout(...)`) times it out.
- **The browser gets a page saying it can be closed**, and the program the code, which it
  exchanges with its PKCE verifier.
- **Where no browser can be opened**, the link is printed; over SSH or in a container, use
  `DeviceLogin`. `onOpen(...)` replaces opening the browser.
- **Caching, renewal and `signOut()` are `DeviceLogin`'s** (`TokenCache`), in the same file for
  the same issuer, client and scope: a CLI can offer both and sign out of either.
- Tested against the fake issuer and against Keycloak's own login page. Keycloak's
  `pkce.code.challenge.method` client setting is left unset: set, it requires PKCE on the
  device authorization request too, which breaks the device grant for the same client.

### 6.5 MCP
The MCP authorization spec uses OAuth 2.1 and requires protected resource metadata (RFC 9728).
`cafeai-mcp` gets both from this module: `bearer(issuer)` protects the MCP endpoint, and the
metadata tells MCP clients which issuer to sign in with.

As built, `Auth.mcp(app, issuer, "https://orders.example.com/mcp").scope(...)`:
- **Found while building:** the MCP endpoint is mounted outside CafeAI's filter chain, so
  `app.filter(Auth.bearer(...))` never covered it. Route tools happened to stay safe, since they
  forward the caller's token to a route that checks it, but `@Tool` objects could be listed and
  called with no credential. `Auth.mcp` guards the endpoint itself, at the Helidon level.
- **Audience-bound tokens:** a token's `aud` must be the endpoint's own URL, as the MCP
  specification requires, so a token issued for another service can't be replayed here. A
  route behind `Auth.bearer` that route tools call must accept that URL as an audience too.
- **Discovery:** `GET /.well-known/oauth-protected-resource/mcp` serves the RFC 9728 metadata
  (resource, issuer, scopes), and every refusal points at it:
  `401` with `WWW-Authenticate: Bearer resource_metadata="..."`, or `403` with
  `insufficient_scope`.
- **Enforced:** an app that serves verified callers refuses to start with its MCP endpoint
  unprotected, or protected at a different path than the one it is mounted on.
- **`@Tool` objects see the caller.** The endpoint is mounted with `helidon().scoped(path)`:
  off CafeAI's filters, but each request still runs as a CafeAI request. After validating the
  token, `Auth.mcp` puts the caller and their token on it, as `Auth.bearer` does on a route,
  so a `@Tool` method finds the caller in `Identity.current()`, and a model call it makes can
  use token exchange on their behalf.

## 7. Identity in the AI layer

### 7.1 Usage
`app.usage()` reports per caller (`callers()`, `caller(key)`) as well as per route. Each model
call records the identity of the request it was made for, including calls a request makes on
another thread, such as a stream. Anonymous calls, and calls with no request behind them, count
in the route totals only.

### 7.2 Limits: passed through, not imposed
CafeAI imposes no quotas. Limits belong to whoever provides the model or the data. When an API or
gateway enforces one, the caller gets a `429`, not a `500` that hides it. CafeAI retries only if
the app asked it to (`app.retry(...)`).

The provider's `Retry-After` goes with the `429`. LangChain4j's rate-limit exception doesn't
carry the response's headers, so CafeAI builds the OpenAI-compatible, Anthropic and Ollama
models on LangChain4j's JDK HTTP client over one of its own: it passes every call through, and
on a `429` keeps the `Retry-After` on the request the call was made for (taken when the call
starts, so streams are covered). Only delay-seconds or an HTTP date (RFC 9110) is passed on.
Gemini's and Jlama's models are built otherwise and don't carry it.

### 7.3 Audit
`app.audit(sink)` receives an `AuditEvent` for:
- **every model call** (`ModelCall`): caller, route, model, tokens, cost and time. Each round
  trip of an agent's tool loop is a model call;
- **every tool call** (`ToolCall`): caller, route, tool, who called it (an MCP client, or one of
  the app's agents), whether it failed, and how long it ran. Recorded for `@Tool` objects and
  route tools served over MCP, and for the tools of agents built with `app.agent(...)` or
  `CafeAgentic.agentBuilder(...)`. Its arguments and result are not recorded;
- **every guardrail flag** (`GuardrailFlag`): caller, route, guardrail, what it was screening
  (request, response or retrieved document) and what it did (block, warn or log). This covers
  both the engine's guardrails and guardrails used as middleware.

Several sinks may be registered. Each is called synchronously and must be quick, and a sink that
throws is logged and skipped.

**Audit records hold metadata only.** They never contain prompt, answer or document text, tool
arguments or results, nor a guardrail's reason, which can quote what was flagged. They name people, so they are personal
data, and what is kept and for how long is the sink's decision.

**Capturing text, opt-in,** for settings that must keep what was asked and answered:
`app.auditText(TextCapture.to(sink).keepFor(Duration.ofDays(90)))`.
- **Separate:** each model call's `Transcript` (caller, route, model, the caller's last message,
  the answer) goes to its own `TranscriptSink`, never to `app.audit(...)` sinks, which stay
  metadata only. The text can be kept elsewhere, for less time, by fewer people.
- **Redacted** before it leaves CafeAI: credentials, then personal data, each replaced by its kind
  (`[EMAIL]`, `[AWS_ACCESS_KEY_ID]`), by default (`Redactor.standard()`, over the same
  `SensitivePatterns` the PII and secrets guardrails use); `redactWith(...)` adds an
  organisation's own terms. Retrieved documents and system prompts are not captured.
- **Kept for a time the app must state** (`keepFor`, no default), carried on each record as
  `keepUntil`. `TranscriptSink.jsonLines(dir)` enforces it: records are filed by the day they
  expire (`until-2026-11-09.jsonl`), and a day's file is deleted once that day has passed.
- Captured at the same point as usage: every model call, plain or streamed, including agents'.
  A sink that fails is logged and skipped; it never fails the call.

OpenTelemetry gets the caller on **spans only**: `enduser.id` (the subject) and
`cafeai.enduser.issuer`. Never on metrics, where one series per person would make them grow
without bound.

### 7.4 Conversation memory: bound to identity
Without identity, the memory key is whatever id the client sends, so anyone with someone's id
reads and continues their conversation. With a verified caller, the key is **scoped to issuer +
subject**: `ConversationKeys.forCurrentCaller(id)` gives `cafeai-identity:<hash of issuer and
subject>:<id>`.

- **Isolated, not refused.** Another caller sending the same id gets a conversation of their own,
  and never reads or continues the first. Refusing instead would need a record of who owns each
  id, and its answer would tell a caller that someone else's conversation exists.
- **No personal data in storage keys:** the scope is a hash, with a separator between issuer and
  subject so neither can be shifted into the other.
- **Anonymous callers** keep the id as given, as before identity existed, but an id that looks
  like a scoped key is refused, so they can't name a caller's conversation.
- **Everywhere memory is keyed:** prompts, streams, vision and audio in the engine (the key is
  computed on the request's thread, since some flows save history from the provider's thread);
  stateful agents in `cafeai-aiservices`, whose cached agent instances are scoped the same way;
  and `CafeAgenticMemory`.

Work a handler hands to another thread loses the request, and so the caller. CafeAI's own streams
carry it. Anything else carries it with `RequestScope.wrap(task)` or
`RequestScope.carrying(executor)`, e.g. a parallel agentic workflow's
`.executor(RequestScope.carrying(...))`. A thread the request was not carried to fails closed
(§8.3): in an app that serves verified callers, conversation memory used with no request in scope
is refused with `IdentityRequiredException`, never keyed by the bare id.

### 7.5 RAG: enforced by the store, under the user's identity
Company documents are shared by permission. Without enforcement, RAG is a way to read any indexed
document through the model. **The store enforces, not CafeAI.** The user's roles are in their
token, the data service applies its own policies, and CafeAI stores no permission metadata.

- **Retrieval runs under the user's identity.** CafeAI obtains a token for the store through token
  exchange (§8.2), carrying the same user and roles, and queries with it. The user's own token is
  never passed on (§8.2).
- **A store that can't enforce access is refused.** With identity on, CafeAI won't start with a
  vector store that has no notion of users (such as `InMemoryVectorStore`), unless the app
  explicitly declares that store public, meaning every document in it may be read by anyone
  signed in.
- **Database concerns stay outside CafeAI's core.** Row-level access is validated by the database
  itself (as Supabase does with PostgreSQL row-level security over the caller's JWT claims), or
  by the `VectorStore` adapter wrapping the database client. CafeAI's core hands the store the
  caller's exchanged token on every query and does nothing else: no connection pools, no
  policies, no row filtering. How the adapter connects (a connection per credential, or a shared
  pool that sets the caller's claims on each connection for the database's policies) is the
  adapter's own decision, and each adapter documents it.
- **The built-in adapters follow the same rule.** `PgVectorStoreAdapter` (`cafeai-rag`) passes
  the caller's claims to PostgreSQL for row-level security policies written by the database's
  owner. `ChromaVectorStoreAdapter` has no per-row access control to hand them to, so with
  identity on it is refused unless declared public, like `InMemoryVectorStore`.

As built:
- **Stores find the caller themselves.** CafeAI passes no token or identity through the
  `VectorStore` interface: a store reads `Identity.current()` on the thread that searches. A
  store behind a token-authenticated service obtains a token for it with
  `OAuthCredentials.tokenExchange(...)`; PgVector over JDBC passes the caller's claims instead
  (the trust trade-off above: PostgreSQL relies on CafeAI's statement of who the caller is).
- **`VectorStore.access()`** is `UNENFORCED` (the default: the store can't tell callers apart),
  `PUBLIC` (wrapped by `VectorStore.everyoneMayRead(store)`) or `PER_CALLER`. Once identity mode
  is on, `listen()` refuses an `UNENFORCED` store.
- **PgVector with `PgVectorConfig.rowLevelSecurity(true)`:** every connection the pool hands
  out first sets `request.jwt.claims` to the caller's verified claims as JSON, the setting
  PostgREST and Supabase use, so their policy patterns apply as they are. It is empty with no
  caller. The setting is written on every hand-out and cleared on every return, so a pooled
  connection never carries one caller's claims into another's query.
- **The policy can stay entirely in the database.** Every chunk row carries its source in
  `metadata->>'sourceId'`, so a policy can join it to a table of who may read each source:

  ```sql
  CREATE POLICY read_by_group ON cafeai_chunks FOR SELECT USING (EXISTS (
    SELECT 1 FROM source_acl a
    WHERE a.source_id = cafeai_chunks.metadata->>'sourceId'
      AND a.grp IN (SELECT jsonb_array_elements_text(COALESCE(
            NULLIF(current_setting('request.jwt.claims', true), '')::jsonb -> 'groups', '[]'::jsonb)))));
  ```
- **No false promises.** At startup the adapter checks that PostgreSQL will really apply the
  policy to the connecting role: row-level security enabled on the table, and the role not a
  superuser, not `BYPASSRLS`, and not the owner of a table that isn't
  `FORCE ROW LEVEL SECURITY`. Otherwise it refuses to start.
- **Ingestion runs with no caller**, so its claims are empty. Ingest with a role allowed to
  insert (the table's owner, or a role with an `INSERT` policy): a policy for reads only blocks
  inserts by the app role.
- **Tested against PostgreSQL 16 with pgvector**, through a real app with a single pooled
  connection, so one caller's claims would show up in the next query if the reset failed.
  Mutation-checked: removing both resets makes that test fail.

### 7.6 Semantic cache: one rule to keep
ADR-013 already restricts the cache to answers that depend on nothing but the prompt. A call with
a conversation or with RAG bypasses it (`CafeAIApp.cacheable`), so sharing between identities is
safe. That rule now has one more case: **any input that depends on the identity must bypass the
cache.** Any later feature that adds per-identity input to a prompt call (tools called with the
user's credentials, a per-user system prompt) must make the call bypass the cache.

Per-caller credentials are such a case. With token exchange the model endpoint decides, per
caller, whether a call is allowed, so serving one caller another caller's cached answer would skip
that decision. A provider whose `Credentials.perCaller()` is true therefore never uses the cache.
The app's own credentials (client credentials, a static key) are the same for everyone and cache as
before.

### 7.7 Guardrail and security events
`SecurityEvent`s carry the caller (`caller()`), and guardrail outcomes are audit records (§7.3),
so a blocked prompt is attributable.

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
- **Anthropic always sends `x-api-key`** with the builder's key, and its client has no
  per-request header hook. So for Anthropic the credential is set one level down, in the HTTP
  client the model is built on (`ProviderHttp`, which also keeps a `429`'s `Retry-After`, §7.2):
  on every request, asked for on the calling thread, it removes whatever credential header the
  client set (`Authorization`, `x-api-key`, `api-key`) and sets the call's own. An API key
  (`Credentials.staticKey`) goes as `x-api-key`, the one header every Anthropic-compatible
  endpoint takes (DeepSeek's documents no other); a token goes as `Authorization: Bearer`, which
  the Claude API now prefers (`x-api-key` is its legacy fallback) and which Claude in Microsoft
  Foundry requires for Entra ID tokens. The builder gets a placeholder key that is never sent.
  `withBaseUrl` reaches any Anthropic-compatible endpoint, with or without `/v1` at the end.
- **The supplier runs in the call that builds the request**, on the caller's thread, so
  `Identity.current()` sees the request's identity. Verified for streaming calls too: a stream
  runs in its request's scope (§7.1), so a streamed call carries its own caller's token.

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
| `AnthropicFederation` | RFC 7523 JWT bearer grant at Anthropic | The Claude API with no API key: the app's token from its own issuer (or a workload token file) is exchanged for a short-lived Anthropic token acting as a service account. As the app, not a person. |
| `AwsCredentials` | AWS STS `AssumeRoleWithWebIdentity`, then Signature Version 4 on every request | Claude in Amazon Bedrock: temporary AWS credentials for an IAM role, in exchange for the caller's token (per person, the session named after them) or the app's. |
| `AzureCliCredentials`, `AwsCredentials.fromCli()`, `GoogleApplicationDefault` | The developer's own cloud CLI sign-in (`az`, `aws sso`, `gcloud auth application-default`) | Claude in Foundry, Bedrock or Vertex AI from a developer's machine, with no key; see `docs/design/LOGIN.md`. |
| `GoogleFederation` | Google STS token exchange (RFC 8693, Google's JSON form), optionally `generateAccessToken` for a service account | Claude on Vertex AI: a Google token for the app's token (workload pool) or the caller's (workforce pool, per person). |
| `onBehalfOf(scopes)` | Microsoft Entra ID's on-behalf-of (an RFC 7523 JWT bearer grant with `requested_token_use=on_behalf_of`) | The same, at Entra, which has no RFC 8693. |

Passing the user's own token on to another service is **not** offered. OAuth's security guidance
discourages it, and the MCP authorization spec forbids it. Token exchange covers the legitimate
case.

Token exchange is a standard, but an optional one. Not every issuer implements it, and the docs
must say so. **Microsoft Entra ID rejects it** (`AADSTS70003 unsupported_grant_type`) and offers
its on-behalf-of flow instead, which `onBehalfOf` speaks. The one rule bent for it (§2, open
standards only, decided 2026-10-10): its grant is a standard one (RFC 7523), with Microsoft's
`requested_token_use` parameter. Entra adds two conditions: the caller's token must have been
issued for the app redeeming it (`aud` is the app's client id), and a `scope` names the target
(`https://ai.azure.com/.default` for Claude in Microsoft Foundry). When Entra answers
`interaction_required` (a second factor, a changed policy), the call is refused with `401`, so the
client signs the person in again; passing Entra's claims challenge on to the client is not done. Where it's missing, `clientCredentials` plus the audit record (§7.3) is the fallback:
the call is made as the app, and the audit record ties it to the person.

Only the issuer that issued the caller's token can exchange it. A `tokenExchange` refuses a
caller from another issuer (a server error, since the app is misconfigured), so the token is never
sent to the wrong issuer. With several issuers, `OAuthCredentials.byIssuer(exchanges...)` picks
the exchange at the caller's issuer.

**Anthropic Workload Identity Federation** removes the last API key from the Claude API: the
app presents a JWT from the organisation's issuer (`IdentityToken.clientCredentials(issuer, ...)`,
or a token file a platform rotates), Anthropic checks it against a federation rule set up in the
Claude Console, and returns a short-lived token for one of the organisation's service accounts.
It acts as the app, so per-person accountability comes from the audit records. Each renewal
presents a new JWT, since Anthropic treats a token's `jti` as single-use; Anthropic's token is
renewed 120 seconds before it expires, as its SDKs do. Anthropic must be able to verify the
issuer's tokens: a public `https` issuer, or its keys uploaded to the Console for one that isn't.
Every refusal is the same opaque `401`; the reason is in the Console's authentication history.

**AWS signs, rather than carries, a credential.** Claude in Amazon Bedrock serves the Messages
API at `https://bedrock-mantle.<region>.api.aws/anthropic`, so the Anthropic provider reaches it,
but every request must carry a Signature Version 4 worked out over the request itself. That is
the per-provider exception §8.1 allows for: core's `SignedCredentials` signs instead of handing
out a token, and `ProviderHttp` reads the body, asks for the signature headers and sends those
very bytes, per request on the calling thread. `AwsSigV4` is written here, not taken from the
AWS SDK (decided 2026-10-10: a large dependency for one algorithm), and is checked against AWS's
own published SigV4 test suite. The key comes from STS `AssumeRoleWithWebIdentity`, which needs
no AWS credentials to ask: with the caller's own token (`assumeRoleAsCaller`, per person, their
subject naming the session so CloudTrail says who acted, never kept past their token) or the
app's (`assumeRole`). The role's trust policy names an IAM OIDC provider for the issuer.

**Claude on Google Cloud's Vertex AI** (decided 2026-10-10: Claude only, not Gemini) takes the
Messages API in its own form: the model in the URL (`.../publishers/anthropic/models/<model>:rawPredict`,
or `:streamRawPredict`) and `anthropic_version: vertex-2023-10-16` in the body.
`Anthropic.onVertex(model, project, location)` has `ProviderHttp` reshape each request for it,
and picks the location's host (global, `us`/`eu` multi-region, or a region). The token is
Google's: `GoogleFederation.workload(provider, identityToken)` exchanges the app's token at
Google's Security Token Service, then, with `serviceAccount(...)`, acts as a service account;
`GoogleFederation.workforce(provider)` exchanges the caller's own token, per person. Whether
Vertex AI accepts a workforce pool's tokens directly was not confirmed from Google's
documentation: workload federation with a service account is the established route. Google
fetches the issuer's discovery document itself, so the issuer must be reachable from the
internet.

Obtained tokens are cached per (identity, audience) until shortly before they expire. This
caches a short-lived string, not a client, so it doesn't conflict with §8.1.

### 8.3 Fail closed
Some work has no request behind it: agents running on, scheduled work, RAG ingestion, calls at
startup. There `Identity.current()` is empty. A call configured for per-user credentials
**refuses** to run without an identity. It never falls back to a static key, which would turn
anonymous calls into calls with the app's authority. Work that legitimately belongs to the app
uses `clientCredentials`, chosen explicitly.

As built:
- A per-caller credential with no verified caller throws `IdentityRequiredException`, and a
  request answers it with `401` and `WWW-Authenticate: Bearer`. The model is never reached.
- `IdentityMode`: creating `Auth.bearer` marks the JVM as serving verified callers. From then
  on, caller-scoped work with no request in scope (conversation memory, so far) is refused
  instead of running unscoped. This is JVM-wide, like the rest of an app's process-global
  wiring.

### 8.4 Open models in a company
A shared open-model server (any OpenAI-compatible inference server) behind the same issuer is an
ordinary endpoint with `Credentials`. This is how open models become viable in a company: one
central server, not one per laptop.

**This needs a base URL on the OpenAI provider, which it doesn't have today.** `OpenAI.of(modelId)`
holds only the model id, temperature, max tokens and timeout, and `Nvidia` is OpenAI-compatible
but fixed to NVIDIA's URL. The provider gains `withBaseUrl(url)`, an immutable copy like
`withTemperature`:

```java
app.ai(OpenAI.of("<model-id>")
        .withBaseUrl("https://models.internal.example.com/v1")
        .credentials(Credentials.tokenExchange("model-server")));
```

One generic provider then reaches every OpenAI-compatible endpoint: a company's own model
server, a gateway, and hosted open-weight models (Kimi, Qwen, DeepSeek, GLM, MiniMax all offer an
OpenAI-compatible API). **There is no provider class per vendor.** It would add maintenance and
nothing else, for the same reason providers take a model id string instead of named model
constants.

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

  As built:
  - The app's filters (`Auth.bearer`, browser sign-in) run on the WebSocket upgrade request,
    so an upgrade without a valid identity is refused like any request. Verified, since it is
    Helidon's routing that decides it.
  - Helidon calls `onHttpUpgrade` inside the filters, while the request is in scope. The scope
    is taken there, but only if it belongs to the very request being upgraded, and every
    callback of that connection runs in it. So `WsSession.identity()` and `Identity.current()`
    name the caller, and conversation memory, usage, audit records and per-caller model
    credentials work inside WebSocket handlers.
  - When the identity expires, the connection is closed with `1008` (policy violation), by a
    timer, or at the next message if that comes first. The client signs in again and
    reconnects; there is no re-authentication over an open socket.
  - Fixed on the way: `WsSession.isOpen()` always returned `true`.
- **Revocation.** Access tokens are short-lived and validated locally. Disabling someone at the
  issuer cuts them off within the token lifetime, and signing out ends the server-side session
  immediately. Where that lifetime is too long, introspection (§6.1) cuts them off within its
  cache time instead (30 seconds by default).
- **Logs never contain tokens.** Tokens and session ids are redacted from logs, errors and audit
  records.

## 11. Compatibility

- **Identity is opt-in.** With no issuer configured, CafeAI behaves exactly as it does today:
  static key, no identity, memory keyed by the supplied id.
- **Once identity is on, it's enforced, not just available.** Memory binding (§7.4), retrieval
  under the user's identity and the refusal of stores that can't enforce access (§7.5),
  fail-closed credentials (§8.3) and the cache rule (§7.6) all apply automatically. Switching any
  of them off is explicit and logged at startup.

## 12. Development and testing

- **A fake issuer** that runs in-process: it publishes discovery and a JWK set and signs tokens
  with its own key. It serves unit tests and **local development**, so examples run without a real
  identity provider.
- **Keycloak for integration tests.** It's open source, a CNCF project, OpenID-certified, and
  supports token exchange and device grant, so every flow is tested against a real issuer.
  This is a test choice only; nothing in CafeAI depends on it.
- **Kimi as a real-world reference.** Kimi Code CLI's terminal login (`kimi login`) is a public
  RFC 8628 device-code flow, and Kimi's API takes its OAuth token only as
  `Authorization: Bearer`, rejecting it as `x-api-key`. Kimi's terms reserve OAuth sign-in for
  its own CLI and IDE extension, forbid changing the client identifier, and expect third-party
  tools to use an API key. So CafeAI never signs in with Kimi's client id and never reads the
  token Kimi Code saves. Within those terms:
  1. **Device flow, compared against the reference.** Record a `kimi login` session run by a
     person: endpoints, polling interval, `slow_down` and expiry handling, refresh. Check that the
     §6.4 helper behaves the same against Keycloak.
  2. **Credential header.** Call Kimi with a Kimi platform API key through the per-request
     credential (§8.1). Check that the credential arrives in exactly the header Kimi expects, with
     no stray `x-api-key` next to it. This is the Anthropic-protocol case from §8.1.
  3. **End to end, later.** If Kimi opens OAuth client registration to third parties: terminal
     login, token, model call. Not allowed until then.
- **Security tests:** expired, not-yet-valid, wrong-issuer, wrong-audience and unsigned tokens;
  a forged `state`; a replayed code; a conversation id belonging to someone else; retrieval
  reaching the store under the caller's exchanged identity; startup with a store that can't
  enforce access; a call with no identity under per-user credentials.

As built:
- **Keycloak 26.4, in Docker, configured by an imported realm**
  (`src/test/resources/keycloak/cafeai-realm.json`), with no admin steps.
  `KeycloakIntegrationTest` passes for:
  - bearer validation, including groups and names from Keycloak's mappers, and `require`;
  - RFC 8693 token exchange, where the model endpoint gets a Keycloak-issued token naming the
    same user, for `model-server`, requested by `orders-api`;
  - client credentials;
  - the full browser sign-in through Keycloak's own login page, including token renewal and
    sign-out at Keycloak;
  - the device grant through Keycloak's own device and login pages.

  Every flow worked against Keycloak as built. The only problems were in the test client:
  - Java's `CookieManager` won't send `Secure` cookies over http, though browsers do on
    localhost; a small cookie jar stands in.
  - A device-page walk that silently missed a step left the CLI polling until the code expired
    (ten minutes by default). The realm now uses a 60-second code, and the test has its own
    timeout.
- **Not run against Keycloak:** the MCP endpoint. Its audience must be the endpoint's URL, which
  depends on the test's port, and the realm is fixed at import. MCP is tested against the fake
  issuer (§6.5).
- **Kimi:** `KimiCredentialsTest` makes a real chat call through `withBaseUrl` and a per-call
  credential when `KIMI_API_KEY` and `KIMI_MODEL` are set, and is skipped otherwise; it has not
  been run yet. It covers Kimi's OpenAI-compatible endpoint. The Anthropic-protocol case (item 2
  above) can now be run too (`Anthropic.withCredentials`), with a Kimi key. Comparing the device flow with a recorded
  `kimi login` (item 1) needs a person to run `kimi login`.

## 13. Deferred

These can be added later without changing the design:

## 14. Decisions and open questions

**Decided**
1. **No quotas.** CafeAI passes through the limits of whoever provides the model or data (§7.2).
2. **Document access is enforced by the store,** under the user's identity obtained through token
   exchange. Stores that can't enforce access are refused at startup unless declared public
   (§7.5).
3. **Roles come from the RFC 9068 claims.** `require` stays, as a check of what the token carries
   (§6.3).
4. **Audit:** OpenTelemetry, plus a pluggable sink (§7.3).
5. **Order of work:** `Identity` + `bearer` + the fake issuer first. The streaming-thread test in
   §8.1 belongs to the first outbound work.

6. **Database concerns stay outside CafeAI's core.** The database or the `VectorStore` adapter
   validates row-level access and chooses its connection model. CafeAI hands it the caller's
   exchanged token (§7.5).
7. **One generic OpenAI-compatible provider** with a base URL; no provider class per vendor
   (§8.4).

**Open**

None at present.
