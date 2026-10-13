# `cafeai-loop`: the development loop, as a module

Status: design, for review. The vision this implements is `docs/DEV_LOOP_VISION.md`;
this document is how. The module is also the first project built with the method: it practises
on itself (§13). This file is the module's design spec, where the method puts every project's:
`DESIGN_SPEC.md` at the project's root, as a real greenfield project would have it.

## 1. What it is

`cafeai-loop` is the method in `docs/DEV_LOOP_VISION.md`, made runnable: the commands
`init`, `design`, `plan`, `brew`, `run` and `review`, each an agent, working on a project's
living artifacts, with a local web app as the window onto them.

What it isn't:

- **Not published, not in the BOM.** It lives in this repository, alongside the framework, and
  nothing in the framework depends on it. The dependency only goes one way, so it can move to a
  repository of its own later with no change to the framework.
- **Not an IDE plugin yet.** A web app first; IntelliJ and VS Code plugins later, as windows onto
  the same commands (vision §7).
- **Not a tracker integration.** The roadmap and milestones are what a team plans from, in
  whatever tracker it uses (vision §7).

## 2. Where it sits

```
cafeai-loop  ──depends on──►  cafeai-cli (login, logout, status)
                                  cafeai-core (models, usage, the web server)
                                  cafeai-aiservices (the agents behind app.agent(...))
                                  cafeai-guardrails (screening what leaves the machine)
                                  cafeai-observability (tracing the agents)
                 ──tests with──►  cafeai-test (evals of the commands' agents)
```

- **Package:** `io.cafeai.loop`.
- **The `cafeai` command:** `./gradlew :cafeai-loop:install` installs a `cafeai` command
  with every command: the method's, plus `login`, `logout` and `status` from `cafeai-cli`. It
  replaces the `cafeai-cli`-only install in `~/.cafeai/cli`. `cafeai-cli` stays as it is,
  published, for people who only want `cafeai login`.
- **Built with CafeAI itself.** Each command's agent is a CafeAI agent (`app.agent(...)`, with
  its own model, system prompt and tools); the web app is a CafeAI app; model calls go through
  CafeAI's credential lookup (`cafeai login`, no keys), its guardrails and its usage metering.
  The framework is the proof of the method.

## 3. Tech stack

What `cafeai-loop` is built with. Every design spec has this section (§5): `init` asks about it,
or reads it from an existing codebase, and every agent follows it.

| Layer | Choice | Version | Notes |
|---|---|---|---|
| Language | Java | 23 | The build's toolchain; runs on 23 and later. |
| Build | Gradle, a module of this repository's build | 9.7 (wrapper) | `./gradlew :cafeai-loop:...` |
| **Development framework** | **CafeAI** | this repository (0.6.0 line) | `cafeai-loop` is a CafeAI application: the web app, the agents, model access, guardrails, usage and tracing are CafeAI's own. |
| Web server | Helidon SE, through CafeAI | 4.5 | Express-style routes and streaming, CafeAI's API. |
| AI | LangChain4j, through CafeAI | 1.20 | Each command's agent is `app.agent(...)` (`cafeai-aiservices`): its own model, system prompt and tools. |
| Model access | CafeAI's credential lookup | | `cafeai login` (no keys at work), the vendor's environment variable, or code. |
| Guardrails | `cafeai-guardrails` | | Screening what leaves the machine (secrets, tokens, personal data), `run`'s output included. |
| Observability | `cafeai-observability` (OpenTelemetry) | 1.40 | Tracing each agent and its tool calls. |
| JSON | Jackson | 2.22 | As in the rest of CafeAI. |
| Git | the `git` command, run through CafeAI's `Cli` helper | the developer's | Branches and commits; no Git library to add. |
| Tests | JUnit 5, AssertJ | 5.14, 3.27 | |
| Evals | `cafeai-test` | | Recorded model calls (no key, no cost) and evals for each command's prompt. |
| Mutation testing | open (§14) | | PIT, or a smaller tool of our own. |
| Web app front end | open (§14) | | Pages served by the CafeAI app; how much script they need is decided in phase 3. |

## 4. Conventions

The parts of the process so ordinary nobody thinks about them, without which there is no
process. A branch name tells a reviewer what a change is for; many organisations fix their
conventions and enforce them with git hooks. Every design spec has this section (§5). `init`
asks about each one, or reads it from an existing repository (its branches, history, hooks),
and shows the developer what it found. It offers a default where one is meaningful, but never
assumes one: the developer confirms. Every agent follows what is recorded here, and `brew`
works within the project's hooks, never around them.

