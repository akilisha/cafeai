# CafeAI as a development methodology: the vision

*Shaken down in conversation, 2026-10-11. A north star, not a plan: what held after pushing on
it, what changed, and what is still open. It evolves through the same review as everything else.*

---

## 1. The problem

There is no shortage of AI tools for developers. There is a shortage of **method**. Developers
face a saturation of agents, plugins, IDEs and services, and no agreed way of making them work
in their favour. The result is analysis paralysis, not pain. In time a consensus will form, and
it will turn into another ideological battlefield. The window is now, for a clear opinion.

Around that, four observations:

- **Adoption is held back** by data privacy, justifiably, and by the lack of any formal way to
  control usage, mostly its cost.
- **Python has a plethora of AI tools**; most other languages have almost none.
- **Java sits inside nearly every major enterprise**, and is struggling to be relevant in AI.
- **Java developers have no shortage of frameworks.** Another web framework, or another
  high-throughput server, would be noise. CafeAI can be both, but it would not win that race
  against incumbents proven in public. That race is not the goal.

## 2. The idea

**CafeAI powers development itself.** A developer uses it to design, develop, test and release
their product with AI, whatever framework they build in (Spring Boot, Quarkus, Micronaut, ...).
Someone with no preference gets their product built on Helidon, native to CafeAI, as a bonus.

**The process is captured in artifacts.** What CafeAI produces is a set of documents a person can
read and maintain, and that the AI is familiar with and uses to guide it for the product's whole
life:

- a design spec;
- roadmaps and milestones;
- guides, a changelog, a README, API specs (OpenAPI), blog posts.

CafeAI itself is built this way. Its design specs, roadmaps, milestones, guides and changelog let
the work continue across sessions without losing the thread. When that memory failed, the work
went off the rails. The artifacts are what made it work.

**The artifacts are the north star.** Without them, a project is built the way projects are built
today.

In one sentence:

> CafeAI is an opinionated, Java-native method for building with AI: living project artifacts
> (design → roadmap → milestones with evidence → docs) and a CLI that creates them, keeps them
> true, builds the product from them and runs it with observability built in, on new and existing
> codebases, whatever framework the team uses.

## 3. The experience

A developer installs CafeAI: the `cafeai` command, and a web app (served by CafeAI itself) as a
window onto it. Once the commands work well, the same window becomes a plugin for IntelliJ and
VS Code. Commands, as they have emerged so far (more will):

| Command | What it does |
|---|---|
| `cafeai init` | Starts a product. The developer describes their vision in as much detail and with as much precision as they can. In an existing codebase, CafeAI notices, says so, and asks to read the project first; the developer adds a short description or comments. Either way, the result is the **design spec**. From then on there is no difference between a new codebase and an existing one. |
| `cafeai design` | Updates the design spec (or creates it, as `init` does). The developer may also edit it by hand. New or changed requirements go here, iteratively, at any phase. A design change flows on into the roadmap and milestones. |
| `cafeai plan` | From the design spec, generates the **roadmap and milestones**: what the team plans its work from, in whatever tracker app it uses. Estimation notes can be added to the roadmap for visibility, and are updated in the milestones as the work goes. |
| `cafeai brew` | Picks up from the last place in the roadmap and keeps building: code, tests, validation, evidence. |
| `cafeai run <target>` | Runs the product, or the platform's own command. Locally, `<target>` is a jar or a class file (`cafeai run app.jar`). In a shared environment, `<target>` is the platform's command itself, run as the developer would run it: on OpenShift, `cafeai run oc login`, then `cafeai run oc get pods -o wide -o json`. CafeAI hands over to the real command, as `cafeai login` hands over to the vendor's CLI, and sees what it did. Observability comes with it as a first-class part, not bolted on afterwards: tracing and metrics (`cafeai-observability`), the JVM's own view through Java Flight Recorder (`cafeai-flight`), and on Kubernetes, `cafeai-sentinel` doing much of the troubleshooting. What running finds feeds `review`. |
| `cafeai review` | Looks for gaps, deferred items and blocked items, and brings them back up, including what `run` found. |
| `cafeai login`, `logout`, `status` | Already built: sign in to a model with the company's account, no API key (LOGIN.md). |

