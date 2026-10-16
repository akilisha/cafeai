# MILESTONE-04 — `brew` and the validation ladder

Tracks [ROADMAP-04](ROADMAP-04.md). Criteria are referred to by number; the roadmap holds their text.

| Phase | Status | Estimate | Timeline |
|---|---|---|---|
| 1. Picking up where the roadmap stands | `in-progress` | — | — |
| 2. Criteria into tests, checked by mutation | `in-progress` | — | — |
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

- **1.1** met: `Progress` reads where the plan stands from the artifacts alone: every criterion of the loop's roadmaps in number order, done when the milestones say met or waived or the roadmap marks it cancelled, and a phase done when its status is `completed`, `cancelled` or `out-of-scope`; phases `blocked`, `escalated` or `deferred` are skipped and said. `brew` starts from the first criterion left, telling the developer and the model where the plan stands, the criterion, its phase's objective and testing criteria, and what is held back; with nothing left it says so and asks no model. `ProgressTest`, `BrewCommandTest`.
- **1.2** met: `brew` asks the default branch as `init` and `plan` do, and writes nothing on it (the file tools refuse), with the model told to work on a branch of its own for the phase, named by the project's conventions. `BrewCommandTest`.
- **1.3** open: met for the reading, in the prompt for the following: the project's `docs/STANDARDS.md` reaches the model in full in its first message, so it is read whatever the model does; without one, the model is told there is none. Following them is `brew.md`'s §1, for validation. `BrewCommandTest`.

**Entries**

- **2026-10-14.** Readings of the plan's, to confirm in review:
  - `brew` asks before reading and changing the code, every run, saying which model the code goes to, as `init` asks for reading; declined, it stops and sends nothing;
  - the branch's name is the model's, from the project's conventions; the code holds only that it isn't the default branch (a name chosen by the code would ignore the conventions);
  - a criterion's state is the first word after its number in the milestones: `met` and `waived` are done, any other word is not. This module's own milestones say more than ARTIFACTS.md's three words in places ("in the prompt, to show in validation" reads as not done, rightly; "met for the sections, in the prompt for the rest" reads as done, though partly not), so the states want keeping to the three, with the rest after them;
  - until phases 3 and 4 (evidence, and stopping) the session ends with the developer's `/quit`.
- **2026-10-14.** Reading the real plans: this module's plan, 42 of 100 criteria done, `brew` would start at ROADMAP-03 3.2; PetClinic's, 0 of 43, at its ROADMAP-01 1.1. The count of PetClinic's criteria corrected a miscount in MILESTONE-02.
- **2026-10-14.** The readings above, decided by the developer in review: `brew` asks no permission, since running it on a project the developer planned is the permission, and asking more would add ceremony; it says which model the code goes to, and stops to ask only at a real fork (`brew.md` §3). The branch's name the model's, confirmed. The criterion states kept to ARTIFACTS.md's words, as the developer agreed: this module's milestones restated, each line now starting with `met`, `waived` or `open` (40 met of 100, the partly met now `open`).

**Results**

- **2026-10-14.** `./gradlew :cafeai-loop:test`: 205 tests, all passing (`ProgressTest` 9, `BrewCommandTest` 10 among them).
- **2026-10-14.** Mutation testing, 22 mutations across `Progress`, `BrewCommand` and `Loop`, each verified to apply and to compile: 20 caught. Of the 2 survivors, one was a gap (the session's screen given to `brew`, now tested, re-run caught); one is equivalent: the "where it is planned" note of a command in `Loop` is read only for commands not built, so for `brew` no test can tell it.

## Phase 2 — Criteria into tests, checked by mutation

**Criteria**

- **2.1** met: the code of a criterion can't be written until its tests have been run by the loop and seen failing (`testsFirst`; the file tools refuse writes under `src/main/` until then, through `brew`'s write gate); tests that pass before the code, and runs where no test ran, don't count; then the same tests must pass (`testsPass`). `BrewToolsTest`, `BrewCommandTest` (end to end: the code refused before its tests), `BuildTest`.
- **2.2** met: `mutate` applies a mutation the model proposes to the code, checks it compiles, runs the criterion's tests and restores the code from a copy, whatever happens; a mutation that survives blocks `done` until it is caught by stronger tests, or waived, with its reason, as one no test could see. `done` also requires every changed stretch of the code (since the default branch, new files whole) to have been broken by a caught mutation made on the code as it is now. `BrewToolsTest`, `BrewCommandTest` (a weak test, exposed by a surviving mutation, made stronger), `GitToolsTest`.
- **2.3** open: removing tests that add no value is in the prompt (`brew.md` §2): the code can't tell such a test from a needed one. For validation.

**Entries**

- **2026-10-14.** How the work divides, agreed with the developer: the model writes the tests and the code; the loop's code makes the tests come first and fail before the code exists, runs the build and the tests itself, applies each mutation the model proposes (verified to compile, the tests run, the code restored from a copy), returns the survivors, and records only what it ran. A reading of the plan's, to confirm when this phase is built: since a model choosing its own mutations could spare its tests, the code also requires mutations covering every changed line the criterion touches.
- **2026-10-14.** Built. Readings of the plan's, to confirm in review:
  - the covering rule is by changed stretch, not by line: each stretch of the code changed since the default branch needs at least one caught mutation in it, made on the code as it is now (a mutation made before the code changed again doesn't count); a deletion leaves no line to break, so it needs none;
  - a mutation that survives because no test could see it (a comment, a log message, an equivalent change) is waived with its reason, which phase 3 records with the evidence; one that was caught can't be waived, and a waiver goes once its mutation is caught;
  - the tests are `src/test/java/`, the code `src/main/`, as Gradle and Maven lay a Java project out; the build is the project's own, Gradle's wrapper before Maven where a project has both (PetClinic does), so a criterion is checked by one of its builds, not both;
  - one criterion a session: `done` ends it, until phase 4 decides where `brew` stops.
- **2026-10-14.** Found while building: a test class with no test in it makes Gradle fail ("No tests found for given includes") and Maven pass with none run; either would have unlocked the code, or passed it, with no test run. A run where no test ran now counts as neither.
- **2026-10-14.** The real build, tried on a throwaway copy of PetClinic at its base commit: compiling, and running `VisitControllerTests` through Gradle's wrapper, passed in 50 seconds. A risk this makes plain: every mutation runs the build, so a criterion with ten mutations costs some ten minutes of builds on a project of PetClinic's size.
- **2026-10-14.** A risk: the code is restored from a copy kept in memory; if the process is killed in the middle of a mutation, the mutated file stays as it is, on the working branch. `git diff` shows it.
- **2026-10-15.** The readings above confirmed by the developer in review.

**Results**

- **2026-10-14.** `./gradlew :cafeai-loop:test`: 233 tests, all passing (`BrewToolsTest` 18, `BuildTest` 7, `BrewCommandTest` 12 among them).
- **2026-10-14.** Mutation testing, 46 mutations across `BrewTools`, `Build`, `GitTools`, `ProjectFiles`, `Agents` and `BrewCommand`, each verified to apply and to compile: 36 caught; the 10 survivors were gaps in the tests (the order of the tools; waivers; the covering rule by line; the output's length; a file that isn't Java; the session ending on done), each closed with a test and re-run caught. 46 of 46.

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
