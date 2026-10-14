# ROADMAP-10 — Collaboration through merged artifacts

## Phase 1 — The merge check

**Objective:** a merge that would break the artifacts' rules is found before it lands.

**Prerequisites:** ROADMAP-03 phase 4.

**Acceptance criteria**
- **1.1** A check compares a branch's artifacts with the merge base and reports every break of
  the rules: a criterion removed or renumbered, a roadmap deleted, a milestone entry rewritten or
  removed, an estimate or timeline written by a command.
- **1.2** It reports a collision: the branch adds a criterion number, or a roadmap number, that
  the target branch has added since the merge base.
- **1.3** It runs from the command line and from CI, exiting with a failure when anything is
  reported, and saying for each report the file, the number and what to do.

**Testing criteria:** two branches from one base in a real repository: each rule broken on one of
them; the same new criterion number on both; the same new roadmap number on both; nothing broken.
Mutation-checked.

**Validation criteria:** the developer runs the check on two branches of their own, and in CI.

**Status:** `defined`

## Phase 2 — Taking the next free numbers

**Objective:** the branch merging second gives its new numbers up, and only its new ones.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** For a collision, the branch's own new criteria and roadmaps take the next free numbers
  after the target's, with every reference to them in the artifacts updated.
- **2.2** Numbers already merged never change, on either branch.
- **2.3** The change is shown to the developer and committed only with their yes.

**Testing criteria:** colliding criteria in one phase; colliding roadmaps with their milestones and
the index; references to the renumbered criteria in milestone entries; a number already merged
left alone. Mutation-checked.

**Validation criteria:** the developer resolves a collision between two branches of their own.

**Status:** `defined`

## Phase 3 — Merging entries and decisions

**Objective:** what both sides wrote is kept, and what they decided differently goes to the
developer.

**Prerequisites:** phase 1; ROADMAP-06 phase 1.

**Acceptance criteria**
- **3.1** Milestone entries added on both sides merge without a conflict, every entry kept, in
  date order.
- **3.2** Two branches deciding the same question differently in the spec are reported as a design
  conflict, both decisions shown, and the developer decides through `design`.

**Testing criteria:** entries added on both sides to the same phase; the same decision changed on
both sides; a decision on one side and an open question answered on the other. Mutation-checked.

**Validation criteria:** the developer merges two branches with entries on both sides, and
settles one design conflict.

**Status:** `defined`

## Phase 4 — What changed since I last looked

**Objective:** after a merge, a command starts from what arrived, said plainly.

**Prerequisites:** phase 1; ROADMAP-07 phase 1.

**Acceptance criteria**
- **4.1** Before it starts, a command says what changed in the artifacts since this machine last
  ran a command on the project: decisions, answers to open questions, readings confirmed, new
  roadmaps and phases, statuses.
- **4.2** With nothing changed, it says nothing.

**Testing criteria:** a merge bringing one of each; a merge bringing nothing; the first run on a
machine. Mutation-checked.

**Validation criteria:** the developer merges a colleague's branch and runs a command.

**Status:** `defined`

## Phase 5 — The proof with two developers

**Objective:** two developers on one project, each with their own model, carry each other's work
on.

**Prerequisites:** phases 1 to 4; ROADMAP-04 phase 1.

**Acceptance criteria**
- **5.1** One developer plans on a branch and merges; the other, merging it, runs `brew`, which
  picks up from the merged plan with no other context.
- **5.2** Both plan on branches at once; the second merge finds the collision, takes the next free
  numbers, and the merged plan passes the check.
- **5.3** The two use different models.

**Testing criteria:** recorded, as phase 5 of ROADMAP-03's evals, on PetClinic: two sessions,
two models, one repository. Mutation-checked where it is code.

**Validation criteria:** the developer and a colleague do it on a project of theirs.

**Status:** `defined`
