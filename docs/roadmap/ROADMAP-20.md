# ROADMAP-20 — `cafeai login`

> Sign in to a model once, the way developers sign in to `claude` or `az`, so CafeAI apps reach
> it without a key in code or a shell profile; on a work computer, through the company's own
> sign-in and within its rules. The design and its decisions are `docs/design/LOGIN.md`; this
> file tracks the work. Acceptance criteria per phase: **[MILESTONE-20](MILESTONE-20.md)**.
>
> **Status (2026-10-10):** 🟡 Built (phases 1–3), not yet validated against real accounts
> (phases 4–10). Every flow is tested against fakes; Anthropic's sign-in is also checked against
> Anthropic's real `ant` CLI with a local token endpoint. Nothing has signed in to a real Claude
> Console, Azure, AWS or Google Cloud account yet.

---

## Why validation gets its own phases

The code was written against vendors' documentation and source code, and tested against fakes
built from them. Fakes agree with that reading of the documentation; they can't show where the
documentation is wrong, out of date, or silent. A real account can. Each vendor is validated on
its own, so one missing account doesn't hold up the others, and each phase records what really
happened: the exact commands, outputs and error texts, kept in MILESTONE-20.

Two levels per cloud:

- **Sign-in only** (free, or close to it): the vendor's CLI signs in, and CafeAI reads and renews
  the real credential. This checks CafeAI's side.
- **End to end** (a card on file, small per-call costs, the cloud's approval for Claude): a real
  model call through CafeAI with that credential.

---

## Phases

| # | Phase | Needs | Status |
|---|---|---|---|
| 1 | Saved keys: `cafeai login <vendor>` for openai, grok, mistral, nova, kimi, deepseek; the lookup order; `Credentials.saved` | — | 🟢 `9a644aa` |
| 2 | Claude with no key: `cafeai login claude` through Anthropic's `ant auth login`; the profile renewed in-process | — | 🟢 `bc879a0` |
| 3 | The company's cloud: `cafeai login azure \| aws \| google`; `AzureCliCredentials`, `AwsCliCredentials`, `GoogleApplicationDefault` | — | 🟢 `f31fdf3` |
| 4 | Validate Google: ADC sign-in, then Claude on Vertex AI | a Google Cloud project (free for sign-in; billing and Claude enabled in Model Garden for a call) | ⚪ |
| 5 | Validate Claude Console: `ant auth login`, a real renewal, a real call | a Claude Console account with API credit (personal is fine; an organisation with SSO is better) | ⚪ |
| 6 | Validate Azure: `az login`, then Claude in Foundry and Azure OpenAI | an Azure account (free for sign-in; a Foundry resource with a Claude deployment for a call) | ⚪ |
| 7 | Validate AWS: `aws sso login`, then Claude in Bedrock | an AWS account with IAM Identity Center (free); Bedrock access to Claude for a call | ⚪ |
| 8 | Validate the saved-key vendors, one by one | a key from each: OpenAI, xAI, Mistral, Amazon Nova, Kimi, DeepSeek | ⚪ |
| 9 | Validate the company's restrictions: what a developer sees when the company says no | an organisation account on at least one vendor, with a restriction switched on | ⚪ |
| 10 | Validate on macOS and Linux | a Mac and a Linux machine (or WSL) | ⚪ |
| 11 | The `cafeai` command: from source now (`cafeai-cli`), shaped as the validations use it; then the JBang `cafeai` alias | the alias: 0.6.0 on Maven Central | 🟡 from source |

After phases 4–8 had run, the parts were reshaped (LOGIN.md §9): a provider pointed at a cloud
now uses that cloud's CLI sign-in by itself, the developer-machine credentials live in
`cafeai-core`, and the command in `cafeai-cli`, published.

Phases 4–10 are independent; do them in whatever order accounts become available. Phase 4 is the
cheapest start: `gcloud` is already installed on the development machine.

---

## What each validation proves

- **4 Google.** The ADC file's real format (including the `account` and `quota_project_id`
  fields CafeAI shows), the real token endpoint's answer, impersonation through the real IAM
  Credentials API, and whether Vertex AI accepts a person's token without a quota-project header.
- **5 Claude Console.** The real `ant auth login` flow and the profile it writes on Windows, a
  renewal at Anthropic's real token endpoint (the refresh grant with the `oauth-2025-04-20` beta),
  a Messages call with the sign-in's token, `ant` and CafeAI sharing the profile for real, and
  how long the token lives.
- **6 Azure.** The real `az account get-access-token` output (`expires_on` as a number or a
  string), the Foundry and Azure OpenAI scopes accepted by the real services, and `az`'s real
  start-up time on Windows (it is a `.cmd`).
- **7 AWS.** The real `aws configure export-credentials` output, an expired SSO session's real
  error text, the profile's region, and a SigV4-signed Bedrock call accepted by AWS.
- **8 Saved keys.** Each vendor's endpoint and request format with a real key: CafeAI's
  `ProviderLiveSuite` for Grok, Mistral and Nova (written, never run with real keys),
  `KimiCredentialsTest`, and DeepSeek and Kimi through `Credentials.saved`.
- **9 Restrictions.** The real refusals behind the messages in LOGIN.md §5: a Console
  organisation that forbids API keys, a model not enabled for a workspace, an Azure Conditional
  Access block, an AWS permission set without Bedrock. CafeAI's messages are checked against the
  real texts and fixed where they mislead.
- **10 macOS and Linux.** The POSIX parts never run on the development machine: owner-only file
  permissions (`SavedKeysTest` skips them on Windows), `~/.config/anthropic` and XDG paths,
  `ant` from Homebrew, Apache Ant from a package manager on the PATH.
- **11 The command.** Every validation runs the real `cafeai` command, installed from source
  (`./gradlew :cafeai-cli:install`), so it is shaped by real use before it ships; then
  `jbang app install --name cafeai cafeai@akilisha/cafeai` gives the same command to everyone
  (distribution.md step 6).
