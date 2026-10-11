# MILESTONE-20 — `cafeai login`

**Current Status:** 🟡 Built and tested against fakes (phases 1–3); validation against real
accounts not started (phases 4–10); the `cafeai` command installs from source (phase 11). Plan and reasons: **[ROADMAP-20](ROADMAP-20-login.md)**.
Design: `docs/design/LOGIN.md`.

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Saved keys and the lookup order | 🟢 `9a644aa` |
| 2 | Claude with no key (`ant auth login`) | 🟢 `bc879a0` |
| 3 | The company's cloud (az, aws, gcloud) | 🟢 `f31fdf3` |
| 4 | Validate Google | 🟡 sign-in validated; impersonation and Vertex AI need a project |
| 5 | Validate Claude Console | 🟡 validated except company SSO; one display bug open |
| 6 | Validate Azure | 🟡 sign-in validated; model calls need a pay-as-you-go subscription |
| 7 | Validate AWS | ⚪ needs an AWS account |
| 8 | Validate the saved-key vendors | ⚪ needs a key per vendor |
| 9 | Validate the company's restrictions | ⚪ needs an organisation account |
| 10 | Validate on macOS and Linux | ⚪ needs the machines |
| 11 | The `cafeai` command | 🟡 from source; the JBang alias needs 0.6.0 |

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

## Phase 4 — Validate Google &nbsp;🟡

**Needs:** a Google Cloud project. Sign-in only: free. A call: billing on, Claude enabled for the
project in Vertex AI Model Garden.

### Sign-in only
- [x] `cafeai login google` runs `gcloud auth application-default login` and the browser sign-in completes
- [x] `cafeai status` shows the real ADC file's type and account (or record that `account` is absent): `account` is there but empty
- [x] `GoogleApplicationDefault.credentials().token()` gets a token from the real token endpoint
- [x] The token is kept: a second `token()` makes no request
- [x] After `gcloud auth application-default revoke`, the error says to sign in again
- [ ] With `--impersonate-service-account`, the token is the service account's (needs a service account and the Token Creator role)

### End to end
- [ ] A Claude call through `Anthropic.onVertex(...)` with `GoogleApplicationDefault` returns an answer
- [ ] A streamed call works (`:streamRawPredict`)
- [x] Record whether Vertex AI wants an `x-goog-user-project` header with a person's token; add it if so: it doesn't

### Results

**2026-10-11, sign-in only.** Windows 11, gcloud 588.0.0, a personal Google account, no project
set. `cafeai` installed from source (`./gradlew :cafeai-cli:install`); `GoogleApplicationDefaultLiveTest`.

- `cafeai login google`: the browser sign-in completed; the file is
  `%APPDATA%\gcloud\application_default_credentials.json`, with `type: authorized_user`,
  `client_id`, `client_secret`, `refresh_token`, `universe_domain: googleapis.com`, and `account: ""`
  (empty, not absent). No `quota_project_id`, as no project was set. `cafeai status`:
  `application default credentials (authorized_user)`, so it can't say whose sign-in it is.
- `token()` (`GOOGLE_ADC_LIVE=1`): a token from `oauth2.googleapis.com/token`. Google's tokeninfo
  accepted it (HTTP 200): scopes `cloud-platform`, `userinfo.email`, `sqlservice.login`, `openid`,
  `email`; `expires_in: 3599`, so the 3600-second default matches. The second `token()` returned the same token.
