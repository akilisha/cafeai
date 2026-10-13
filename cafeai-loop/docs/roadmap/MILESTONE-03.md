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
- **2.2** open
- **2.3** open
- **2.4** open
- **2.5** open

**Entries**

- **2026-10-12.** 2.1 is the plan's reading in two places, to confirm in review: an artifact records its version as an HTML comment on its first line (invisible when read); the design spec, the changelog, `docs/roadmap/` and `docs/design/` are what count as artifacts (the README and code don't).
- **2026-10-12.** Stopped for three design questions before 2.2, 2.4 and 2.5: what "one model for all" is and how a model id names its provider; how screening personal data fits with agents writing files back; where a command's usage is recorded.

- **2026-10-12.** Decided by the developer, and written into the spec: a `models.default` dial and `provider:model` ids, the loop naming no model itself; screening with reversible placeholders; usage in a log on the developer's machine, tokens for now.

**Results**

None yet.

## Phase 3 — `cafeai init` from the terminal

**Criteria**

- **3.1** open
- **3.2** open
- **3.3** open
- **3.4** open
- **3.5** open
- **3.6** open

**Entries**

None yet.

**Results**

None yet.

## Phase 4 — `cafeai plan` from the terminal

**Criteria**

- **4.1** open
- **4.2** open
- **4.3** open
- **4.4** open

**Entries**

- **2026-10-12.** 4.4 is the plan's reading, to confirm in review: it came up in discussion (criterion numbers must stay stable because the milestones track them by number) but was not formally decided.

- **2026-10-12.** 4.4 confirmed by the developer in review.

**Results**

None yet.

## Phase 5 — Evals on PetClinic

**Criteria**

- **5.1** open
- **5.2** open
- **5.3** open

**Entries**

None yet.

**Results**

None yet.
