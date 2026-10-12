# The artifacts of the development loop

Status: being written (phase 0 of `DESIGN_SPEC.md`). The roadmap is defined; the milestones are
next. Plain Markdown: readable and usable without CafeAI.

---

## The three records of progress

| Artifact | Its role |
|---|---|
| **Roadmap** | **The plan.** What will be done, in what phases, to what criteria. No narrative and no explanations of why anything is in the state it's in. |
| **Milestones** | **The heartbeat.** What is happening and what happened: explanations, reasons, evidence. *(To be defined.)* |
| **Changelog** | **The log.** An immutable entry for each change, for people. A smaller role than the other two. |

---

## Roadmap

### Definition

A roadmap is a phased execution plan for one design goal. Each phase is organised in small,
executable steps, and the phases together complete the goal.

- **A project is a collection of roadmaps.** They grow and change as the project's scope grows
  and changes.
- **Roadmaps are numbered, in the order the work began** (`ROADMAP-20.md`). The number is how a
  roadmap is referred to, and the numbering implies the order of prerequisites. The topic is in
  the title (`# ROADMAP-20 — cafeai login`) and in the index (`docs/roadmap/README.md`), never in
  the file name.
- **The roadmap is the plan, not the story.** It holds no narrative and no excuses. Why something
  is blocked, what was found, what was waived and why: all of that belongs in the milestones.

### What a roadmap holds

- **Its number and title.**
- **The design goal it serves:** a link to the part of the design spec it carries out, so a
  change to the design finds the roadmaps it affects.
- **Its phases,** in order.
- **Estimation notes,** optional: the team's own estimates, for visibility; kept current in the
  milestones as the work goes.

### What each phase holds

| Field | | What it says |
|---|---|---|
| **Objective** | required | An unambiguous description of the phase's task within the design goal. |
| **Prerequisites** | optional | What must be done or exist before the phase can start: another phase or roadmap (beyond what the numbering already implies), an account, an environment, an approval. |
| **Inputs** (pre-conditions) | optional | What the phase starts from. |
| **Outputs** (post-conditions) | optional | What exists when the phase is done. |
| **Acceptance criteria** | required | The outcomes without which the phase can't be accepted as completed. |
| **Testing criteria** | required | The automated tests: extensive, even aggressive, coverage of the acceptance criteria. See *Every test earns its place*, below. |
| **Validation criteria** | required | How the AI and the developer validate each acceptance criterion together, against the real thing where there is one. |
| **Status** | required | One of the statuses below. Nothing else: the reasons live in the milestones. |

The steps of a phase are small and executable: each one a change the project's change process can
take in one go (the size is a dial: `DESIGN_SPEC.md`, The dials).

### Statuses

| Status | Meaning |
|---|---|
| `defined` | Planned, not started. |
| `in-progress` | Being worked on. |
| `blocked` | Waiting on a process step (an account, a quota, another phase). |
| `escalated` | Waiting on an approval. |
| `deferred` | Not now: priorities changed, or it waits until after something else. A step waived for now is deferred. |
| `cancelled` | Not ever. A step waived for good is cancelled. |
| `completed` | Done: every acceptance criterion met, tested and validated. |

The milestones say why a phase is `blocked`, `escalated`, `deferred` or `cancelled`: on what, on
whom, until when.

### Every test earns its place

An instruction to the AI as much as a rule for people:

- **Coverage is not the measure.** A test that runs code without checking what it does counts
  towards coverage and proves nothing.
- **A test earns its place by catching a mistake.** Break the code it covers on purpose (mutation);
  if the test still passes, it is rewritten or removed.
- **Tests that add no value are removed,** not kept for their numbers: duplicates, tests of the
  language or the library rather than the product, tests that can't fail.
- **Aggressive where it matters:** failure paths, limits, and what a person sees when things go
  wrong are tested as hard as the happy path.

---

## Milestones

*(To be defined.)*

## Changelog

*(To be defined.)*