| Convention | What `init` asks | Offered default | This module |
|---|---|---|---|
| Default branch | the branch changes merge into | `main` | `main` |
| Branch names | the pattern for a change's branch; which prefixes mean what | `<type>/<milestone>-<short-description>` (`feat/`, `fix/`, `docs/`) | none so far: this repository commits to `main` directly (one maintainer). `brew`'s own branches here: open (§14) |
| Commit messages | the format, and whether a tool or hook checks it | Conventional Commits, `type(scope): summary` | Conventional Commits with a scope (`feat(login)`, `docs(vision)`); the scope here is `loop` |
| Commit cadence | how often work is committed, and when it's pushed | one well-documented commit after each discussion or iteration, so the history tells the story and each change has a small blast radius; a prompt to push and sync with the remote now and then | the same |
| Commit trailers | lines the organisation requires or forbids | none | no `Co-Authored-By` or "Generated with" lines |
| Hooks and checks | what the repository enforces before a commit or merge (git hooks, required checks, protected branches) | whatever exists, read from the repository | none installed |
| Merge style | merge commits, squash or rebase | the repository's setting | direct commits to `main` |
| Versions and tags | how releases are numbered and tagged | Semantic Versioning, `vX.Y.Z` | `vX.Y.Z` for the framework; this module isn't released |
| Changelog | where changes are recorded for people, and in what form | `CHANGELOG.md`, newest first | the repository's `CHANGELOG.md`, once the module is usable |

## 5. The artifacts

Plain Markdown in the project, readable and usable without CafeAI (vision principle 3). The
layout follows what this repository does:

| Artifact | Where | Written by | Holds |
|---|---|---|---|
| Design spec | `DESIGN_SPEC.md` at the project's root; further design detail, as the product grows, in `docs/design/`, linked from it | `init`, `design`; the developer by hand | the problem, users, scope, the tech stack (the development framework first), the conventions, decisions, and for an existing codebase "as built" kept apart from "intended", with what's unsure marked for the developer to confirm; open questions |
| Roadmap | `docs/roadmap/ROADMAP-NN.md` | `plan` | the plan for one design goal: its phases, each with an objective, optional prerequisites, inputs and outputs, acceptance, testing and validation criteria, and a status. No narrative (`ARTIFACTS.md`) |
| Milestones | `docs/roadmap/MILESTONE-NN.md` | `plan`, then `brew` | the honest inventory of the roadmap's progress, phase by phase: reasons, difficulties, decisions, risks, estimates and timelines; each acceptance criterion tracked by its number, with its evidence (what was run, what happened, what was fixed and in which commit) (`ARTIFACTS.md`) |
| Standards | `docs/STANDARDS.md` | the developer | the company's and team's own rules (coding standards, banned libraries, required patterns); every agent reads it |
| Changelog | `CHANGELOG.md` | `brew`, with every completed phase | an immutable summary of what was added to or removed from the product; not the process behind it (`ARTIFACTS.md`) |
| Docs after the fact | `README.md`, OpenAPI, guides | `brew`, `review` | the product, approachable for a newcomer |
| Settings | `cafeai-loop.yaml` | the developer | the dials (§9) and each command's model |

The format is written down in full (the required sections of each artifact, the statuses, the
evidence entries) in `ARTIFACTS.md`, beside this file: the first deliverable (§12, phase 0),
from this repository's own LOGIN.md, ROADMAP-20 and MILESTONE-20.

**Roadmaps are named by number only,** in the order the work began (`ROADMAP-20.md`, with its
`MILESTONE-20.md`). The number is how they're referred to in prose and in commits, and it never
changes. The topic is a description that drifts as the work does, so it lives in the file's title
(`# ROADMAP-20 — cafeai login`) and in an index, `docs/roadmap/README.md` (number, milestones,
topic, status, dates), never in the file name. A name belongs after the number only when it is
final, as a finished product's is: this repository's capstone specs keep theirs
(`CAPSTONE-5-nova-tutor.md`). This repository follows the rule.

**No hidden state.** Where the project stands is read from the artifacts themselves: the ticked
and unticked criteria in the milestones, each phase's status in the roadmap. `brew` picks up
"from the last place in the roadmap" by reading it.

## 6. The commands

