# MILESTONE-20 — `cafeai login`

**Current Status:** 🟡 Built and tested against fakes (phases 1–3); validation against real
accounts not started (phases 4–11). Plan and reasons: **[ROADMAP-20](ROADMAP-20-login.md)**.
Design: `docs/design/LOGIN.md`.

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Saved keys and the lookup order | 🟢 `9a644aa` |
| 2 | Claude with no key (`ant auth login`) | 🟢 `bc879a0` |
| 3 | The company's cloud (az, aws, gcloud) | 🟢 `f31fdf3` |
| 4 | Validate Google | ⚪ needs a Google Cloud project |
| 5 | Validate Claude Console | ⚪ needs a Claude Console account |
| 6 | Validate Azure | ⚪ needs an Azure account |
| 7 | Validate AWS | ⚪ needs an AWS account |
| 8 | Validate the saved-key vendors | ⚪ needs a key per vendor |
| 9 | Validate the company's restrictions | ⚪ needs an organisation account |
| 10 | Validate on macOS and Linux | ⚪ needs the machines |
| 11 | Ship the JBang `cafeai` command | ⚪ needs 0.6.0 on Maven Central |

Each validation phase records, under **Results**, the date, the CLI version, what was run, what
it printed (secrets removed) and every difference from the fakes, with the fix's commit. A phase
is done when every box is ticked or explicitly waived with a reason.

---

## Phase 1 — Saved keys &nbsp;🟢

- [x] `cafeai login | logout <vendor>`, `cafeai status`; key hidden at a terminal, piped otherwise
- [x] `~/.cafeai/credentials.json`, owner-only on POSIX, `CAFEAI_CONFIG_DIR`
- [x] Lookup order: `withCredentials`, the vendor's environment variable, the saved key
- [x] `Credentials.saved(vendor)` per call, for Kimi and DeepSeek
- [x] Tests: `SavedKeysTest`, `CafeLoginTest`; mutation-checked

## Phase 2 — Claude with no key &nbsp;🟢

- [x] `cafeai login claude` checks for Anthropic's `ant` (not Apache Ant) and runs `ant auth login`
- [x] The profile read as Anthropic's SDKs read it; renewed in-process and written back
- [x] Never sent to a host other than Anthropic's
- [x] Tests: `AnthropicProfileTest`, `CafeLoginTest`; `AntInteropTest` with the real `ant` 1.40.0 and a local token endpoint

## Phase 3 — The company's cloud &nbsp;🟢

- [x] `cafeai login | logout azure | aws | google`, CLI options passed through, status per cloud
- [x] `AzureCliCredentials`, `AwsCredentials.fromCli`, `GoogleApplicationDefault`
- [x] Tests: `CliCredentialsTest` (with a real stand-in process for `az`), `CloudLoginTest`; mutation-checked

---

## Phase 4 — Validate Google &nbsp;⚪

**Needs:** a Google Cloud project. Sign-in only: free. A call: billing on, Claude enabled for the
project in Vertex AI Model Garden.

### Sign-in only
- [ ] `cafeai login google` runs `gcloud auth application-default login` and the browser sign-in completes
- [ ] `cafeai status` shows the real ADC file's type and account (or record that `account` is absent)
- [ ] `GoogleApplicationDefault.credentials().token()` gets a token from the real token endpoint
- [ ] The token is kept: a second `token()` makes no request
- [ ] After `gcloud auth application-default revoke`, the error says to sign in again
- [ ] With `--impersonate-service-account`, the token is the service account's (needs a service account and the Token Creator role)

### End to end
- [ ] A Claude call through `Anthropic.onVertex(...)` with `GoogleApplicationDefault` returns an answer
- [ ] A streamed call works (`:streamRawPredict`)
- [ ] Record whether Vertex AI wants an `x-goog-user-project` header with a person's token; add it if so

### Results
*(none yet)*

---

## Phase 5 — Validate Claude Console &nbsp;⚪

**Needs:** a Claude Console account (platform.claude.com) with API credit. A personal account
checks the flow; an organisation with SSO checks the company path. A claude.ai subscription is not
enough.

- [ ] `cafeai login claude` with the real `ant` on Windows: browser, sign-in, workspace picked
- [ ] `cafeai status` shows organisation, email, workspace and expiry from the real file
- [ ] `Anthropic.of(model)` with no `ANTHROPIC_API_KEY` answers a real call with the sign-in's token
- [ ] A real renewal: once the token is near expiry, CafeAI renews it at Anthropic; record the token's lifetime
- [ ] `ant auth print-credentials --access-token` afterwards prints CafeAI's renewed token without renewing again
- [ ] `ant auth status` reads the file CafeAI wrote back without complaint
- [ ] `cafeai logout claude` then a call: the message says to sign in again
- [ ] With an organisation and SSO: the sign-in goes through the company's identity provider

