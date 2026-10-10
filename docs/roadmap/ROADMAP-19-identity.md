# ROADMAP-19 — Identity (`cafeai-identity`)

> Gives every request a verified identity from any OpenID Connect issuer, carries it through the
> AI layer, and uses it for every outgoing call. The design, with its decisions and the reasons
> for them, is `docs/design/IDENTITY.md`; this file tracks the work.
>
> **Status (2026-10-10):** 🟡 Phases 0–11 done, phase 12 written and waiting to be released, phase 13 (the design's deferred items) under way: `Identity` in core, `Issuer`, `Auth.bearer`, `FakeIssuer`, `Auth.require`, usage per caller, audit records, conversations scoped to the caller, per-call model credentials (OAuth client credentials, token exchange), browser sign-in, terminal sign-in, RAG enforced by the store (PostgreSQL row-level security), the MCP endpoint protected, WebSockets that know their caller and close at expiry; every flow verified against Keycloak 26.4. No Kimi test has run yet: the comparison with a recorded `kimi login` needs a person to run `kimi login`, and the credential-header call needs a Kimi key (design §12).

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
| 7 | ✅ CLI helper: device authorization grant (RFC 8628) | sign-in from a terminal against Keycloak ✅; compared with a recorded `kimi login`: **not yet run** (needs a person to run `kimi login`) |
| 8 | ✅ RAG under the user's identity: stores read the caller (`Identity.current()`), a token-authenticated store exchanges for its own token; stores that can't enforce access are refused unless declared public; `PgVectorStoreAdapter` passes claims for row-level security | refusal at startup tested; row-level security enforced in PostgreSQL |
| 9 | ✅ MCP: `bearer` on the MCP endpoint plus protected resource metadata (RFC 9728) | an MCP client discovers the issuer and connects |
| 10 | ✅ WebSockets and long streams at token expiry | a WebSocket closes when its identity expires |
| 11 | ✅ Keycloak integration suite; Kimi credential-header test written | all flows against a real issuer ✅; the Kimi test (`KimiCredentialsTest`, OpenAI-compatible endpoint) is gated on `KIMI_API_KEY` and **has not run**; its Anthropic-protocol half waits on phase 13 |
| 12 | Developer guide ✅ (§32), README and SPEC ✅, example ✅ (`IdentityExample`), release: waiting for the go-ahead | published |
| 13 | The design's deferred items (§13), approved 2026-10-10. Done: refresh token revoked on sign-out (RFC 7009) ✅; one renewal per refresh token, never saved back stale (`Session.beforeSave`) ✅; `@Tool` objects over MCP see the caller (`helidon().scoped`) ✅; tool calls are audit records ✅; the provider's `Retry-After` on a `429` ✅; protected resource metadata for any API (RFC 9728) ✅; token introspection (RFC 7662) ✅; back-channel logout ✅; CLI sign-in through a local browser (RFC 8252) ✅; several issuers at once ✅; opt-in, redacted audit text with its own retention ✅; per-call credentials beyond OpenAI-compatible endpoints ✅ (Anthropic-compatible endpoints, Kimi and DeepSeek with keys, Entra on-behalf-of, Anthropic Workload Identity Federation, Claude in Amazon Bedrock with SigV4 and STS, Claude on Vertex AI with Google STS). To do: Copilot (an agent-runtime module over the Copilot SDK, undecided); verifying against providers other than Keycloak (Entra, Okta, Auth0) and against the real clouds, which needs accounts | each against the fake issuer, and Keycloak where it supports the flow |