CafeAI also writes the documentation after the fact (OpenAPI specs, README, guides, blog
posts), so the project is approachable for anyone new to the team.

CafeAI generates all of it, the code included. A developer who never edits the code by hand
still gets a project built the way a seasoned Java developer would build it.

## 4. Principles that held

1. **No corners cut, everything transparent.** CafeAI does not go around any part of the
   development process. Every change it makes (design spec, roadmap, milestones, docs, code)
   arrives through the project's own change process: commit, push, merge, and whatever review
   and approval that process has. CafeAI adds no review step of its own and removes none: if
   someone cares about a change, they review it; if the process cares, it has a review step.
   CafeAI needs no trust model of its own; it rides on the one the project already has. This is
   also why it makes no distinction between an experienced developer and a newcomer: the
   process is the same for both.
2. **Testing and validation keep the project on course.** Tests written by the same AI that
   wrote the code share its blind spots: building `cafeai login`, tests against fakes agreed with
   the AI's reading of the vendors' documentation, and only real accounts found the bugs. So
   validation is a ladder:
   1. acceptance criteria, written from the design spec before the code, through the same
      change process as everything else;
   2. tests, checked by mutation (deliberately broken code must make them fail);
   3. checks against real dependencies, where they exist;
   4. evidence recorded in the milestones: what was run, what happened, what was fixed.

   A plan that holds proof is rare. It is what keeps the artifacts true, and so the north star
   pointing the right way.
3. **The artifacts are open.** Plain Markdown, readable and usable without CafeAI. No lock-in
   makes adoption easier, and a method spreads when it is free to copy.
4. **Artifacts that match what Java teams already have.** OpenAPI, Gradle or Maven modules,
   releases, changelogs: their real deliverables. The roadmap and milestones are what the team
   plans from in its tracker app; CafeAI doesn't need to integrate with one.
5. **Changes flow both ways.** A design change updates the roadmap and milestones; the code
   updates the documentation.
6. **Own the method and the validation; the engine can change.** The model or agent that writes
   the code is whichever is best, reached through CafeAI (`cafeai login`, Anthropic's API,
   GitHub Copilot's Java SDK, ...). What is CafeAI's own is the method and the checks.
7. **Running is part of the method, and closes the loop.** The product doesn't stop at release:
   `run` → observe → `review` brings problems back up → `design` → `plan` → `brew`. CafeAI's
   observability (OpenTelemetry tracing, Java Flight Recorder through `cafeai-flight`) and its
   Kubernetes incident pipeline (`cafeai-sentinel`) are first-class benefits of following the
   method, not afterthoughts. This is where CafeAI's existing modules meet the methodology.
8. **Each command is its own agent, and the prompts are CafeAI's IP.** `init`, `design`,
   `plan`, `brew`, `run` and `review` each run as an agent with its own system prompt, its own
   guardrails, possibly its own model, and its own cost tracking. The method lives in these
   prompts: how to interview, how to write a design spec, the validation ladder, when to stop and
   ask. Writing, versioning and improving them is CafeAI's intellectual property.
   - **They belong to the framework, not the user's work.** Users don't maintain or need to read
     them, as nobody reads a compiler's internals to use it. They are maintained by CafeAI's
     maintainers, and versioned with CafeAI, so a project knows which version of the method
     produced its artifacts.
   - **Not part of the user's work, but not secret.** Principle 1 holds: a company's security
     team can see what instructions run against its code and data (they are in CafeAI's
     repository, at a version).
   - **Users steer through their artifacts, never the prompts.** A company's own rules (coding
     standards, banned libraries, required patterns) go in a standards document in the project,
     which the agents read and which goes through the project's change process. The prompts say
     how to work; the artifacts say what this project wants.
   - **The prompts are tested like code.** CafeAI's own validation principle, applied to itself:
     evals (`cafeai-test`) give each command's agent fixed inputs and check what it produces; a
     prompt change goes through those tests and review like any change. The same evals, run
     against each supported model, show whether the engine really can change.