- `cafeai logout google` (gcloud's revoke): the file is deleted and `cafeai status` says
  `not signed in (cafeai login google)`. Run without a terminal (the `!` prompt), gcloud's `Y/n`
  doesn't wait: it takes the default, Y, and revokes.
- The copy taken before revoking (`GOOGLE_ADC_REVOKED_FILE`): Google answers `invalid_grant`, and
  CafeAI says: *The Google sign-in has expired or was revoked. Sign in again: `cafeai login google`
  (gcloud auth application-default login): Token has been expired or revoked.*
- No difference from the fakes needed a code fix. Open: whether `cafeai status` should show the
  account (the file doesn't hold it; tokeninfo returns the email, at the cost of two requests).
- Running a live test again with other variables needs `--rerun`: Gradle doesn't count
  environment variables as test inputs.

**2026-10-11, Claude on Vertex AI, a new project.** The same personal account, signed in again;
a new project, upgraded from the free trial to a paid account (Claude can't be enabled on a
trial), the Agent Platform API (`aiplatform.googleapis.com`, Vertex AI's new name) enabled, and
Claude Sonnet and Haiku enabled in Model Garden. No quota project set.

- Every call reached the model's quota check, so the sign-in's token, the
  `publishers/anthropic/models/<model>:rawPredict` URL and the reshaped body were all accepted,
  with no `x-goog-user-project` header: the quota is charged to the project in the URL.
- `global`, `claude-haiku-4-5`: HTTP 429, *Quota exceeded for
  aiplatform.googleapis.com/global_online_prediction_requests_per_base_model with base model:
  anthropic-claude-haiku-4-5*.
- `us-east5` and `europe-west1`, `claude-sonnet-5-5`: HTTP 429, quota exceeded for
  `online_prediction_input_tokens_per_minute_per_base_model`.
- `us-east5` and `europe-west1`, `claude-haiku-4-5`: HTTP 404, *Publisher model ... was not found
  or your project does not have access to it*: not offered under that ID in those regions.
- A new project has no Claude quota, and Google refused the increase: *If this is a new project
  please wait 48h until you resubmit the request or until your Billing account has additional
  history.* The plain and streamed calls wait for a project with quota.

---

## Phase 5 — Validate Claude Console &nbsp;🟡

**Needs:** a Claude Console account (platform.claude.com) with API credit. A personal account
checks the flow; an organisation with SSO checks the company path. A claude.ai subscription is not
enough.

- [x] `cafeai login claude` with the real `ant` on Windows: browser, sign-in, workspace picked
- [x] `cafeai status` shows organisation, email, workspace and expiry from the real file
- [x] `Anthropic.of(model)` with no `ANTHROPIC_API_KEY` answers a real call with the sign-in's token
- [x] A real renewal: once the token is near expiry, CafeAI renews it at Anthropic; record the token's lifetime: 8 hours
- [x] `ant auth print-credentials --access-token` afterwards prints CafeAI's renewed token without renewing again
- [x] `ant auth status` reads the file CafeAI wrote back without complaint
- [x] `cafeai logout claude` then a call: the message says to sign in again
- [ ] A name with characters outside the console's code page shows right (found here: `Rah?s`)
- [ ] With an organisation and SSO: the sign-in goes through the company's identity provider

### Results

**2026-10-11.** Windows 11, `ant` 1.40.0, a personal Claude Console account (an individual
organisation, workspace "Default"). `cafeai` installed from source; `AnthropicSignInLiveTest`
(`./gradlew :cafeai-core:liveTest`, `ANTHROPIC_SIGNIN_LIVE_MODEL=claude-haiku-4-5`, no `ANTHROPIC_API_KEY`).

- `cafeai login claude`, with `ant` on the PATH, in a Windows terminal: `ant`'s browser sign-in,
  its own summary (organisation, workspace, *set as active profile*, the two files), then
  CafeAI's line: *Signed in to Claude: <organisation>, as <email>, workspace Default (Anthropic
  profile 'default').* The workspace was picked without a question (the account has one).
- Without `ant` on the PATH, `cafeai login claude` prints the install choices (brew, the GitHub
  releases, `go install`) and exits 1.
- The profile `ant` wrote: `configs/default.json` has `authentication {type: user_oauth,
  client_id}`, plus `organization_id` and `workspace_id` at the top level (no `base_url`);
  `credentials/default.json` has `version`, `type: oauth_token`, `access_token`, `expires_at`,
  `refresh_token`, `scope` (`user:developer user:inference user:profile`), `organization_uuid`,
  `organization_name`, `account_email`, `workspace_id`, `workspace_name`.
- `cafeai status`: *signed in: <organisation>, as <email>, workspace Default (profile 'default',
  token valid until ...)*.
- A real call with only the sign-in answered.
- `ANTHROPIC_SIGNIN_RENEW=1`: with the stored token marked as expiring in a minute, CafeAI renewed
  it at `api.anthropic.com/v1/oauth/token`. Anthropic returned a new access token and a new
  refresh token (refresh tokens rotate), lifetime 28800s (8 hours); every field `ant` wrote was kept.
- Afterwards `ant auth print-credentials --access-token` printed CafeAI's renewed token (same
  hash) without renewing again or rewriting the file, and `ant auth status` showed the profile
  valid for 8h.
- `cafeai logout claude`: *Removed the Claude sign-in (Anthropic profile 'default') from this
  machine, as `ant auth logout` does.* The credentials file is gone, the config stays; `cafeai
  status` says *not signed in (cafeai login claude)*, and a call fails with *Not signed in to Claude
  (Anthropic profile 'default' has no credentials). Run `cafeai login claude`.*
- **Bug:** the organisation's name has a curly apostrophe (U+2019). In the terminal `ant` shows
  `Rah’s`, and CafeAI's line right after it shows `Rah?s`: Java encodes output in the console's
  code page, which has no `’`; `ant` writes Unicode to the console. (Piped, Java writes
  Windows-1252.)
- No difference from the fakes needed a code fix.

---

## Phase 6 — Validate Azure &nbsp;🟡

**Needs:** an Azure account. Sign-in only: free. A call: a Foundry resource with a Claude
deployment; Azure OpenAI with a model deployment for the second scope.

### Sign-in only
- [x] `cafeai login azure` runs `az login`; `cafeai status` shows user, subscription and tenant
- [x] `AzureCliCredentials.scope(FOUNDRY).token()` returns a token; record the real `expires_on` type (number or string): a number
- [x] The same for `AZURE_OPENAI`
- [x] The token is kept: a second `token()` doesn't run `az`; record how long `az` takes to start: about 0.65s
- [x] After `az logout`, the error says to sign in again

### End to end
- [ ] Claude in Foundry answers a call with `AzureCliCredentials.scope(FOUNDRY)`
- [ ] Azure OpenAI answers a call with `AzureCliCredentials.scope(AZURE_OPENAI)`

### Results

**2026-10-11.** Windows 11, Azure CLI 2.91.0, a personal Azure account. `cafeai` installed from
source; `AzureCliCredentialsLiveTest` (`AZURE_CLI_LIVE=1`).

- Without `az`, `cafeai login azure` says where to install it and exits 1; `cafeai status` says
  `az not installed`.
- With no subscription, `az login` signs in to the browser, then saves nothing (`az account list`
  is empty). `--allow-no-subscriptions` (passed through by `cafeai login azure`) reaches az 2.91.0's
  new tenant and subscription picker, which crashes on a directory with no name:
  `AttributeError: 'NoneType' object has no attribute 'get'` in `_subscription_selector.py`. CafeAI
  then says ``cafeai: `az login` failed (exit 1).`` (`AZURE_CORE_LOGIN_EXPERIENCE_V2=off` skips the
  picker.) The same from PowerShell (`cafeai.bat`) and Git Bash (`cafeai`).
- In PowerShell, `~/.cafeai/cli/bin/cafeai` without `.bat` names the bash script, which Windows
  runs outside the terminal: the browser sign-in happens, but nothing is shown. On the PATH, plain
  `cafeai` finds `cafeai.bat`.
- With a subscription: `az login`'s picker, then CafeAI's line *azure: signed in as <email>,
  subscription <name> (tenant <id>)*.
- `az account get-access-token` answers `accessToken`, `expiresOn`, `expires_on`, `subscription`,
  `tenant`, `tokenType`; `expires_on` is a JSON number (epoch seconds).
- `AzureCliCredentials.scope(FOUNDRY)`: audience `https://ai.azure.com`; `scope(AZURE_OPENAI)`:
  audience `https://cognitiveservices.azure.com`. Lifetimes 5550s and 4201s (Entra varies them,
  about 60 to 90 minutes). The first `token()` took about 0.65s (az answers from its own token
  cache); the second 0 to 1 ms.
- `cafeai logout azure`: `cafeai status` says *not signed in (cafeai login azure)*, and a token
  request fails with *Not signed in to Azure, or the sign-in has expired. Sign in: `cafeai login
  azure` (az login). Azure CLI said: ERROR: Please run 'az login' to setup account.*
- No difference from the fakes needed a code fix.
- Model calls: Microsoft's documentation says Claude in Foundry needs a paid subscription with a
  pay-as-you-go billing method; free trial, student and credit-only subscriptions are refused.

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

## Phase 11 — The `cafeai` command &nbsp;🟡

**From source** (the validations use it, and shape it):
- [x] `cafeai-cli` module: `./gradlew :cafeai-cli:install` puts `cafeai` and `cafeai.bat` in `~/.cafeai/cli/bin`
- [x] Runs on the JDK it was built with, whatever `JAVA_HOME` says (`CAFEAI_JAVA_HOME` overrides); found because this machine's `JAVA_HOME` is Java 21
- [x] Left out of the BOM; not published
- [ ] Changes the validations call for, each noted here

**The release** (needs a `cafeai-core` with `CafeLogin` on Maven Central, 0.6.0):

- [ ] `jbang/cafeai.java` and the `cafeai` catalog alias added (distribution.md step 6)
- [ ] `jbang app install --name cafeai cafeai@akilisha/cafeai` installs it on Windows, macOS and Linux
- [ ] `cafeai status` works from the installed command
- [ ] README and GETTING-STARTED show `cafeai login`
