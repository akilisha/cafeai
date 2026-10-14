# MILESTONE-04 — `brew` and the validation ladder

Tracks [ROADMAP-04](ROADMAP-04.md). Criteria are referred to by number; the roadmap holds their text.

| Phase | Status | Estimate | Timeline |
|---|---|---|---|
| 1. Picking up where the roadmap stands | `defined` | — | — |
| 2. Criteria into tests, checked by mutation | `defined` | — | — |
| 3. Evidence, statuses and the changelog | `defined` | — | — |
| 4. Stopping | `defined` | — | — |
| 5. Real dependencies | `defined` | — | — |
| 6. Observability built into the product | `defined` | — | — |

Estimates and timelines are the team's own, added here when the team makes them.

## Entries for the whole roadmap

- **2026-10-12.** Planned from DESIGN_SPEC.md (phase 4). Open in the spec, and affecting this roadmap: whether `brew` first uses the developer's own coding agent or CafeAI's agents; the mutation tool (2.2); how `brew` names branches in this repository, which commits to `main` (1.2).
- **2026-10-14.** Renumbered from ROADMAP-05, before any work began: `brew` comes before the web app (decided by the developer). `brew` closes the loop, from the plan to code with evidence, and is what picks up from merged artifacts; the web app is a window onto it, needed first by ROADMAP-09. The spec's list of phases (DESIGN_SPEC.md, Phases) swapped with it, so earlier entries' "phase 3" and "phase 4" read the other way round now.
- **2026-10-14.** The three questions left open when this roadmap was planned, decided by the developer and written into the spec (The commands, How `brew` builds): the model the developer signed in to does the coding, directly, with `brew` an agent of the loop's own coordinating it, and no other coding tool involved; the code is broken on purpose by targeted mutations, as this repository's phases were checked, with nothing added to the project's build (2.2); a branch per phase in every project, this one included, merging staying the developer's (1.2).

## Phase 1 — Picking up where the roadmap stands

**Criteria**

- **1.1** open
- **1.2** open
- **1.3** open

**Entries**

None yet.

**Results**

None yet.

## Phase 2 — Criteria into tests, checked by mutation

**Criteria**

- **2.1** open
- **2.2** open
- **2.3** open

**Entries**

- **2026-10-14.** How the work divides, agreed with the developer: the model writes the tests and the code; the loop's code makes the tests come first and fail before the code exists, runs the build and the tests itself, applies each mutation the model proposes (verified to compile, the tests run, the code restored from a copy), returns the survivors, and records only what it ran. A reading of the plan's, to confirm when this phase is built: since a model choosing its own mutations could spare its tests, the code also requires mutations covering every changed line the criterion touches.

**Results**

None yet.

## Phase 3 — Evidence, statuses and the changelog

**Criteria**

- **3.1** open
- **3.2** open
- **3.3** open

**Entries**

None yet.

**Results**

None yet.

## Phase 4 — Stopping

**Criteria**

- **4.1** open
- **4.2** open
- **4.3** open

**Entries**

None yet.

**Results**

None yet.

## Phase 5 — Real dependencies

**Criteria**

- **5.1** open

**Entries**

None yet.

**Results**

None yet.

## Phase 6 — Observability built into the product

**Criteria**

- **6.1** open

**Entries**

None yet.

**Results**

None yet.
