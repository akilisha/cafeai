# ROADMAP-04 — `brew` and the validation ladder

## Phase 1 — Picking up where the roadmap stands

**Objective:** `brew` starts from the first unfinished criterion, on a branch.

**Prerequisites:** ROADMAP-03 phase 4.

**Acceptance criteria**
- **1.1** It reads the roadmaps and milestones and starts from the first criterion not yet met,
  in roadmap order.
- **1.2** It works on a branch named by the project's conventions.
- **1.3** It reads `docs/STANDARDS.md`, when there is one, and follows it.

**Testing criteria:** projects with nothing done, some criteria met, a phase blocked; standards
present and absent. Mutation-checked.

**Validation criteria:** the developer runs `brew` on a planned project.

**Status:** `defined`

## Phase 2 — Criteria into tests, checked by mutation

**Objective:** every criterion gets tests that earn their place.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** For each criterion, `brew` writes the tests first, then the code.
- **2.2** It breaks the code on purpose; a test that still passes is rewritten or removed.
- **2.3** Tests that add no value are removed, not kept for coverage.

**Testing criteria:** a criterion with a weak test, which must be caught and fixed. Mutation-checked.

**Validation criteria:** the developer reads the tests written for a criterion.

**Status:** `defined`

## Phase 3 — Evidence, statuses and the changelog

**Objective:** what `brew` did is recorded where the loop says.

**Prerequisites:** phase 2.

**Acceptance criteria**
- **3.1** Each criterion met is recorded in the milestones by its number, with what was run, what
  happened and the commit.
- **3.2** A phase's status is updated in its roadmap and milestones.
- **3.3** When a phase is completed, what it added to or removed from the product goes into the
  changelog.

**Testing criteria:** criteria met, a phase completed, a changelog entry; nothing restated.
Mutation-checked.

**Validation criteria:** the developer reads the milestones after a `brew`.

**Status:** `defined`

## Phase 4 — Stopping

**Objective:** `brew` stops where the team wants, and when it should.

**Prerequisites:** phase 3.

**Acceptance criteria**
- **4.1** It stops after each criterion, milestone or phase, by the `brew.stopAt` dial, with one
  change per stop.
- **4.2** It stops and asks when a criterion can't be met as written, or validation fails and the
  fix isn't clear.
- **4.3** It runs only the commands the `brew.commands` dial allows, asking otherwise.

**Testing criteria:** each dial value; an impossible criterion; a failing validation; a command
not allowed. Mutation-checked.

**Validation criteria:** the developer runs `brew` with each stopping point.

**Status:** `defined`

## Phase 5 — Real dependencies

**Objective:** `brew` checks against the real thing where the project says.

**Prerequisites:** phase 3.

**Acceptance criteria**
- **5.1** The live tests of the environments named by the `validation.live` dial run, and their
  results are evidence.

**Testing criteria:** a live test switched on and off by the dial. Mutation-checked.

**Validation criteria:** the developer runs `brew` with a live environment named.

**Status:** `defined`

## Phase 6 — Observability built into the product

**Objective:** what `brew` builds can be observed, on open standards.

**Prerequisites:** phase 3.

**Acceptance criteria**
- **6.1** The product it builds has OpenTelemetry tracing, Java Flight Recorder events and health
  checks, and the labels and structured logs `cafeai-sentinel` reads, as ordinary code.

**Testing criteria:** a built feature emits its trace, events and health. Mutation-checked.

**Validation criteria:** the developer sees a built feature in their tracing.

**Status:** `defined`