Every command reads `docs/STANDARDS.md` and the artifacts it needs. **Every change it makes
arrives through the project's own change process.** It works on a branch and commits there;
it never merges, and never pushes to a protected branch. Whatever review the project has
applies; the loop adds none and removes none (vision principle 1).

| Command | Reads | Produces | Stops and asks when |
|---|---|---|---|
| `init` | the codebase, if there is one (after asking permission to read it); the developer's description, in conversation | the design spec | the description leaves a decision open; code and stated intent disagree |
| `design` | the design spec; the developer's new or changed requirements | an updated design spec; then, if the change affects them, an updated roadmap and milestones | the change contradicts a decision already made |
| `plan` | the design spec | the roadmap (each phase with numbered acceptance criteria) and its milestones | the spec is too vague to write a testable criterion |
| `brew` | the roadmap and milestones, from the first unfinished item; the code | code, tests, evidence in the milestone, changelog; one change per stopping point (§9) | a criterion can't be met as written; validation fails and the fix isn't clear; it reaches its stopping point |
| `run <target>` | `<target>`: a jar or class file locally, or the platform's own command (`cafeai run oc get pods -o wide -o json`) | the command's result; findings for `review` | — |
| `review` | the milestones (unticked, deferred, blocked), the open questions, `run`'s findings | a report, and a change adding what it found to the milestones | — |

**Uploads** (`init` and `design`): the developer adds material in the conversation (dragged
into the web app, or `--attach <file>` on the command line). What CafeAI reads, with what it
already has:

| Content | Read by |
|---|---|
| Text, Markdown, structured text (OpenAPI, SQL, sample JSON or CSV) | read directly |
| PDF | Apache Tika, through `cafeai-rag` |
| Web pages | `cafeai-rag`'s URL source |
| Images (screenshots, whiteboards, mock-ups, diagrams) | a vision model (`app.vision`) |
| Recordings | transcription (`app.audio`), then read as text |

Small items go straight to the agent; large documents are ingested with `cafeai-rag` and looked
up, never read whole. Video and tools' own formats (Figma, Visio) are exported to images or PDF
first. The same rules as reading code apply: the developer is told what will be sent, and
guardrails screen out personal data. **An upload is a source, not a decision:** the agent
checks it against the spec and other uploads, brings out conflicts and gaps, and asks; nothing
from it enters the spec until the developer confirms, and the spec notes its source. By default
only a reference is kept (`uploads.keep`, §9).

**Reading an existing codebase** (`init`): map the modules and their dependencies first,
summarise each, then ask the developer targeted questions about what's unclear. Never the
whole codebase into one prompt: a large codebase doesn't fit, and summaries read better. After
`init`, a new codebase and an existing one are handled the same way (vision §3).

**`run`** hands over to the real command, as `cafeai login` hands over to a vendor's CLI. Its
output is screened by guardrails (secrets, tokens, personal data) before anything is kept or
sent to a model: `oc login` prints a token.

**Observability is built in by `brew`, not adapted** (vision §7): OpenTelemetry tracing, Java
Flight Recorder events, health probes, and the labels and structured logs `cafeai-sentinel`
reads, as ordinary code in the product, on open standards.

## 7. The agents

Each command is an agent of its own (vision principle 8):

- **System prompt:** a versioned resource in the module, `prompts/<command>.md`. The version is
  CafeAI's, so a project knows which version of the method produced its artifacts; each
  artifact records it. The prompts are open (in this repository) and part of the framework,
  not the user's work. Users steer through their artifacts and `docs/STANDARDS.md`, never the
  prompts.
- **Model:** its own, if `cafeai-loop.yaml` says so (a reasoning model for `init` and
  `design`, a faster one for `brew`'s small steps); else one for all. Reached through CafeAI's
  credential lookup, so `cafeai login` is all a developer needs, at work as at home.
- **Guardrails:** what leaves the machine is screened (secrets, personal data), and what each
  agent may do is bounded by its tools (below) and the dials (§9).
- **Cost:** every model call is metered by CafeAI and credited to the command that made it;
  the web app and `review` show it. The loop doesn't enforce a budget (vision: cost becomes
  visible through observability).
