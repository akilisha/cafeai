# ROADMAP-04 — The web app

## Phase 1 — Starting it

**Objective:** `cafeai loop` serves the web app to the developer, and only to them.

**Prerequisites:** ROADMAP-03 phase 2.

**Acceptance criteria**
- **1.1** `cafeai loop` starts the web app and opens it in the browser.
- **1.2** It listens on the loopback address only; it can't be reached from another machine.

**Testing criteria:** it starts and answers; a connection from a non-loopback address is refused.
Mutation-checked.

**Validation criteria:** the developer opens it.

**Status:** `defined`

## Phase 2 — The conversation

**Objective:** `init` and `design` are held in the browser, with the same agents as the terminal.

**Prerequisites:** phase 1; ROADMAP-03 phase 3.

**Acceptance criteria**
- **2.1** The conversation for `init` (and later `design`) runs in the web app, its answers
  streamed as they are written.
- **2.2** It is the same agent as the command line's: the same prompt, tools and rules, and the
  same artifacts result.

**Testing criteria:** one scripted conversation through the web app and through the command line
yields the same spec. Mutation-checked.

**Validation criteria:** the developer runs `init` in the browser.

**Status:** `defined`

## Phase 3 — The project at a glance

**Objective:** the developer sees where the project stands.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **3.1** The design spec, the roadmaps and the milestones are shown as they stand in the project.
- **3.2** What each command is doing is shown while it works.
- **3.3** What each command has cost is shown.

**Testing criteria:** each view against a project with known artifacts and usage.
Mutation-checked.

**Validation criteria:** the developer reads this project's own artifacts in it.

**Status:** `defined`