### Results
*(none yet)*

---

## Phase 6 — Validate Azure &nbsp;⚪

**Needs:** an Azure account. Sign-in only: free. A call: a Foundry resource with a Claude
deployment; Azure OpenAI with a model deployment for the second scope.

### Sign-in only
- [ ] `cafeai login azure` runs `az login`; `cafeai status` shows user, subscription and tenant
- [ ] `AzureCliCredentials.scope(FOUNDRY).token()` returns a token; record the real `expires_on` type (number or string)
- [ ] The same for `AZURE_OPENAI`
- [ ] The token is kept: a second `token()` doesn't run `az`; record how long `az` takes to start
- [ ] After `az logout`, the error says to sign in again

### End to end
- [ ] Claude in Foundry answers a call with `AzureCliCredentials.scope(FOUNDRY)`
- [ ] Azure OpenAI answers a call with `AzureCliCredentials.scope(AZURE_OPENAI)`

### Results
*(none yet)*

---

## Phase 7 — Validate AWS &nbsp;⚪

**Needs:** an AWS account with IAM Identity Center (free) and a permission set. A call: Bedrock
access to Claude in one region.

### Sign-in only
- [ ] `cafeai login aws --profile <p>` runs `aws sso login`; `cafeai status` shows the credentials' expiry
- [ ] `AwsCredentials.fromCli("<p>")` gets credentials from the real `aws configure export-credentials`
- [ ] The region is read from the profile when not given
- [ ] After the SSO session expires (or `aws sso logout`), the error says to sign in again; record the real text

### End to end
- [ ] Claude in Bedrock answers a SigV4-signed call with `AwsCredentials.fromCli`
- [ ] A streamed call works

### Results
*(none yet)*

---

## Phase 8 — Validate the saved-key vendors &nbsp;⚪

**Needs:** a key per vendor, each tested on its own. Small per-call costs; Amazon Nova's API has
a free tier.

For each of **OpenAI, xAI (Grok), Mistral, Amazon Nova, Kimi, DeepSeek**:

- [ ] `cafeai login <vendor>` saves the key; `cafeai status` shows it as saved
- [ ] A call with the saved key and no environment variable answers
- [ ] With the environment variable set too, the variable's key is used
- [ ] `cafeai logout <vendor>`, then a call: the message names both ways to provide a key

Plus:
- [ ] `./gradlew :cafeai-core:liveTest` passes for Grok, Mistral and Nova (`GROK_LIVE_MODEL` etc.)
- [ ] Kimi and DeepSeek through `Credentials.saved(...)` on their Anthropic- and OpenAI-compatible endpoints
- [ ] `KimiCredentialsTest` (cafeai-identity) runs with `KIMI_API_KEY` and `KIMI_MODEL`

### Results
*(none yet)*

---

## Phase 9 — Validate the company's restrictions &nbsp;⚪

**Needs:** an organisation account on at least one vendor where an admin can switch a
restriction on. Each case is checked on its own; the real refusal is recorded and CafeAI's
message compared with it.

- [ ] Claude Console organisation that doesn't let developers create keys: `cafeai login claude` still works
- [ ] A model not enabled for the workspace: the message names the model and passes on the vendor's reason
- [ ] Azure Conditional Access blocking the sign-in or the token: `az`'s refusal comes through
- [ ] AWS permission set without Bedrock: the call's refusal is clear
- [ ] Google organisation policy restricting Vertex AI or impersonation: the refusal is clear
- [ ] Every message that misleads is fixed, with the commit noted here

### Results
*(none yet)*

---

## Phase 10 — Validate on macOS and Linux &nbsp;⚪

- [ ] `SavedKeysTest`'s permission test passes (owner-only file and directory)
- [ ] The whole core and identity suites pass
- [ ] `AntInteropTest` with `ANT_BIN` set to `ant` from Homebrew (macOS) or the Linux release
- [ ] Anthropic's profile found in `~/.config/anthropic`, and under `$XDG_CONFIG_HOME` when set
- [ ] With Apache Ant first on the PATH, `cafeai login claude` names it and stops
- [ ] gcloud's ADC found in `~/.config/gcloud`

### Results
*(none yet)*

---

## Phase 11 — Ship the JBang `cafeai` command &nbsp;⚪

**Needs:** a `cafeai-core` with `CafeLogin` on Maven Central (0.6.0).

- [ ] `jbang/cafeai.java` and the `cafeai` catalog alias added (distribution.md step 6)
- [ ] `jbang app install --name cafeai cafeai@akilisha/cafeai` installs it on Windows, macOS and Linux
- [ ] `cafeai status` works from the installed command
- [ ] README and GETTING-STARTED show `cafeai login`