- **Tools,** the only way an agent acts:

  | Tool | `init` | `design` | `plan` | `brew` | `run` | `review` |
  |---|---|---|---|---|---|---|
  | read files, list, search the project | ✓ | ✓ | ✓ | ✓ | | ✓ |
  | write files on the working branch | artifacts | artifacts | artifacts | code, tests, artifacts | | artifacts |
  | ask the developer | ✓ | ✓ | ✓ | ✓ | | |
  | build and test (Gradle, Maven) | | | | ✓ | | |
  | run commands the dials allow | | | | ✓ | the developer's `<target>` | |
  | git: branch, commit | ✓ | ✓ | ✓ | ✓ | | ✓ |

### Child agents

A command's agent can delegate to **child agents**: a child works on its own and returns only its
conclusion, with pointers to its evidence, so the parent's context stays clean. `init` reads a
codebase one child per package (until the framework provides children, one package at a time); `run` investigates a failure with children reading the logs, the
recent commits, and what `cafeai-sentinel` reports. The developer can start one explicitly too.

- **The mechanism is the framework's, not this module's** (`docs/design/CHILD_AGENTS.md`, a
  framework design goal still to be planned): a child on a virtual thread, its lifetime bound to the parent (it can't outlive it;
  cancellation and deadlines propagate), its cost credited to the parent command, its start and
  finish published for observability. Next to `app.agent`: the API in `cafeai-core`, built in
  `cafeai-aiservices`. On final Java APIs behind a small abstraction, moving to
  `StructuredTaskScope` when it is final (a preview API through JDK 27).
- **The policy is this module's:** which commands spawn children and for what; children's tools,
  read-only by default (only the parent changes files); the limits (dials, §9); and the tree of
  children in the web app, so no work is hidden.

## 8. The validation ladder

How `brew` keeps the project on course (vision principle 2):

1. **Acceptance criteria before code.** `plan` writes them into the roadmap, numbered; they go through
   the project's change process before `brew` starts on them.
2. **Tests, checked by mutation.** For each criterion `brew` writes tests, then breaks the code
   on purpose and checks the tests fail; a test that still passes is rewritten or removed.
   Every test earns its place: tests that add no value (duplicates, tests of the language or a
   library, tests that can't fail) are removed, not kept for coverage (`ARTIFACTS.md`). (Tooling:
   PIT where the build allows it; otherwise the targeted mutations this repository used for
   `cafeai login`.)
3. **Real dependencies, where configured.** Live tests against real services, switched on per
   environment, as `GoogleApplicationDefaultLiveTest` and its siblings are.
4. **Evidence in the milestones.** Each criterion met is recorded by its number, pointing to its
   tests and to what was run, what happened, and what was fixed in which commit.

The developer leads the defence (vision principle 10): the clearer the spec and the more
carefully the test and validation criteria are reviewed, the better the ladder works.

## 9. The dials

`cafeai-loop.yaml` at the project's root, in YAML (decided 2026-10-12: the dials are nested, `brew.stopAt`, `models.init`, and Jackson's YAML support is already on the classpath), the team's style (vision principle 9):

| Dial | Values | Default |
|---|---|---|
| `branches` | `per-artifact` (the spec, the plan and each change on a branch of its own) or `single` (one branch for all): a matter of style, neither better | `per-artifact` |
| `brew.stopAt` | `criterion` (one change per acceptance criterion), `milestone`, `phase` | `criterion` |
| `brew.commands` | which commands `brew` may run by itself, beyond build and test: `none`, an allowed list, `ask` | `ask` |
| `models.<command>` | a model id, through CafeAI's providers | one model for all |
| `validation.mutation` | on or off | on |
| `validation.live` | which environments' live tests `brew` runs | none |
| `children.depth` | how deep child agents may nest | 1 (children don't spawn children) |
| `children.parallel` | how many children run at once | 4 |
| `children.deadline` | how long a child may take | 60 seconds |
| `uploads.keep` | `reference` (a reference and what was taken from it, in the spec) or `original` (the file too, in `docs/design/sources/`) | `reference` |

## 10. The web app

A CafeAI app the loop starts on localhost: `cafeai loop` opens it in the browser.

- **The conversation** for `init` and `design`: describing the product, answering the agent's
  questions, streamed as it writes.
- **The project at a glance:** the design spec, roadmap and milestones as they stand, what each
  command is doing, what it has cost.
- **The same agents as the command line.** The web app is a window, not a second
  implementation: every command works from the terminal too, and the IDE plugins later will be
  windows onto the same thing.

## 11. Testing the loop itself

- **The prompts are tested like code** (vision principle 8): evals with `cafeai-test` give each
  command's agent fixed inputs and check what it produces (`plan` on a fixed design spec must
  yield criteria a test can check; `init` on a fixed codebase must find its modules). Recorded
  model calls let them run with no key and no cost; the same evals run against each supported
  model show whether the engine really can change.
