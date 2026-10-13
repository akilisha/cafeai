# ROADMAP-02 — The loop by hand on PetClinic

## Phase 1 — `init` by hand

**Objective:** an agent, following draft rules, produces a design spec for Spring PetClinic with
its developer.

**Inputs:** a clone of Spring PetClinic, `main` at `500158f`.

**Acceptance criteria**
- **1.1** The agent noticed the existing codebase and asked permission before reading it, saying
  what leaves the machine.
- **1.2** It read the structure, the code package by package and the git history, and reported
  what is built, its quirks, and the gaps the developer's ideas met.
- **1.3** It asked numbered questions with recommendations, until no question was left open.
- **1.4** `DESIGN_SPEC.md` at PetClinic's root keeps what is built apart from what is intended,
  and decisions apart from assumptions.
- **1.5** It was committed by PetClinic's conventions, the sign-off only with the developer's
  word.
- **1.6** Draft 1 of `init`'s prompt is written from the run.

**Testing criteria:** none: a run by hand.

**Validation criteria:** the developer judges whether a newcomer would thank us for the spec.

**Status:** `completed`

## Phase 2 — `plan` by hand

**Objective:** the same agent turns the spec into roadmaps and milestones.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** Answers that changed the design went into the spec first, then the plan.
- **2.2** Three roadmaps with numbered criteria, their milestones tracking them by reference, and
  an index.
- **2.3** The developer accepts the criteria as written.
- **2.4** Draft 1 of `plan`'s prompt is written from the run.

**Testing criteria:** none: a run by hand.

**Validation criteria:** the developer's review of every criterion.

**Status:** `completed`

## Phase 3 — PetClinic as a fixture

**Objective:** the run can be repeated by the evals.

**Acceptance criteria**
- **3.1** The base commit and the branches holding the run's artifacts are recorded in the design
  spec, by reference, without a copy.

**Testing criteria:** none.

**Validation criteria:** the developer's review.

**Status:** `completed`
