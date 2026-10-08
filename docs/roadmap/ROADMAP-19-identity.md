# ROADMAP-19 — Identity (`cafeai-identity`)

> Gives every request a verified identity from any OpenID Connect issuer, carries it through the
> AI layer, and uses it for every outgoing call. The design, with its decisions and the reasons
> for them, is `docs/design/IDENTITY.md`; this file tracks the work.
>
> **Status (2026-10-07):** 🟡 Phases 0–6 done: `Identity` in core, `Issuer`, `Auth.bearer`, `FakeIssuer`, `Auth.require`, usage per caller, audit records, conversations scoped to the caller, per-call model credentials (OAuth client credentials, token exchange), browser sign-in. The Keycloak half of phase 6's gate runs with phase 11.

---

## Why this exists

CafeAI was built only for API-key access to models, and companies ban static API keys for
security reasons. As it stands, CafeAI itself is unusable anywhere with access requirements: it
works on a solo developer's laptop and nowhere else. It has no notion of identity: every request
reaches the model under one static key, nothing knows who is calling, and conversation memory is
keyed by an id the client supplies.

This roadmap adds that capability, so CafeAI can be used in collaborative, shared environments
through acceptable access (anything other than API keys), built on open standards (OpenID
Connect, OAuth 2.0).

---

## Phases

Each phase ends usable and tested. Identity is opt-in throughout: with no issuer configured,
CafeAI behaves exactly as before.

| # | Phase | Gate |
|---|---|---|
| 0 | ✅ Skeleton: `cafeai-identity` in the build, this file | compiles |
| 1 | ✅ `Identity` in core (`req.identity()`, `Identity.current()`), the fake issuer, `Issuer.discover`, `bearer(issuer)` validating with Helidon's JWT/JWK library | a token from the fake issuer reaches a handler as an `Identity`; expired, not-yet-valid, wrong-issuer, wrong-audience, unsigned and wrongly-signed tokens are rejected with RFC 6750 responses; key rotation handled |
| 2 | ✅ `require(...)` over the RFC 9068 claims | scope and role checks on routes |
| 3 | ✅ Identity in the AI layer: usage per identity, audit records with OpenTelemetry end-user attributes plus a pluggable sink, identity on guardrail and security events | `app.usage()` reports per identity; audit holds metadata only by default |
| 4 | ✅ Conversation memory bound to issuer + subject; `RequestScope` carries the request onto other threads | another identity naming the conversation gets its own, never the first one's |
| 5 | ✅ Outbound `Credentials` (`staticKey`, `clientCredentials`, `tokenExchange`) through LangChain4j's per-request header supplier; `OpenAI.withBaseUrl`; fail closed with no identity; `429`s passed through | per-request header verified for chat and streaming (including the streaming-thread test); one shared client per provider |
| 6 | ✅ Browser sign-in: code flow + PKCE, tokens held server-side in the session, refresh, sign-out, CSRF protection | full sign-in against the fake issuer and Keycloak |
| 7 | CLI helper: device authorization grant (RFC 8628) | sign-in from a terminal against Keycloak; compared with a recorded `kimi login` |
| 8 | RAG under the user's identity: the store receives the exchanged token; stores that can't enforce access are refused unless declared public; `PgVectorStoreAdapter` passes claims for row-level security | refusal at startup tested; row-level security enforced in PostgreSQL |
| 9 | MCP: `bearer` on the MCP endpoint plus protected resource metadata (RFC 9728) | an MCP client discovers the issuer and connects |
| 10 | WebSockets and long streams at token expiry | a WebSocket closes when its identity expires |
| 11 | Keycloak integration suite; Kimi credential-header test | all flows against a real issuer |
| 12 | Developer guide, examples, release | published |
