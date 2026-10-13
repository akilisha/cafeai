# ROADMAP-01 — The artifact format

## Phase 1 — The three records, and the roadmap

**Objective:** the roles of the roadmap, the milestones and the changelog, and the roadmap itself,
written down in `ARTIFACTS.md`.

**Acceptance criteria**
- **1.1** The roadmap is the plan, the milestones the heartbeat, the changelog the log; each role
  stated.
- **1.2** The roadmap is defined: a phased execution plan for one design goal; numbered; no
  narrative.
- **1.3** Each phase holds an objective, optional prerequisites, inputs and outputs, numbered
  acceptance criteria, testing and validation criteria, and a status.
- **1.4** The statuses are `defined`, `in-progress`, `blocked`, `escalated`, `deferred`,
  `cancelled`, `out-of-scope`, `completed`.
- **1.5** Every test earns its place: coverage isn't the measure; tests that survive mutation are
  rewritten or removed.

**Testing criteria:** the format is used for a real plan (ROADMAP-02).

**Validation criteria:** the developer reviews each definition as it is written.

**Status:** `completed`

## Phase 2 — The milestones

**Objective:** the milestones defined.

**Acceptance criteria**
- **2.1** One milestones file per roadmap, by number; the honest inventory of where the work
  stands, phase by phase; the manager's test.
- **2.2** Criteria are tracked by reference: numbered *phase.criterion* in the roadmap, referred
  to by number in the milestones, never restated.
- **2.3** Effort estimates and timelines are the team's, in the milestones.

**Testing criteria:** used for a real plan (ROADMAP-02).

**Validation criteria:** the developer's review.

**Status:** `completed`

## Phase 3 — The changelog

**Objective:** the changelog defined.

**Acceptance criteria**
- **3.1** An immutable summary of what was added to or removed from the product, updated with
  every completed phase; the product, not the process; in the shape of this repository's
  `CHANGELOG.md`.

**Testing criteria:** none beyond use.

**Validation criteria:** the developer's review.

**Status:** `completed`

## Phase 4 — Where the artifacts live, and their names

**Objective:** the layout and naming of the artifacts.

**Acceptance criteria**
- **4.1** The root holds what concerns the whole project (`README.md`, `CHANGELOG.md`,
  `DESIGN_SPEC.md`); `docs/roadmap/` and `docs/design/` hold the detail.
- **4.2** Roadmaps and milestones are named by number only, with an index; a name follows a
  number only when it is final, as a capstone's is.

**Testing criteria:** this repository's 38 roadmap and milestone files renamed to the rule, every
reference fixed, the build passing.

**Validation criteria:** the developer's review.

**Status:** `completed`
