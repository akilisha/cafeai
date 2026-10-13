# ROADMAP-08 — `run`

## Phase 1 — Running locally

**Objective:** `cafeai run` runs the product on the developer's machine.

**Prerequisites:** ROADMAP-03 phase 1.

**Acceptance criteria**
- **1.1** `cafeai run <target>` runs a jar or a class file.
- **1.2** What it prints is captured, and screened (secrets, tokens, personal data) before
  anything is kept or sent to a model.

**Testing criteria:** a jar and a class file; output containing a token, a password and an email
address, screened. Mutation-checked.

**Validation criteria:** the developer runs a product with it.

**Status:** `defined`

## Phase 2 — The platform's own commands

**Objective:** `cafeai run` hands over to a platform's command, as `cafeai login` hands over to a
vendor's.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **2.1** `cafeai run oc login`, `cafeai run oc get pods -o wide -o json` and the like run the real
  command, with its own options.
- **2.2** Its output is screened as in 1.2: `oc login` prints a token.

**Testing criteria:** a stand-in command printing a token; options passed through unchanged.
Mutation-checked.

**Validation criteria:** the developer runs an `oc` command against a cluster.

**Status:** `defined`

## Phase 3 — Findings for `review`

**Objective:** what `run` finds isn't lost.

**Prerequisites:** phase 1; ROADMAP-07 phase 1.

**Acceptance criteria**
- **3.1** Findings are kept where `review` reads them (the form is an open question in the spec).

**Testing criteria:** a finding from a run appears in the next review. Mutation-checked.

**Validation criteria:** the developer runs a failing product, then `review`.

**Status:** `defined`
