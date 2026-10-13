# ROADMAP-06 — `design`

## Phase 1 — Changing the design, without ambiguity

**Objective:** `design` writes new and changed requirements into the spec, and brings out every
ambiguity and conflict first.

**Prerequisites:** ROADMAP-03 phase 3.

**Acceptance criteria**
- **1.1** It updates the design spec from the developer's new or changed requirements.
- **1.2** A requirement that contradicts the spec, or another requirement, is brought out and
  asked about, never resolved silently.
- **1.3** A requirement too ambiguous to plan from is brought out and asked about.

**Testing criteria:** with a stand-in model: a plain addition; a contradiction (walk-ins "most
common" against "new visits tomorrow or later"); an ambiguous requirement. Mutation-checked.

**Validation criteria:** the developer changes a requirement of a planned project.

**Status:** `defined`

## Phase 2 — Changes flowing into the plan

**Objective:** a design change reaches the roadmaps and milestones it affects.

**Prerequisites:** phase 1; ROADMAP-03 phase 4.

**Acceptance criteria**
- **2.1** The roadmaps a change affects are updated; the others are left alone.
- **2.2** Criterion numbers stay stable: new criteria get new numbers, dropped ones are cancelled,
  nothing is renumbered.
- **2.3** The milestones record the change, dated, with its reason.

**Testing criteria:** a change affecting one roadmap of three; a dropped criterion; every earlier
number unchanged. Mutation-checked.

**Validation criteria:** the developer reads the milestones after a design change.

**Status:** `defined`

## Phase 3 — Uploads

**Objective:** the developer adds material to `init` and `design`.

**Prerequisites:** phase 1.

**Acceptance criteria**
- **3.1** Files are added in the conversation (`--attach` on the command line; dragged in, in the
  web app).
- **3.2** Text and structured text are read directly, PDF through `cafeai-rag`, images through a
  vision model, recordings through transcription; large documents are looked up, never read
  whole.
- **3.3** Nothing from an upload enters the spec until the developer confirms it, and the spec
  notes its source.
- **3.4** By default only a reference is kept; with `uploads.keep: original`, the file too, in
  `docs/design/sources/`.

**Testing criteria:** each kind of file; an upload that conflicts with the spec; both settings of
`uploads.keep`; personal data screened before anything is sent. Mutation-checked.

**Validation criteria:** the developer adds a requirements document to a design change.

**Status:** `defined`
