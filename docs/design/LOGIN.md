# `cafeai login`: sign in to a model once, the way you sign in to `claude` or `copilot`

Status: design, for review. Nothing here is built yet.

## 1. The problem

CafeAI can be used in different ways, from a single desktop development server to a cloud-hosted,
multi-tenant server. For `cafeai login`, a developer can view CafeAI as another development tool
on their machine, in the same way as the AI CLIs and IDEs.

Today a CafeAI app reaches a model with an API key in an environment variable. That is fine on a personal machine
with a personal account. On a **work computer with the company's account** it often isn't
allowed: companies forbid long-lived keys, sign people in through their own identity provider
(SSO), and set policies on which tools and models may be used.

Developers there already sign in to their AI tools in the terminal: `claude` (via `/login`),
`copilot login`, `az login`, `aws sso login`, `gcloud auth login`. The aim is the same experience
for CafeAI:

```
cafeai login claude
```
```java
app.ai(Anthropic.of("claude-opus-5-5"));     // no key anywhere
```

Two questions stay separate throughout:

- **Who uses my app?** Alice, Bob, staff. That is `cafeai-identity` (done).
- **How does my app get to the model?** That is this document.

## 2. Principles

1. **Reuse the vendor's own sign-in; never build one.** The company's SSO, MFA and device rules
   run inside the vendor's sign-in (browser or device code). CafeAI never sees a password and
   never registers its own OAuth client with a vendor.
2. **Only sign-ins the vendor lets other apps use.** Anthropic forbids other apps from using a
   claude.ai subscription login, OpenAI's ChatGPT/Codex sign-in only works for Codex, and so on.
   Those are out (§6), however convenient.
3. **The company's rules win, and are explained.** A sign-in the company has blocked fails with a
   sentence a developer can act on ("your organisation hasn't enabled Copilot CLI; ask your GitHub
   admin"), not an HTTP status.
4. **Keys still have their place.** Open-weight model hosts (Kimi, DeepSeek) and personal
   accounts work with keys, and that is fine. `cafeai login` stores a pasted key once so it isn't
   in shell profiles or code.
5. **Nothing leaves the machine.** Credentials stay in the user's profile, read at call time.
6. **Resolved per call.** Every login becomes a `Credentials` (the per-request credential that
   already exists), so expiry and renewal work as they do now.

## 3. The experience

```
cafeai login <vendor>     # sign in (or store a key), once per machine
cafeai logout <vendor>    # forget it (and revoke, where the vendor can)
cafeai status             # what each vendor will use, whose account, until when
```

Shipped through the existing JBang catalog (`jbang login@akilisha/cafeai claude`), installable as
a plain `cafeai` command with `jbang app install`.

In code, a provider with no key finds the login by itself. Order, first wins:

1. `withCredentials(...)` in code;
2. the vendor's usual environment variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, ...);
3. what `cafeai login` recorded for that vendor.

`cafeai status` and the startup log say which one was used, never the secret itself.

## 4. Per vendor

| `cafeai login ...` | Runs / asks for | Company sign-in | At call time | Key? |
|---|---|---|---|---|
| `claude` | `ant auth login` (Anthropic's CLI) | Claude Console, through the company's SSO; picks org and workspace | the `ant` profile's access token, as `Authorization: Bearer` | no |
| `copilot` | `copilot login` | GitHub device code, through the company's SSO (EMU) | Copilot's Java SDK with the logged-in user | no |
| `azure` | `az login` | Entra ID | `az account get-access-token --scope ...` (Azure OpenAI, Claude in Foundry) | no |
| `aws` | `aws sso login` | IAM Identity Center | `aws configure export-credentials`, then our SigV4 (Bedrock) | no |
| `google` | `gcloud auth application-default login` | Google Workspace / workforce SSO | `gcloud auth application-default print-access-token` (Claude on Vertex) | no |
| `openai` | a pasted key | — (admins issue project keys) | the stored key | yes |
| `grok` | a pasted key | — (xAI's SSO covers its console, not the API) | the stored key | yes |
| `mistral`, `nova` | a pasted key | — | the stored key | yes |
| `kimi`, `deepseek` | a pasted key | — | the stored key | yes |

Notes:

- **Claude.** `ant auth login` is documented as *"intended for local development and scripting
  on your own machine"* and works even where the company forbids developers to create keys. The
  profile lives in `%APPDATA%\Anthropic` (Windows) or `~/.config/anthropic`, in a documented
  format that Anthropic's SDKs and Claude Code also read. The token is bound to one workspace, so
  its budget and rate limits apply.
- **Copilot.** The SDK starts the Copilot CLI itself and uses its stored login; usage counts
  against the company seat. It gives an agent *session* (model choice, its own tools, MCP), not a
  plain chat endpoint, so it is its own module, `cafeai-copilot`, rather than a `Credentials`.
  What the company controls: the "Copilot CLI" policy, seat assignment, enabled models, allowed
  MCP servers.
- **Cloud CLIs.** For companies that buy models through a cloud, the cloud's CLI login is the
  developer's SSO sign-in. Bedrock signing (SigV4), Entra tokens and Vertex's request form are
  already built; only reading the CLI's credentials is new.
- **Key vendors.** The key goes in a file in the user's profile (`~/.cafeai/credentials.json`,
  user-only permissions), like Claude Code's `.credentials.json`. `cafeai login kimi` opens the
  vendor's keys page and asks for the key.

## 5. Corporate restrictions and what the developer sees

| Situation | Message |
|---|---|
| Vendor CLI not installed | "`cafeai login claude` uses Anthropic's `ant` CLI. Install it: ..." |
| Not signed in, or the login expired | "Sign in again: `cafeai login claude`" |
| Copilot CLI policy off / no seat | "Your organisation hasn't enabled Copilot CLI for you; ask your GitHub admin" |
| Model not enabled for the org/workspace | the vendor's own reason, with the model id |
| Company forces a specific login method | the vendor CLI enforces it; we pass its message through |

## 6. Out of scope, on purpose

- **claude.ai subscription logins** (`claude` `/login` with Pro/Max/Team/Enterprise): Anthropic
  forbids other apps from using or storing them.
- **ChatGPT / Codex sign-in and Codex access tokens**: Codex only, not the OpenAI API.
- **Gemini** (decided 2026-10-10): not part of `cafeai login`. The `Gemini` provider keeps its
  `GEMINI_API_KEY`.
- **Our own OAuth clients at vendors**: would bypass the company's control over which apps are
  approved.

## 7. To verify before building

1. Whether `ant auth print-credentials --access-token` renews an expired profile token (so CafeAI
   never handles the refresh token), and its output format.
2. The Copilot Java SDK: Maven coordinates, minimum Java version, and whether a session can be
   driven like a chat call (send prompt, stream text, CafeAI's tools).
3. Whether a GitHub organisation can block `copilot login` itself, beyond the Copilot CLI policy.
4. How long each cloud CLI's token call takes, to size the cache (tokens are cached until shortly
   before expiry either way).

## 8. Phases

1. **Key store and `cafeai login` for key vendors** (openai, grok, mistral, nova, kimi, deepseek), plus the
   lookup order in §3 for every provider. Smallest, and sets the command's shape.
2. **Claude via `ant auth login`.**
3. **Cloud CLIs:** azure, aws, google.
4. **`cafeai-copilot`**, after the spike in §7.2.

Each phase ends with a demo a developer can run on their own machine, like the identity demos.
