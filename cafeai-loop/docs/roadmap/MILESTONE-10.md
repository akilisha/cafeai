# MILESTONE-10 — Collaboration through merged artifacts

Tracks [ROADMAP-10](ROADMAP-10.md). Criteria are referred to by number; the roadmap holds their text.

| Phase | Status | Estimate | Timeline |
|---|---|---|---|
| 1. The merge check | `defined` | — | — |
| 2. Taking the next free numbers | `defined` | — | — |
| 3. Merging entries and decisions | `defined` | — | — |
| 4. What changed since I last looked | `defined` | — | — |
| 5. The proof with two developers | `defined` | — | — |

Estimates and timelines are the team's own, added here when the team makes them.

## Entries for the whole roadmap

- **2026-10-14.** Planned from DESIGN_SPEC.md (The artifacts: collaboration is merging the artifacts; Phases, 9), after a discussion with the developer: what has been formalised for teams working with AI shares what the AI can do, not what it has worked out with a person; the loop's artifacts already carry that, and this roadmap gives them the merge rules several developers need at once.
- **2026-10-14.** Readings of the plan's, to confirm in review:
  - the check is a command of the loop's own, runnable in CI (its name is open); `PlanCheck` already compares the artifacts before and after, and the merge base becomes its "before";
  - milestone entries merge with git's own union merge, declared in `.gitattributes` for `docs/roadmap/MILESTONE-*.md`, rather than a merge driver of the loop's; ordering by date may need a pass after;
  - "since this machine last looked" is the last commit a command saw, kept on the machine with the usage log, not in the project;
  - the proof reuses phase 5 of ROADMAP-03: PetClinic, a briefed developer per session, recordings.
- **2026-10-14.** Found while planning, affecting phases 1 and 2: `PlanCheck` compares roadmaps by file name, so the swap of ROADMAP-04 and ROADMAP-05 the same day (before any work on either) reads to it as criteria removed and entries rewritten. The rule is that a number never changes once work has begun, or once it is merged; the check knows neither. Taking the next free numbers (phase 2) is exactly that kind of change, so the check must tell a roadmap or criterion that moved, unmerged and unstarted, from one that was removed.
- **2026-10-14.** Open, affecting phase 3: whether a decision in the spec needs a marker of its own (an id, like a criterion's number) for a design conflict to be found reliably, or whether the section and its text are enough.

## Phase 1 — The merge check

**Criteria**

- **1.1** open
- **1.2** open
- **1.3** open

**Entries**

None yet.

**Results**

None yet.

## Phase 2 — Taking the next free numbers

**Criteria**

- **2.1** open
- **2.2** open
- **2.3** open

**Entries**

None yet.

**Results**

None yet.

## Phase 3 — Merging entries and decisions

**Criteria**

- **3.1** open
- **3.2** open

**Entries**

None yet.

**Results**

None yet.

## Phase 4 — What changed since I last looked

**Criteria**

- **4.1** open
- **4.2** open

**Entries**

None yet.

**Results**

None yet.

## Phase 5 — The proof with two developers

**Criteria**

- **5.1** open
- **5.2** open
- **5.3** open

**Entries**

None yet.

**Results**

None yet.
