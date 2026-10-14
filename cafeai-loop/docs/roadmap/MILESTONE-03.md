# MILESTONE-03 — The module, its runtime, `init` and `plan`

Tracks [ROADMAP-03](ROADMAP-03.md). Criteria are referred to by number; the roadmap holds their text.

| Phase | Status | Estimate | Timeline |
|---|---|---|---|
| 1. The command and its tools | `completed` | — | — |
| 2. The agents and their prompts | `in-progress` | — | — |
| 3. `cafeai init` from the terminal | `defined` | — | — |
| 4. `cafeai plan` from the terminal | `defined` | — | — |
| 5. Evals on PetClinic | `defined` | — | — |

Estimates and timelines are the team's own, added here when the team makes them.

## Entries for the whole roadmap

- **2026-10-12.** Planned from DESIGN_SPEC.md (phase 2). `init` reads one package at a time until the framework provides child agents (the spec's Child agents; the framework's `docs/design/CHILD_AGENTS.md`, not yet planned).

## Phase 1 — The command and its tools

**Criteria**

- **1.1** met: `cafeai-loop`'s `install` (from `gradle/cafeai-command.gradle`, now shared with `cafeai-cli`) installs one `cafeai` with the loop's commands and `cafeai-cli`'s; `LoopTest` (help lists both; others handed on untouched; a command not built yet says where it is planned and fails).
- **1.2** met: `ProjectFiles`; `ProjectFilesTest`, 14 tests: reads, lists, searches and writes inside; refuses `..` in every form (even staying inside), absolute and drive-relative paths, symbolic links pointing out (file and directory; run on this machine, not skipped), the git directory; writes only on a working branch.
- **1.3** met: `GitTools`; `GitToolsTest` against real git, 20 tests: starts a working branch and commits named files, with no sign-off unless the developer said so; refuses committing on the default branch or a detached head; refuses `merge`, `push`, `rebase`, `reset`, `checkout`, `pull`, `fetch`, `branch`, `tag`, `config`, `remote`, and switching to anything but a branch just created.
- **1.4** met: `Settings` reads `cafeai-loop.yaml` at the project's root; every dial of the spec, its default when unset or when there is no file; a malformed value, an unknown dial (a typo) or a file that isn't YAML refused with the dial's name and what it takes. `SettingsTest`, 20 tests, written before the code.

**Entries**