- **The fixtures** start with the Spring PetClinic (vision §8), referenced, not copied: a clone of
  `https://github.com/spring-projects/spring-petclinic` from `main` at
  `500158f732419217507c7656904b8e6aa1bcc0d6` (2026-09-29). The first run by hand (phase 1) left
  its artifacts in that clone, on two local branches: `feature/design-spec` (`DESIGN_SPEC.md`, at
  `aabc476`) and `feature/roadmaps` (`docs/roadmap/`, at `400ff04`). An eval starts from the base
  commit and compares what `init` and `plan` produce with those.
- **The tools** are ordinary Java, tested as such: no write outside the project, no merge, no
  push to a protected branch.

## 12. Phases

0. **The artifact format, written down,** from this repository (LOGIN.md, ROADMAP-20,
   MILESTONE-20, the CHANGELOG): `ARTIFACTS.md`, beside this file. Written: the roadmap, the
   milestones and the changelog.
1. **The method by hand on PetClinic.** No code. An agent (Claude, in a session) follows draft
   `init` and `plan` prompts on PetClinic; the developer plays the product owner for a new
   feature. Out come a design spec, roadmap and milestones in the format, and the first drafts
   of the two prompts. Judged by one question: would a newcomer thank us for them?

   **The `init` system prompt starts from how this module's own spec came about:** the
   conversation that produced `docs/DEV_LOOP_VISION.md` and this document. What it
   taught, so far:
   - the vision before the spec: shake the idea down first, and let the spec claim only what
     survived;
   - offer named options with a recommendation, and let the person decide (this module's name;
     where its spec lives);
   - ask about the tech stack, the development framework first;
   - ask where the code lives and how it's released (published or not, in the BOM or not);
   - surface the conventions (default branch, branch names, commit messages, hooks) instead of
     assuming them; offer a meaningful default, like `main`, but let the developer confirm;
   - commit after each discussion or iteration, with a message that tells what was decided and
     why; prompt to push now and then;
   - challenge only what the product claims to solve, not what any team or tool already handles;
   - write decisions down as they're made; keep genuine open questions visible, unanswered.
2. **The module and its runtime:** `cafeai-loop`, the tools, git on a working branch,
   `cafeai-loop.yaml`, the install. `init` and `plan` from the terminal, with the phase 1
   prompts; evals on the PetClinic fixture.
3. **The web app,** for the conversation and the project at a glance.
4. **`brew` and the validation ladder,** first with `brew.stopAt: criterion`; evidence written
   into the milestones.
5. **`design`, with changes flowing into the roadmap and milestones.**
6. **`review`.**
7. **`run`,** locally, then the platform's own commands.
8. **The proof:** `brew` delivers PetClinic's own ROADMAP-01 end to end (the plan the developer
   accepted in phase 1), every criterion met with evidence in its milestones, and the developer
   validates it in the browser (vision §8). Not an OpenAPI document: PetClinic's spec decided
   against one, and the proof can't require what its project decided against.

## 13. Practising on itself

This module is built with the method it implements:

- this document is its design spec;
- its roadmaps and milestones are in `cafeai-loop/docs/roadmap/`, numbered from `ROADMAP-01` with
  an index: the module is its own project (it may move to its own repository), and the
  repository's `docs/roadmap/` belongs to the framework. In the format of phase 0, with acceptance
  criteria before code and evidence after;
- once `init` works (phase 2), it is run on this repository; what it produces is compared with
  these hand-written artifacts. Where they differ, one of them is wrong, and finding out which
  is the point.

## 14. Open questions

- How `run` reports what it found for `review`: in the milestones directly, or a log of its
  own that `review` reads.
- Whether `brew` uses the developer's own coding agent at first (vision §8, first slice) or
  starts with CafeAI's own agents straight away, now that the agents are cheap to build on
  CafeAI.
- The mutation tool: PIT, or a smaller one of our own.
- How `brew` names its branches in this repository, which commits to `main` directly: a
  pattern of its own, or straight to `main` as a dial.
- The web app's front end: pages served by the CafeAI app, and how much script they need for the
  conversation and streaming (phase 3).
