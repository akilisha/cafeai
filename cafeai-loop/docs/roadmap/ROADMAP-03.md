# ROADMAP-03 — The module, its runtime, `init` and `plan`

## Phase 1 — The command and its tools

**Objective:** `cafeai-loop` runs as a CafeAI application, with the tools its agents act through.

**Acceptance criteria**
- **1.1** `./gradlew :cafeai-loop:install` installs a `cafeai` command with every command: the
  loop's, and `login`, `logout` and `status` from `cafeai-cli`; it replaces the install of
  `cafeai-cli` alone.
- **1.2** The file tools read, list and search inside the project, and write inside it on the
  working branch; no path outside the project can be read or written.
- **1.3** The git tools create a branch named by the project's conventions and commit to it; they
  never commit to the default branch, merge, or push to a protected branch.
- **1.4** Settings are read from a settings file at the project's root; when it is absent, or a
  dial isn't set, the documented defaults apply.

**Testing criteria:** each tool against a temporary project; escaping it refused in every form
(`..`, an absolute path, a symbolic link pointing out); the git tools in a temporary repository,
including a refused merge, a refused push and a refused commit to the default branch; each dial
set, unset, and malformed. Mutation-checked.

**Validation criteria:** install it; `cafeai help` and `cafeai status` work.

**Status:** `completed`

## Phase 2 — The agents and their prompts

**Objective:** each command is an agent of its own, with a versioned prompt.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** Each command's system prompt is a versioned resource of the module; every artifact a
  command writes records the version that wrote it.
- **2.2** Each command's agent is an `app.agent(...)`, with its own model when the settings name
  one (else one model for all), and only its own tools.
- **2.3** Models are reached through CafeAI's credential lookup (code, the vendor's variable,
  `cafeai login`); no key is needed when a sign-in exists.
- **2.4** Personal data is screened out of what is sent to a model.
- **2.5** Each command's model use and cost is recorded against the command, and can be shown.

**Testing criteria:** a stand-in model receives the right prompt and version; a command can't use
another command's tools; the credential order; screening on what is actually sent; usage credited
to the right command. Mutation-checked.

**Validation criteria:** the developer runs a command and reads what was sent and what it cost.

**Status:** `in-progress`

## Phase 3 — `cafeai init` from the terminal

**Objective:** `init` produces a project's design spec in conversation with its developer.

**Prerequisites:** phase 2.

**Acceptance criteria**
- **3.1** It notices an existing codebase, and asks permission before reading it, saying what will
  leave the machine; without a yes, it reads nothing.
- **3.2** It reads the structure, then the code one package at a time, then the git history, and
  reports what is built, its quirks, and the gaps the developer's ideas meet.
- **3.3** It asks numbered questions, each choice with a recommendation, and records the answers.
- **3.4** It writes `DESIGN_SPEC.md` at the project's root with its nine sections; anything the
  developer didn't say and the code doesn't show is an assumption, never a decision.
- **3.5** Open questions are listed with what they affect; it moves on with them only with the
  developer's agreement.
- **3.6** Before committing, it shows the branch, the files and the message, and asks; it never
  signs anything on the developer's behalf.

**Testing criteria:** with a stand-in model and scripted answers: a new project and an existing
one; permission refused; a question left open; an assumption kept out of the decisions; the commit
shown before it's made, and a sign-off never added unasked. Mutation-checked.

**Validation criteria:** the developer runs `cafeai init` on a small project of their own.

**Status:** `in-progress`

## Phase 4 — `cafeai plan` from the terminal

**Objective:** `plan` turns the spec's intended changes into roadmaps and milestones.

**Prerequisites:** phase 3.

**Acceptance criteria**
- **4.1** It writes roadmaps, milestones tracking them by reference, and the index, in the format
  of `ARTIFACTS.md`.
- **4.2** Where the spec is too vague for a testable criterion, it stops and asks; an answer that
  changes the design goes into the spec first.
- **4.3** Estimates and timelines are left to the team; the plan's own readings are flagged to
  confirm.
- **4.4** Planning again keeps criterion numbers stable: new criteria get new numbers, dropped ones
  are cancelled, nothing is renumbered.

**Testing criteria:** with a stand-in model: a clear spec; a vague one; an answer that
contradicts the spec; a second plan after a change, with every earlier number unchanged.
Mutation-checked.

**Validation criteria:** the developer runs `cafeai plan` on the spec from phase 3's validation.

**Status:** `in-progress`

## Phase 5 — Evals on PetClinic

**Objective:** `init` and `plan` are measured against the run by hand (ROADMAP-02).

**Prerequisites:** phases 3 and 4.

**Acceptance criteria**
- **5.1** Evals run `init` and `plan` from PetClinic's base commit with the developer's recorded
  answers, and check what they produce: the packages found; the gaps found (nobody signs in; a
  visit has no time, vet or status); the spec's sections; numbered, testable criteria.
- **5.2** They run with recorded model calls (`cafeai-test`): no key, no cost.
- **5.3** The same evals run live against each supported model.

**Testing criteria:** the evals themselves fail when a check is broken on purpose.

**Validation criteria:** the developer compares an eval run's artifacts with the run by hand.

**Status:** `defined`
