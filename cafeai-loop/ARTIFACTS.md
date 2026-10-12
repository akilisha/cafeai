# The artifacts of the development loop

Status: written (phase 0 of `DESIGN_SPEC.md`): the roadmap, the milestones and the changelog. Plain Markdown: readable and usable without CafeAI.

---

## The three records of progress

| Artifact | Its role |
|---|---|
| **Roadmap** | **The plan.** What will be done, in what phases, to what criteria. No narrative and no explanations of why anything is in the state it's in. |
| **Milestones** | **The heartbeat.** The honest inventory of where the work stands: reasons, difficulties, decisions, risks, estimates, timelines, evidence. |
| **Changelog** | **The log.** An immutable summary of what was added to or removed from the product, for anyone following it. A smaller role than the other two. |

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

- **Its number and title.** No link to the design spec: the spec is far more general than a
  roadmap, and a roadmap exists only to serve it.
- **Its phases,** in order.

### What each phase holds

| Field | | What it says |
|---|---|---|
| **Objective** | required | An unambiguous description of the phase's task within the design goal. |
| **Prerequisites** | optional | What must be done or exist before the phase can start: another phase or roadmap (beyond what the numbering already implies), an account, an environment, an approval. |
| **Inputs** (pre-conditions) | optional | What the phase starts from. |
| **Outputs** (post-conditions) | optional | What exists when the phase is done. |
| **Acceptance criteria** | required | The outcomes without which the phase can't be accepted as completed. Numbered *phase.criterion* (`3.2`), so the milestones can track them by reference. |
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
| `out-of-scope` | Not part of this design goal. A signal visible without opening the milestones, which say why. |
| `completed` | Done: every acceptance criterion met, tested and validated. |

The milestones say why a phase is `blocked`, `escalated`, `deferred`, `cancelled` or
`out-of-scope`: on what, on whom, until when.

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

### Definition

A milestones document is the progress tracker for one roadmap: **the honest inventory of where the
work on it stands, phase by phase.** Plural, because each step is a milestone in itself.

- **One to one with a roadmap,** by number: `MILESTONE-20.md` tracks `ROADMAP-20.md`.
- **The heartbeat to the roadmap's plan.** Everything the roadmap leaves out lives here: why a
  phase is in its status, what happened, what was learned.
- **The manager's test:** a manager reading it should have every answer they need without talking
  to a person.

### What it captures, phase by phase

- **Reasons for a status:** why a phase is blocked, escalated, deferred, cancelled or out of
  scope, on what or whom, until when.
- **Difficulties encountered** with a step.
- **Discussions** that happened during the work, and what they concluded.
- **Dependencies:** on other phases, roadmaps, people, vendors.
- **Risks,** and **vulnerabilities unearthed.**
- **Decisions that changed the scope,** or that **generated new roadmaps** (named by number).
- **Effort estimates and timelines,** the team's own, published here.
- **Evidence** (from the validation ladder, `DESIGN_SPEC.md`): for each acceptance criterion met,
  what was run, what happened, and what was fixed in which commit. A criterion waived is recorded
  with its reason.

**Criteria are tracked by reference, never restated.** The roadmap defines each acceptance
criterion once, numbered (`3.2`); the milestones refer to it by that number ("3.2 met: ...",
"3.4 waived: ..."). One source of truth: a criterion can't say one thing in the plan and another
in the tracker.

### Shape

What this repository's milestones settled into (MILESTONE-20):

- **At the top, a status table:** each phase, its status (as in the roadmap), its estimate and
  timeline. A manager's one-glance answer.
- **Then a section per phase:**
  - each of the roadmap's acceptance criteria, by number, with its state (met, waived, open)
    and a pointer to its evidence;
  - its entries: reasons, difficulties, discussions, dependencies, risks, vulnerabilities,
    decisions, each dated;
  - **Results:** the evidence, dated: what was run, against what, what came out, what was fixed.

Entries are added, not rewritten: when the picture changes, a new dated entry says so, and the
history of how the work went stays readable.

## Changelog

### Definition

The changelog is an immutable summary of what changed in the product: what was added to it, or
removed from it. It is what anyone following the project reads for an overview of what has been
happening. Not a complicated document, but it says a lot about the state and direction of the
project.

- **Updated with every completed phase.** When a phase reaches `completed`, what it added to or
  removed from the product goes in.
- **The product, not the process.** What changed, as someone using the product sees it. Not the
  decisions, discussions or difficulties behind it: those are the milestones'.
- **Immutable.** Entries are never rewritten. A correction is a new entry.

### Shape

The form this repository's `CHANGELOG.md` uses (loosely [Keep a Changelog](https://keepachangelog.com/)):

- **One file,** `CHANGELOG.md` at the project's root.
- **Newest first,** grouped by release (`## [0.6.0] — 2026-10-07`), with what isn't released yet
  at the top.
- **Within a release,** headed by kind: *Added*, *Changed*, *Removed*, *Fixed*, and *Breaking
  changes* called out on their own.
- **Each entry** a short, plain statement of what the product now does or no longer does.