9. **Not a competitor on the web-server plane.** CafeAI the framework stays, and is the native
   target for those with no preference, but it is not the battleground.

## 5. What changed under pressure

- **"A file layout that drives the AI" is not new on its own.** Claude Code (CLAUDE.md, memory,
  skills), the AGENTS.md convention, GitHub's Spec Kit (specify, plan, tasks) and AWS's Kiro
  (requirements, design, tasks) all use some form of it (as of late 2025; check, it moves fast).
  What CafeAI adds is in §4: the review gate, validation with evidence, changes flowing both ways,
  existing codebases treated like new ones, and artifacts that are a Java team's real deliverables.
- **Existing codebases need care.**
  - **Size:** a large monolith doesn't fit in a model's context. Reading it means mapping the
    modules, summarising each, then asking the developer targeted questions.
  - **Intent:** code shows what is, not what was meant. A design spec rebuilt from code keeps
    "as built" apart from "intended" and marks what it's unsure of for the developer to confirm.
  - **Privacy:** reading company code means sending it to a model. The company's sign-in,
    screening and cost limits are what make that acceptable, which is where `cafeai login` and
    CafeAI's guardrails and usage metering come in.
- **Estimates come from people.** The roadmap gives structure; estimating and capacity planning
  stay with the team, informed by it, with estimation notes optionally recorded in the roadmap
  and kept current in the milestones. Estimates generated by a model are where credibility is
  lost.
- **The order of building matters more than the size of the vision.** See §8.

## 6. Open questions

Genuine questions with no immediate answers. They stay here, visible, until they have one.

- What other commands the method needs (they will emerge, as `review` did).
- The artifact format itself: written down from what this repository already does (design specs,
  ROADMAP and MILESTONE pairs with evidence, the CHANGELOG), so CafeAI is its own reference project.

## 7. Decided along the way

- **The build command is `brew`.** It fits the name; `cook` and `execute` were the alternatives.
- **A web app first, IDE plugins later.** CafeAI serves its own window onto the commands; when
  they work well, it becomes an IntelliJ and a VS Code plugin.
- **No tracker integration.** "The tracker app" is a talking point, not an integration: the team
  plans from the roadmap and milestones in whatever tracker it uses.
- **`<target>` is a placeholder:** a jar or class file locally; in a shared environment, the
  platform's own command, whole (`cafeai run oc login`, `cafeai run oc get pods -o wide -o json`).
  CafeAI runs the real command and sees its output, so troubleshooting and `review` have
  something to work with. What it keeps or sends to a model is screened first: output can hold
  secrets (`oc login` prints a token).
- **Observability in any product, with no adapter.** CafeAI writes the product's code, so
  `brew` builds observability in: OpenTelemetry tracing, Java Flight Recorder events, health
  probes, the labels and structured logs `cafeai-sentinel` reads. On open standards, so the
  product isn't tied to CafeAI, and as ordinary code through the project's change process.
- **Each command is its own agent** (principle 8): its own system prompt, guardrails, possibly
  model, and cost tracking. The prompts, their versions and their improvement are CafeAI's IP.
- **Review belongs to the process.** CafeAI follows whatever review the project's change process
  has, and adds none of its own.

## 8. A first slice

To test the whole idea before building all of it:

1. Write the artifact format down, from this repository.
2. `cafeai init`: the interview, or reading an existing codebase, producing the design spec.
3. `cafeai plan`: roadmap and milestones, each milestone with acceptance criteria.
4. The validation ladder, recording evidence in the milestones.
5. `cafeai brew`, first with the developer's own agent writing the code from the artifacts, then
   with CafeAI driving the engine itself.

Each step arrives through the project's change process, like everything after it. A cheap trial before any code:
run the flow by hand twice, once for an imagined new service and once backwards from an existing
codebase (one of the capstones). If the backwards run produces something a newcomer would thank
you for, the idea holds.
