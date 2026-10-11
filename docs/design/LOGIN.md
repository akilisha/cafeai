# `cafeai login`: sign in to a model once, the way you sign in to `claude` or `az`

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
   sentence a developer can act on ("your Claude workspace doesn't allow this model; ask your
   Console admin"), not an HTTP status.
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

The command is `io.cafeai.core.login.CafeLogin`. Once the release is on Maven Central it is
shipped through the JBang catalog (`jbang cafeai@akilisha/cafeai login openai`), installable as
a plain `cafeai` command with `jbang app install --name cafeai cafeai@akilisha/cafeai`.

In code, a provider with no key finds the login by itself. Order, first wins:

1. `withCredentials(...)` in code;
2. the vendor's usual environment variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, ...);
3. what `cafeai login` recorded for that vendor.

`cafeai status` and the startup log say which one was used, never the secret itself.

## 4. Per vendor

| `cafeai login ...` | Runs / asks for | Company sign-in | At call time | Key? |
|---|---|---|---|---|
| `claude` | `ant auth login` (Anthropic's CLI) | Claude Console, through the company's SSO; picks org and workspace | the profile's access token, as `Authorization: Bearer`, renewed in-process (§7.1) | no |
| `azure` | `az login` | Entra ID | `az account get-access-token --scope ...`, cached until shortly before expiry (Azure OpenAI, Claude in Foundry) | no |
| `aws` | `aws sso login` | IAM Identity Center | `aws configure export-credentials`, cached until shortly before expiry, then our SigV4 (Bedrock) | no |
| `google` | `gcloud auth application-default login` | Google Workspace / workforce SSO | the Application Default Credentials file, renewed in-process (§7.2; Claude on Vertex) | no |
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
- **Cloud CLIs.** For companies that buy models through a cloud, the cloud's CLI login is the
  developer's SSO sign-in. Bedrock signing (SigV4), Entra tokens and Vertex's request form are
  already built; only reading the CLI's credentials is new.
- **Key vendors.** The key goes in a file in the user's profile (`~/.cafeai/credentials.json`,
  user-only permissions), like Claude Code's `.credentials.json`. `cafeai login kimi` shows the
  vendor's keys page and asks for the key. Endpoints with no factory of their own (Kimi,
  DeepSeek) use `Credentials.saved("kimi")`, which does the same lookup on every call.

## 5. Corporate restrictions and what the developer sees

| Situation | Message |
|---|---|
| Vendor CLI not installed | "`cafeai login claude` uses Anthropic's `ant` CLI. Install it: ..." |
| `ant` on the PATH is Apache Ant | "The `ant` found at <path> is Apache Ant, not Anthropic's CLI. Install Anthropic's, or set `cafeai.login.ant`" |
| Not signed in, or the login expired | "Sign in again: `cafeai login claude`" |
| Model not enabled for the org/workspace | the vendor's own reason, with the model id |
| Company forces a specific login method | the vendor CLI enforces it; we pass its message through |

## 6. Out of scope, on purpose

- **claude.ai subscription logins** (`claude` `/login` with Pro/Max/Team/Enterprise): Anthropic
  forbids other apps from using or storing them.
- **ChatGPT / Codex sign-in and Codex access tokens**: Codex only, not the OpenAI API.
- **Gemini** (decided 2026-10-10): not part of `cafeai login`. The `Gemini` provider keeps its
  `GEMINI_API_KEY`.
- **Copilot** (decided 2026-10-10): not part of `cafeai login`. For the record, its Java SDK
  was `com.github:copilot-sdk-java:1.0.14-preview.1`, a preview that runs a Copilot agent
  session rather than a model call.
- **Our own OAuth clients at vendors**: would bypass the company's control over which apps are
  approved.

## 7. Checks (done 2026-10-10)

### 7.1 Does `ant` renew an expired token? Yes, but CafeAI shouldn't call it on every renewal

From the `ant` source (v1.40.0, `pkg/cmd/cmd_auth.go`): `ant auth print-credentials
--access-token` renews the token when it has 120 seconds or less left, writes the new one back to
the profile, and prints only the token. If renewal fails it prints a warning on stderr and
**still prints the old token**, so a caller must read stderr, not just stdout.

Anthropic's Java SDK does the same renewal itself (`UserOAuthCredentials`): it reads the profile,
redeems the refresh token at `/v1/oauth/token` with the profile's `client_id` and the
`anthropic-beta: oauth-2025-04-20` header, and writes the result back. Profiles are built to be
shared by Anthropic's CLI, SDKs and Claude Code. The SDK's resolver is `internal`, so CafeAI
can't call it.

Two problems with running `ant` at call time:

- **Name clash.** Anthropic's CLI is called `ant`, the same as **Apache Ant**, which many Java
  developers have on their PATH. `ant auth ...` would run the wrong program.
- **A process per renewal**, plus the stderr handling above.

**Decided:** `ant` only for `cafeai login claude` (checking that it is Anthropic's). At call
time, CafeAI reads the profile and renews the token in-process the way Anthropic's SDKs do: same
files, same endpoint, written back so `ant` and Claude Code see the new token. Windows release
builds of `ant` exist (`ant_<v>_windows_amd64.zip`), though the install docs only show macOS,
Linux and Go.

### 7.2 How long a cloud CLI takes to hand over a token

Measured here with `gcloud` (the only cloud CLI on this machine): `gcloud --version` takes **2.5
s**; `gcloud auth application-default print-access-token` with no credentials took **11 to 18 s**,
most of it probing for a Compute Engine metadata server. `az` is also a Python program, so similar
start-up times are likely. Tokens must be cached until shortly before expiry, never fetched per
call, and the first call after a renewal will be slow.

**Decided:** for Google, read the Application Default Credentials file that `gcloud auth
application-default login` writes and renew in-process, which is what every Google client
library does; `gcloud` only for the login. For Azure, call `az` (Azure's own SDK does the same in
`AzureCliCredential`) and cache. For AWS, call `aws configure export-credentials` and cache the
role credentials until their expiry.

## 8. Phases

1. ✅ **Key store and `cafeai login` for key vendors** (openai, grok, mistral, nova, kimi, deepseek), plus the
   lookup order in §3 for every provider. Smallest, and sets the command's shape.
2. ✅ **Claude via `ant auth login`.** Checked against Anthropic's real `ant` 1.40.0 with a local
   token endpoint (`AntInteropTest`, runs when `ANT_BIN` is set): each renews, the other picks up
   the new token without renewing again.
3. ✅ **Cloud CLIs:** azure, aws, google. Tested against fakes of the CLIs and of Google's token
   endpoint, plus a real process standing in for `az`; not yet against real cloud accounts.

Each phase ends with a demo a developer can run on their own machine, like the identity demos.
