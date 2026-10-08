# acme-desk

A team knowledge desk where **what you can read decides what the AI tells you**. Identity
end to end, with no API key anywhere:

- **People sign in in the browser** (authorization code + PKCE, `Auth.login`) and chat over a
  **WebSocket that runs as them**.
- **RAG under PostgreSQL row-level security.** CafeAI holds no permissions. On every query it
  hands PostgreSQL the verified caller's claims (`request.jwt.claims`), and a policy owned by
  the database decides which chunks that caller can retrieve.
- **The model is called on each caller's behalf.** The caller's token is exchanged
  (RFC 8693) for one issued for `model-gateway`, then sent to Ollama's OpenAI-compatible
  endpoint. A real gateway would check it; Ollama ignores it.
- **The same desk is an MCP tool**, `ask_desk`, for AI agents with their own identity. It is
  protected per the MCP authorization spec: RFC 9728 metadata, and a 401 with
  `resource_metadata`.

| Document | Readable by |
|---|---|
| `q3-finance-report` | group `finance` |
| `staff-handbook` | groups `staff`, `finance` |
| `public-faq` | anyone signed in |

| Who | How they reach the desk | Groups |
|---|---|---|
| alice / alice | browser sign-in, WebSocket chat | finance |
| bob / bob | browser sign-in, WebSocket chat | staff |
| `desk-agent` | client-credentials token, MCP | staff |

## Run it

You need Docker and a local Ollama with `llama3.2` (`ollama pull llama3.2`).

```bash
docker compose -f capstones/acme-desk/docker-compose.yml up -d   # Keycloak :8280, pgvector :5433
./gradlew :capstones:acme-desk:demo     # the whole story, no browser
./gradlew :capstones:acme-desk:run      # or open http://localhost:8090 and sign in
```

To use another model, set `OLLAMA_MODEL`. To use another issuer, set `ISSUER`.

## What the demo shows

```
== alice, signed in through Keycloak, chatting over the WebSocket
  Q: What was Q3 revenue?
  A: According to the Acme Q3 finance report, Q3 revenue was 12.4 million euros, up 8 percent on Q2.
     sources retrieved for Alice Liddell: ["public-faq","q3-finance-report","staff-handbook"]

== bob, signed in through Keycloak, chatting over the WebSocket
  Q: What was Q3 revenue?
  A: I don't have that information. ...
     sources retrieved for Bob Builder: ["public-faq","staff-handbook"]

== an AI agent (desk-agent, staff), over MCP with its own Keycloak token
  Q: What was Q3 revenue?
  A: I don't have information on Q3 revenue. ...
     sources retrieved for service-account-desk-agent: ["public-faq","staff-handbook"]
```

The question, the app, the retriever and the prompt are the same for all three callers. Bob
and the agent don't get the finance report because PostgreSQL never returns its chunks to
them. The prompt has no instruction about it.

## Where each piece is

| Concern | Code |
|---|---|
| Who is calling (browser) | `Middleware.session(...)` + `Auth.login(issuer, "desk-web", ...)` |
| Who is calling (agent) | `Auth.bearer(issuer, "desk-api", BASE + "/mcp").optional()` |
| WebSocket as the caller | `app.ws("/ws", ...)`: `session.identity()`, closed with 1008 if absent |
| RAG per caller | `PgVectorConfig...rowLevelSecurity(true)`, app connects as `desk_app` |
| The policy | `read_by_group` in `DeskApp.prepareDocuments`: the database owner's part |
| Model on the caller's behalf | `OAuthCredentials.tokenExchange(issuer, "desk-api", ..., "model-gateway")` |
| MCP tool + its protection | `app.mcp().tool("ask_desk", ...)` + `Auth.mcp(app, issuer, BASE + "/mcp")` |

`db/init.sql` creates the role the app connects as. It is not a superuser, has no
`BYPASSRLS`, and doesn't own the chunk table, so the policy applies to it.
`prepareDocuments` ingests the documents and creates the policy as the table owner. That is
the database owner's job; it runs at start-up here only so the capstone needs one command.

Development only: plain http, demo passwords, client secrets in the realm file.
