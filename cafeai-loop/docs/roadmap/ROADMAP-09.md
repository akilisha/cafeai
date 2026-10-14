# ROADMAP-09 — The proof on PetClinic

## Phase 1 — `brew` delivers PetClinic's ROADMAP-01

**Objective:** the loop builds a real feature in a real codebase, end to end.

**Prerequisites:** ROADMAP-04 phases 1 to 4.
**Inputs:** PetClinic at `feature/roadmaps` (`400ff04`): its design spec, roadmaps and milestones
from ROADMAP-02.

**Acceptance criteria**
- **1.1** Every criterion of PetClinic's ROADMAP-01 is met, each with its evidence in PetClinic's
  MILESTONE-01.
- **1.2** Every change is made by PetClinic's conventions: `feature/` branches, plain imperative
  messages, the sign-off only with the developer's word, `spring-javaformat`, Maven and Gradle
  both passing.
- **1.3** PetClinic's existing tests still pass, on H2, MySQL and PostgreSQL.

**Testing criteria:** PetClinic's own, and those `brew` writes.

**Validation criteria:** the developer books visits, runs the vets' daily schedule and the
front-desk view in the browser, and judges the result.

**Status:** `defined`

## Phase 2 — Looking back

**Objective:** the loop reviews its own proof.

**Prerequisites:** phase 1; ROADMAP-07 phase 1.

**Acceptance criteria**
- **2.1** `review` on PetClinic reports what is left (ROADMAP-02 and 03 of PetClinic, anything
  open), and nothing it shouldn't.

**Testing criteria:** none beyond the run.

**Validation criteria:** the developer's judgement.

**Status:** `defined`
