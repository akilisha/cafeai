# ROADMAP-07 — `review`

## Phase 1 — Bringing things back up

**Objective:** what is unfinished, stuck or unanswered is brought back to the developer.

**Prerequisites:** ROADMAP-04 phase 3.

**Acceptance criteria**
- **1.1** It reports criteria not met, phases `blocked`, `escalated` or `deferred`, the spec's
  open questions, and what `run` found.
- **1.2** Each item says where it is (the roadmap, phase and criterion; the spec's section).

**Testing criteria:** a project with one of each; a project with nothing to report.
Mutation-checked.

**Validation criteria:** the developer runs `review` on this project.

**Status:** `defined`

## Phase 2 — Recording what it found

**Objective:** a review leaves a record.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** It offers a change adding what it found to the milestones, through the project's change
  process.

**Testing criteria:** the change holds exactly what was found. Mutation-checked.

**Validation criteria:** the developer reads the change.

**Status:** `defined`