- **2026-10-12.** Built by hand, not by `brew` (which doesn't exist yet): the tests were written after the code, not before it as the validation ladder asks. Mutation testing stood in for that order.
- **2026-10-12.** Readings of the plan's, to confirm in review: the git directory is out of bounds for the file tools (it holds remote addresses and history); any `..` is refused, even one staying inside; the git tools allow only `rev-parse`, `status`, `check-ref-format`, `switch -c`, `add`, `commit`, `log`; a branch's name is the agent's to choose from the project's conventions, the tools only refusing the default branch and invalid names.
- **2026-10-12.** 1.4 is blocked on the spec's open question (YAML or properties for the settings file); asked of the developer at this stop.

- **2026-10-12.** The four readings above confirmed by the developer in review.
- **2026-10-12.** 1.4 unblocked: the developer chose YAML (`cafeai-loop.yaml`); the spec records it as decided.

- **2026-10-12.** Correction to the result below: one of the 20 mutations, the symbolic-link check (replaced with `false`), never compiled (a `catch` with nothing left to throw), so it was counted as caught without testing anything. Rewritten to compile (the check compares the path with itself), it is caught. Every mutation is now verified to apply and to compile before its tests count.

**Results**

- **2026-10-12.** `./gradlew :cafeai-loop:test`: 37 tests (`LoopTest` 3, `ProjectFilesTest` 14, `GitToolsTest` 20), all passing; `cafeai-cli`'s 23 still passing after its install moved to the shared script.
- **2026-10-12.** Mutation testing, 20 mutations of the safety checks, each restored from a copy: 18 caught at first. Two survived and were dealt with: removing the `..` check survived because the "still inside" check also refuses every escape, and the test only looked for `..` in the message; the check also refuses `..` that stays inside, so a test now pins that down (caught). Removing the "branch already exists" check survived because git refuses an existing branch itself, with the same words: the check duplicated git's and was removed. Final: 19 of 19 caught.
- **2026-10-12.** Validation: `./gradlew :cafeai-loop:install` installed `cafeai` on the JDK it was built with; `cafeai help` lists the loop's commands then `login`, `logout`, `status`; `cafeai init` says where it is planned and exits 1; `cafeai status` works, handed on to `cafeai-cli`.

- **2026-10-12.** 1.4: `SettingsTest`, 20 tests, written first and failing (no `Settings` yet), then passing. Jackson's YAML module declared for the module (it was only on the runtime classpath, through another dependency; same version).
- **2026-10-12.** Mutation testing for the whole phase, rerun: 31 mutations (19 of the tools and the command, 12 of the settings), each verified to apply and to compile, each caught; all tests passing before and after. 57 tests in all (`LoopTest` 3, `ProjectFilesTest` 14, `GitToolsTest` 20, `SettingsTest` 20).

## Phase 2 — The agents and their prompts

**Criteria**

- **2.1** met: `Prompts`: each command's prompt a resource of the module (`init.md`, `plan.md` moved there from `cafeai-loop/prompts/`), with CafeAI's version written by the build (`version.properties`); the file tools, given a command's stamp, start every artifact they write (the design spec, the changelog, roadmaps, milestones, design detail) with `<!-- cafeai-loop <version> · <command> -->`, replacing an earlier one. `PromptsTest`, 7 tests, written first.
- **2.2** met: `Agents`: each command a session on a CafeAI app of its own, its agent an `app.agent(...)` with its own prompt, the model the settings name (`Models`: `models.<command>`, else `models.default`, as `provider:model`), and only its own tools: `init`, `design`, `plan`, `review` write the loop's artifacts only, `brew` anything, `run` nothing. `AgentsTest` (through a stand-in model: the system message is the prompt; `init` writes its spec, stamped, and is refused writing code), `ModelsTest`, `SettingsTest`.
- **2.3** met: every model is one of CafeAI's providers with no credentials of the loop's, so CafeAI's lookup decides (code, the vendor's variable, `cafeai login`); `ModelsTest`.
- **2.4** met: `Screen`: secrets and personal data (CafeAI's own patterns, matched on the text as it is) leave as stable placeholders tagged per session, and are restored exactly when the agent writes, commits, or answers; the developer's words, file contents, listings and search results all screened. `ScreenTest` (round trips byte for byte, Unicode, CRLF, keys, tokens, JWTs; text already looking like a placeholder left alone), and the tools' and agents' tests.
- **2.5** met: when a session closes, its use (calls, input and output tokens, the model) is recorded in `usage.jsonl` under CafeAI's configuration directory, on the machine, and can be shown (`Agents.Usage.summary()`, `Agents.Usage.log()`); recorded once, never mixed between commands. `AgentsTest`.

**Entries**

- **2026-10-12.** 2.1 is the plan's reading in two places, to confirm in review: an artifact records its version as an HTML comment on its first line (invisible when read); the design spec, the changelog, `docs/roadmap/` and `docs/design/` are what count as artifacts (the README and code don't).
- **2026-10-12.** Stopped for three design questions before 2.2, 2.4 and 2.5: what "one model for all" is and how a model id names its provider; how screening personal data fits with agents writing files back; where a command's usage is recorded.

- **2026-10-12.** Decided by the developer, and written into the spec: a `models.default` dial and `provider:model` ids, the loop naming no model itself; screening with reversible placeholders; usage in a log on the developer's machine, tokens for now.

- **2026-10-13.** Tests written before the code for 2.2 to 2.5; two of them were wrong and fixed (a stand-in that picked the wrong placeholder; a repository with no commits, where git can't name the branch), and one message was improved because a test asked for the dial's name, `models.default`.
- **2026-10-13.** A reading of the plan's, to confirm in review: a placeholder carries a short tag of its session (`[EMAIL_1_c4e1]`), so text that already contains `[EMAIL_1]` is never restored by mistake.
- **2026-10-13.** The phase stays `in-progress` until its validation: the developer runs a command and reads what was sent and what it cost. No command can run yet; that comes with phase 3.

**Results**

- **2026-10-13.** `./gradlew :cafeai-loop:test`: 105 tests, all passing (`AgentsTest` 11, `ModelsTest` 5, `ScreenTest` 17, `PromptsTest` 7, `SettingsTest` 24, `GitToolsTest` 21, `ProjectFilesTest` 17, `LoopTest` 3).
- **2026-10-13.** Mutation testing, 32 mutations, each verified to apply and to compile: 31 caught. The survivor, a check never to screen inside a placeholder, was dead code (no pattern can match inside `[EMAIL_1_c4e1]`) and was removed; all tests passing after.

## Phase 3 — `cafeai init` from the terminal

**Criteria**

- **3.1** met: `Surface` notices a project by the names at its top level only (a git repository, build files, deployment files), nothing read; for an existing codebase, `InitCommand` says what it found, what reading means, and that what is read goes to the named model, screened, and asks; without a yes, the file and history tools refuse, so nothing is read whatever the model tries (`ProjectFiles`, `GitTools.history`). `SurfaceAndSpecTest`, `InitCommandTest` (permission asked, naming the model; refused, the model never sees the contents).
- **3.2** in the prompt, to show in validation: the order (structure, the code package by package, the history) and the report are the agent's, following `init.md`; the code supplies the means, with the `history` tool reading branches, commit subjects, merges, trailers and hooks. `InitCommandTest` (allowed: the agent reads the project and its history).
- **3.3** in the prompt, to show in validation: numbered questions with recommendations are `init.md`'s §3; a stand-in model can't show a real one follows it. Phase 5's evals measure it.
- **3.4** met for the sections, in the prompt for the rest: `init` can't finish until `DESIGN_SPEC.md` has its sections (As built only for an existing codebase), matched by name whatever their number, level or case (`SpecCheck`, the `finish` tool); the agent is told which are missing. Keeping assumptions out of the decisions is `init.md`'s §5, for validation and phase 5's evals. `SurfaceAndSpecTest`, `InitCommandTest`.
- **3.5** met: `finish` reads the spec's Open questions and, if any are listed, puts them to the developer; without their agreement it refuses, and the agent is told to settle them first. `InitCommandTest` (declined, then agreed).
- **3.6** met: the `commit` tool shows the developer the branch, the files and the message, and commits only with their yes; a sign-off is asked for separately and added only with a second yes, and with no developer to ask it is refused. `InitCommandTest` (declined; sign-off refused, then given), `GitToolsTest`.

**Entries**

- **2026-10-13.** Readings of the plan's, to confirm in review:
  - the code, not the agent, asks the opening questions (the default branch, permission to read, the product in a sentence), so a model can't skip them; the agent starts from what was answered;
  - a folder that isn't a git repository is made one only with the developer's yes, its default branch theirs to name (`main` offered); for an existing repository the remote's default is offered;
  - `init` ends when `finish` succeeds, or when the developer types `/quit`; it exits 0 when the spec is finished, 1 when not, 2 when it can't start (no model named).
- **2026-10-13.** Tests written before the code. One was wrong and fixed: it searched for the very word it then checked the model never saw, and the search request itself carries the word.
- **2026-10-13.** This phase's validation, the developer running `cafeai init` on a small project of their own with `models.default` set, is phase 2's too: it shows what was sent and what it cost.
- **2026-10-13.** The readings above confirmed by the developer in review.

**Results**

- **2026-10-13.** `./gradlew :cafeai-loop:test`: 139 tests, all passing (`InitCommandTest` 11, `SurfaceAndSpecTest` 7, `TerminalDeveloperTest` 12, `GitToolsTest` 24, `AgentsTest` 11, `LoopTest` 4, `ModelsTest` 5, `ScreenTest` 17, `PromptsTest` 7, `SettingsTest` 24, `ProjectFilesTest` 17).
- **2026-10-13.** Mutation testing, 44 mutations across `InitCommand`, `InitTools`, `SpecCheck`, `Surface`, `GitTools`, `ProjectFiles`, `Agents`, `Loop` and `TerminalDeveloper`, each verified to apply and to compile: 32 caught, 12 survived. Every survivor was a gap in the tests, not dead code. Tests were added for each: the branch the developer names is the one used; the reading request names the model; nothing more is asked once finished; `/quit` isn't sent to the model; As built is required for an existing codebase; open questions end at the next section and are found in any case; a sign-off with nobody to ask is refused; an invalid branch name for a new repository is refused; the remote's default branch is read by name; only `y` or `yes` is a yes at the terminal. All 12 re-run: caught.

## Phase 4 — `cafeai plan` from the terminal

**Criteria**

- **4.1** met: `plan` can't finish until `docs/roadmap/` holds, for each roadmap, the title `# ROADMAP-NN — <topic>`, phases with an objective, acceptance, testing and validation criteria, and a status that is one of the statuses; criteria numbered *phase.criterion*, each once; milestones tracking every criterion by number, and only the roadmap's; the index listing each roadmap with its milestones (`PlanCheck`, the `finish` tool). The agent is told every problem at once. `PlanCheckTest`, `PlanCommandTest` (a first plan, stamped and committed with a yes; out of format, refused). PetClinic's plan from the run by hand, and this module's own roadmaps, pass the check unchanged.
- **4.2** met for the spec, in the prompt for the rest: `plan` writes only `docs/roadmap/`; the file tools refuse the spec, so an answer that changes the design goes into the spec first, by the developer (or `design`), and `plan` runs again. Stopping to ask where the spec is too vague is `plan.md`'s §2, for validation and phase 5's evals. `PlanCommandTest`, `ProjectFilesTest`, `AgentsTest`.
- **4.3** met for estimates, in the prompt for the readings: `finish` refuses an estimate or timeline `plan` wrote, and one of the team's it changed; dashes are empty. Flagging its own readings is `plan.md`'s §4, for validation and phase 5's evals. `PlanCheckTest`, `PlanCommandTest`.
- **4.4** met: planning again, `finish` refuses a criterion removed (one dropped stays, marked cancelled), a criterion renumbered (its text found under another number), a roadmap deleted, and a milestone entry rewritten or removed; a criterion added under a new number is fine. `PlanCheckTest`, `PlanCommandTest` (a removal refused, then an addition accepted).

**Entries**

- **2026-10-12.** 4.4 is the plan's reading, to confirm in review: it came up in discussion (criterion numbers must stay stable because the milestones track them by number) but was not formally decided.

- **2026-10-12.** 4.4 confirmed by the developer in review.

- **2026-10-13.** Two design questions, decided by the developer before the code and written into the spec (The commands; The agents, tools):
  - `plan` writes only the roadmaps, their milestones and the index, never the spec: an answer that changes the design goes into the spec first, by the developer (by hand until `cafeai design` exists);
  - `plan` always reads the spec and the loop's other artifacts, its inputs, telling the developer they go to the named model, screened; it asks before reading the code.
- **2026-10-13.** Readings of the plan's, to confirm in review:
  - a roadmap `plan` leaves as it was, with its milestones, isn't checked for format: it is the team's history (CafeAI's own roadmaps, written before ARTIFACTS.md, would otherwise all have to be rewritten). The numbers and entries rules still apply to it;
  - a criterion dropped is kept in the roadmap marked `*(cancelled)*`; ARTIFACTS.md has no per-criterion status in the roadmap;
  - `plan` stops before asking any model when there is no spec, the spec lacks a section (As built isn't required: a new project has nothing built), or the folder isn't a repository (`init` creates one).
- **2026-10-13.** `init` and `plan` now share their conversation (the default branch asked, the turns until finished or `/quit`), and their tests share a base, `CommandTestBase`.
- **2026-10-13.** This phase's validation, the developer running `cafeai plan` on the spec from phase 3's validation, waits for that.
- **2026-10-13.** The readings above confirmed by the developer in review.

**Results**

- **2026-10-13.** `./gradlew :cafeai-loop:test`: 178 tests, all passing (`PlanCheckTest` 24, `PlanCommandTest` 12, `InitCommandTest` 11, `SurfaceAndSpecTest` 7, `TerminalDeveloperTest` 12, `GitToolsTest` 24, `ProjectFilesTest` 20, `AgentsTest` 11, `LoopTest` 4, `ModelsTest` 5, `ScreenTest` 17, `PromptsTest` 7, `SettingsTest` 24).
- **2026-10-13.** Mutation testing, 66 mutations across `PlanCheck`, `PlanTools`, `PlanCommand`, `Conversation`, `ProjectFiles` and `Agents`, each verified to apply: 59 caught, 6 survived, and one didn't compile (a bare `continue` leaving code unreachable), rewritten as `if (true) continue` and caught. Every survivor was a gap in the tests; tests were added for each: a criterion kept while a new one says the same isn't renumbered; any dash in an estimate cell is empty; a criterion's text ends at the next item and its spaces are made single; a new project's spec, with no As built, is planned from; a scope covers whole paths only. All re-run: caught. 66 of 66.

## Phase 5 — Evals on PetClinic

**Criteria**

- **5.1** met: `./gradlew :cafeai-loop:eval` runs `init` from PetClinic's base commit and `plan` from the run by hand's reviewed spec, the developer played by a model briefed with their recorded answers (`BriefedDeveloper`, `eval/petclinic-developer.md`). The checks: the spec's sections, the packages found, the gaps found (nobody signs in; a visit has no time, vet or status), what is built (two builds, three databases), the conventions, decisions kept apart from assumptions; the plan's format, estimates and numbers (`PlanCheck`), a roadmap per design goal, at least 25 criteria with none in vague words, the spec's facts in the criteria, readings flagged to confirm. Two are judged by a model against a rubric. The run by hand passes every check that is code (`PetClinicChecksTest`).
- **5.2** met: every model call goes through `Replay`; recordings are kept on the machine (`~/.cafeai/loop/evals/<model>/`), and a replay runs with an empty configuration directory, so no key is read and nothing is spent. All three models' evals replay.
- **5.3** met for Claude Sonnet 5.5, GPT-5.5 and GPT-5.4-mini; open for the rest (see the entries): Ollama ran and couldn't finish; Gemini, Grok, Mistral and Nova wait.

**Entries**

- **2026-10-13.** Decided by the developer before building: the developer in the evals is a model briefed with their recorded answers, since a live agent never asks the same questions in the same order; recordings are made with Claude Sonnet 5.5, and kept outside the repository (they hold PetClinic's code and the whole conversation); live runs against Anthropic, OpenAI and a local Ollama model now, the other providers later. The spend for recording, about $13–24 at first estimate, was the developer's to approve, and they approved it.
- **2026-10-13.** The developer's answers were recovered from the conversation of 2026-10-12, verbatim, each with its question in short; their corrections are given only once the agent has raised the gaps they answer, so the model playing them can't hand the agent what it should find.
- **2026-10-13.** The first live runs found four faults in the loop that the stand-in tests couldn't, each fixed with a test:
  - a command's agent kept only CafeAI's default 20 messages, so reading a codebase it forgot the developer's opening words: it now keeps the whole conversation;
  - Anthropic's API needs a limit on a reply's length, and without one a command got LangChain4j's 1,024 tokens, too few to write a spec in one tool call: Anthropic models now get 32,000;
  - a model call had CafeAI's minute; writing a whole spec takes several: ten minutes now;
  - a reply with nothing in it was kept as it was, and providers refuse it when it is sent back: it is kept as "(no reply)".
- **2026-10-13.** A fault in CafeAI's `Replay`, not fixed here: a request's key includes the agent's tool list, which Java's reflection orders differently from one run to the next, so an agent with tools can't be replayed reliably. The evals sort the tools before hashing. The fix belongs in `cafeai-test` (sorting the tools in `Cassettes`), a published module: the developer's decision.
- **2026-10-13.** A limit of CafeAI's Ollama provider, not fixed here: it can't set the context window (`num_ctx`), so Ollama's default of 8,192 tokens applies and a long conversation is silently cut. With `qwen2.5` (7B, mostly on the CPU here), `init` and `plan` both timed out after three attempts of ten minutes. Ollama stays open under 5.3 until the provider can set the window and a machine can run a large enough model.
- **2026-10-13.** The live `plan` was stricter than the run by hand, and found a conflict the run by hand missed: the spec assumed every existing visit is in the past, but the app accepts visits dated tomorrow or later, so the planned migration would mark future bookings `COMPLETED`. It said so and stopped, as 4.2 asks. The developer decided: visits dated before the migration become `COMPLETED`, later ones `SCHEDULED`, at 09:00, with no vet until reception assigns one. `plan`'s eval starts from the run by hand's spec with that correction (`PetClinic.planSpec()`).
- **2026-10-13.** `plan.md` changed twice, from what the live runs showed:
  - once it has stopped for a change to the spec, the run is over: it says so once, and doesn't wait or check the spec again (it had re-checked it turn after turn);
  - a detail the spec leaves unsettled, which the developer hasn't decided, is planned with `plan`'s own recommendation, flagged in the milestone "to confirm in review" (4.3, as in the run by hand); only the developer's own design decisions, and contradictions it finds between the spec and the code, go to the spec first (4.2).
- **2026-10-13.** The developer brief changed too: a recommendation is accepted only where the developer's notes say so (the model playing them had accepted recommendations they never gave, and those became design decisions); when only the real developer could go on (a change to the spec), it ends the session; twelve turns at most (from twenty).
- **2026-10-13.** One developer and one judge for every model under test, Claude Sonnet 5.5 (`-Peval.developer`, `-Peval.judge`): with the model under test playing the developer or grading its own work, a run measured that too (GPT-5.4-mini, playing the developer, quit when its own agent said it would fix the plan).
- **2026-10-13.** Readings of the plan's, to confirm in review:
  - `plan` is measured from the run by hand's reviewed spec (with the correction above), not from `init`'s output, so each is measured on its own; 5.1 says "from PetClinic's base commit", which `init` is;
  - the fixture is PetClinic's `main` at the base commit and its history, without upstream's other branches, which move: a recording replays only if every run sees the same;
  - a check that is code accepts the run by hand's wording and the live runs' (the packages listed one per line; "only a date and a description"), and was widened twice for that after a live run: the judged checks read for meaning.
- **2026-10-13.** Spend, from the recordings and the vendors' usage: on Anthropic about $16 in all (recordings kept: about 2.1M input and 0.19M output tokens; the runs set aside after each fault, about 2M input more); on OpenAI, GPT-5.5 1.2M input and 0.04M output tokens, GPT-5.4-mini 0.4M and 0.01M. The OpenAI key was a short-lived one the developer gave for this, passed through the environment only.
- **2026-10-13.** This phase's validation, the developer comparing an eval run's artifacts with the run by hand, is theirs: `cafeai-loop/build/evals/<model>/` holds each run's artifacts, conversation and report.
- **2026-10-14.** The readings above, and the change to `plan.md`, confirmed by the developer in review.

**Results**

- **2026-10-13.** `./gradlew :cafeai-loop:eval -Peval.mode=replay -Peval.model=<model> -Peval.petclinic=<clone>`, no key:

  | Model | `init` | `plan` |
  |---|---|---|
  | Claude Sonnet 5.5 | 7/7 | 5/6: judged, some criteria leave a limit open ("often enough"); a reading flagged inside a criterion, not in the milestone |
  | GPT-5.5 | 7/7 | 5/6: judged, several criteria not testable as written |
  | GPT-5.4-mini | 4/7: no gaps, two conventions missing, decisions and assumptions mixed (judged) | 4/6: the plan out of format (finish refused it), criteria judged untestable |
  | Ollama `qwen2.5` (7B) | timed out | timed out |

  The checks' own tests: `PetClinicChecksTest`, 6, all passing; every check fails when what it checks is broken on purpose.
- **2026-10-14.** `./gradlew :cafeai-loop:test`: 186 tests, all passing.
- **2026-10-14.** Mutation testing of this phase's changes to the loop (the memory, empty replies, the reply limit, the timeout, the screen's tag, the commands' screen), 11 mutations, each verified to apply and compile: 9 caught; the 2 survivors were gaps in the memory's tests (a reply of tool calls only; a reply of blank text), closed with a test and re-run caught.
