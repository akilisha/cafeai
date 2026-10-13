package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP-03, 4.1, 4.3 and 4.4: a plan in the format of ARTIFACTS.md (roadmaps, milestones tracking
 * them by number, the index); estimates left to the team; planning again keeps every number.
 */
@DisplayName("Checking a plan: its format, the team's estimates, numbers kept")
class PlanCheckTest {

    static final String ROADMAP = """
            <!-- cafeai-loop 0.6.0 · plan -->
            # ROADMAP-01 — Visits with a time

            ## Phase 1 — A visit's time

            **Objective:** a visit records when it happens.

            **Acceptance criteria**
            - **1.1** A visit has a date and time.
            - **1.2** Notes hold up to 1,024 characters;
              more is refused with a message.

            **Testing criteria:** 1,024 accepted, 1,025 refused. Mutation-checked.

            **Validation criteria:** book one in the browser.

            **Status:** `defined`

            ## Phase 2 — Booking

            **Objective:** reception books a visit.

            **Prerequisites:** phase 1.

            **Acceptance criteria**
            - **2.1** A booking that overlaps is refused.

            **Testing criteria:** 29 minutes refused, 30 accepted.

            **Validation criteria:** an overlapping booking is refused in the browser.

            **Status:** `defined`
            """;

    static final String MILESTONE = """
            <!-- cafeai-loop 0.6.0 · plan -->
            # MILESTONE-01 — Visits with a time

            | Phase | Status | Estimate | Timeline |
            |---|---|---|---|
            | 1. A visit's time | `defined` | — | — |
            | 2. Booking | `defined` | | |

            ## Phase 1 — A visit's time

            **Criteria**

            - **1.1** open
            - **1.2** open

            **Entries**

            - **2026-10-13.** Planned from DESIGN_SPEC.md. To confirm in review: notes are refused, not cut.

            **Results**

            None yet.

            ## Phase 2 — Booking

            **Criteria**

            - **2.1** open

            **Entries**

            None yet.
            """;

    static final String INDEX = """
            # Roadmaps

            | Roadmap | Milestones | Topic | Status |
            |---|---|---|---|
            | [ROADMAP-01](ROADMAP-01.md) | [MILESTONE-01](MILESTONE-01.md) | Visits with a time | `defined` |
            """;

    static Map<String, String> plan() {
        return new HashMap<>(Map.of("ROADMAP-01.md", ROADMAP, "MILESTONE-01.md", MILESTONE, "README.md", INDEX));
    }

    private static List<String> problems(Map<String, String> after) {
        return PlanCheck.of(Map.of(), after).problems();
    }

    private static List<String> problems(Map<String, String> before, Map<String, String> after) {
        return PlanCheck.of(before, after).problems();
    }

    private static Map<String, String> with(String file, String from, String to) {
        var p = plan();
        assertThat(p.get(file)).as("the fixture has the text being changed").contains(from);
        p.put(file, p.get(file).replace(from, to));
        return p;
    }

    @Test @DisplayName("4.1: a roadmap, its milestones and the index, in the format: nothing to say")
    void complete() {
        assertThat(problems(plan())).isEmpty();
        assertThat(PlanCheck.of(Map.of(), plan()).ok()).isTrue();
    }

    @Test @DisplayName("4.1: no roadmap at all isn't a plan")
    void none() {
        assertThat(problems(Map.of())).singleElement().asString().contains("no roadmap");
        assertThat(PlanCheck.of(Map.of(), Map.of()).ok()).isFalse();
    }

    @Test @DisplayName("4.1: the title is the number and the topic")
    void title() {
        assertThat(problems(with("ROADMAP-01.md", "# ROADMAP-01 — Visits with a time", "# Visits with a time")))
                .singleElement().asString().contains("ROADMAP-01.md").contains("# ROADMAP-01 — ");
        assertThat(problems(with("ROADMAP-01.md", "# ROADMAP-01 — Visits", "# ROADMAP-02 — Visits")))
                .singleElement().asString().contains("ROADMAP-01.md");
    }

    @Test @DisplayName("4.1: a roadmap has phases")
    void phases() {
        var p = plan();
        p.put("ROADMAP-01.md", "# ROADMAP-01 — Visits with a time\n\nTo do.\n");
        p.put("MILESTONE-01.md", "# MILESTONE-01\n");
        assertThat(problems(p)).singleElement().asString().contains("ROADMAP-01.md").contains("no phases");
    }

    @ParameterizedTest
    @ValueSource(strings = {"**Objective:** reception books a visit.", "**Acceptance criteria**",
            "**Testing criteria:** 29 minutes refused, 30 accepted.",
            "**Validation criteria:** an overlapping booking is refused in the browser.", "**Status:** `defined`\n"})
    @DisplayName("4.1: every phase has its required fields")
    void required(String field) {
        String name = field.replaceAll("^\\*\\*([^:*]+):?\\*\\*.*", "$1").strip();
        var p = plan();
        int phase2 = ROADMAP.indexOf("## Phase 2");
        p.put("ROADMAP-01.md", ROADMAP.substring(0, phase2) + ROADMAP.substring(phase2).replace(field, ""));
        List<String> found = problems(p);
        assertThat(found).anySatisfy(s -> assertThat(s).contains("phase 2").contains(name));
        assertThat(found).noneSatisfy(s -> assertThat(s).contains("phase 1"));
    }

    @Test @DisplayName("4.1: a status is one of the statuses")
    void status() {
        assertThat(problems(with("ROADMAP-01.md", "**Status:** `defined`\n\n## Phase 2", "**Status:** `done`\n\n## Phase 2")))
                .singleElement().asString().contains("phase 1").contains("done");
        assertThat(problems(with("ROADMAP-01.md", "**Status:** `defined`\n\n## Phase 2", "**Status:** `out-of-scope`\n\n## Phase 2")))
                .isEmpty();
    }

    @Test @DisplayName("4.1: criteria are numbered phase.criterion, once each, at least one a phase")
    void numbering() {
        assertThat(problems(with("ROADMAP-01.md", "- **2.1** A booking", "- **3.1** A booking")))
                .anySatisfy(s -> assertThat(s).contains("3.1").contains("phase 2"));
        assertThat(problems(with("ROADMAP-01.md", "- **1.2** Notes", "- **1.1** Notes")))
                .anySatisfy(s -> assertThat(s).contains("1.1").contains("twice"));
        assertThat(problems(with("ROADMAP-01.md", "- **2.1** A booking that overlaps is refused.\n", "")))
                .anySatisfy(s -> assertThat(s).contains("phase 2").contains("no acceptance criteria"));
    }

    @Test @DisplayName("4.1: each roadmap has its milestones, tracking every criterion by number, and only those")
    void milestones() {
        var p = plan();
        p.remove("MILESTONE-01.md");
        assertThat(problems(p)).singleElement().asString().contains("MILESTONE-01.md");
        assertThat(problems(with("MILESTONE-01.md", "- **1.2** open\n", "")))
                .singleElement().asString().contains("MILESTONE-01.md").contains("1.2");
        assertThat(problems(with("MILESTONE-01.md", "- **2.1** open\n", "- **2.1** open\n- **2.2** open\n")))
                .singleElement().asString().contains("2.2").contains("ROADMAP-01");
    }

    @Test @DisplayName("4.1: the index lists each roadmap with its milestones")
    void index() {
        var p = plan();
        p.remove("README.md");
        assertThat(problems(p)).singleElement().asString().contains("README.md");
        assertThat(problems(with("README.md", "[MILESTONE-01](MILESTONE-01.md)", "")))
                .singleElement().asString().contains("README.md").contains("ROADMAP-01");
    }

    @Test @DisplayName("4.3: estimates and timelines are the team's: plan leaves them empty, and leaves the team's alone")
    void estimates() {
        assertThat(problems(with("MILESTONE-01.md", "| 1. A visit's time | `defined` | — | — |", "| 1. A visit's time | `defined` | 3 days | — |")))
                .singleElement().asString().contains("MILESTONE-01.md").contains("team");
        assertThat(problems(with("MILESTONE-01.md", "| 2. Booking | `defined` | | |", "| 2. Booking | `defined` | | next week |")))
                .singleElement().asString().contains("team");
        assertThat(problems(with("MILESTONE-01.md", "| 1. A visit's time | `defined` | — | — |", "| 1. A visit's time | `defined` | – | - |")))
                .as("dashes of any kind are empty").isEmpty();
        var teams = with("MILESTONE-01.md", "| 1. A visit's time | `defined` | — | — |", "| 1. A visit's time | `defined` | 3 days | sprint 4 |");
        assertThat(problems(teams, teams)).as("the team's own, kept").isEmpty();
        var changed = new HashMap<>(teams);
        changed.put("MILESTONE-01.md", teams.get("MILESTONE-01.md").replace("3 days", "2 days"));
        assertThat(problems(teams, changed)).singleElement().asString().contains("team");
    }

    @Test @DisplayName("4.4: planning again: a new criterion takes a new number")
    void added() {
        var after = with("ROADMAP-01.md", "- **2.1** A booking that overlaps is refused.\n",
                "- **2.1** A booking that overlaps is refused.\n- **2.2** A booking is shown.\n");
        after.put("MILESTONE-01.md", MILESTONE.replace("- **2.1** open\n", "- **2.1** open\n- **2.2** open\n"));
        assertThat(problems(plan(), after)).isEmpty();
    }

    @Test @DisplayName("4.4: planning again: a criterion kept as it was isn't renumbered because a new one says the same")
    void sameText() {
        var after = with("ROADMAP-01.md", "- **2.1** A booking that overlaps is refused.\n",
                "- **2.1** A booking that overlaps is refused.\n- **2.2** A visit has a date and time.\n");
        after.put("MILESTONE-01.md", MILESTONE.replace("- **2.1** open\n", "- **2.1** open\n- **2.2** open\n"));
        assertThat(problems(plan(), after)).isEmpty();
    }

    @Test @DisplayName("a criterion's text is its line and the lines continuing it, spaces made single, up to the next item")
    void criterionText() {
        assertThat(PlanCheck.criteria(ROADMAP)).hasSize(3).containsEntry("1.1", "A visit has a date and time.")
                .containsEntry("1.2", "Notes hold up to 1,024 characters; more is refused with a message.")
                .containsEntry("2.1", "A booking that overlaps is refused.");
    }

    @Test @DisplayName("4.4: planning again: a criterion is never removed; one dropped stays, marked cancelled")
    void removed() {
        var after = with("ROADMAP-01.md", "- **1.2** Notes hold up to 1,024 characters;\n  more is refused with a message.\n", "");
        after.put("MILESTONE-01.md", MILESTONE.replace("- **1.2** open\n", ""));
        assertThat(problems(plan(), after)).singleElement().asString().contains("1.2").contains("cancelled");
        var cancelled = with("ROADMAP-01.md", "- **1.2** Notes", "- **1.2** *(cancelled)* Notes");
        assertThat(problems(plan(), cancelled)).isEmpty();
    }

    @Test @DisplayName("4.4: planning again: nothing is renumbered")
    void renumbered() {
        var after = with("ROADMAP-01.md", "- **1.1** A visit has a date and time.\n",
                "- **1.1** A visit has a vet.\n- **1.3** A visit has a date and time.\n");
        after.put("MILESTONE-01.md", MILESTONE.replace("- **1.2** open\n", "- **1.2** open\n- **1.3** open\n"));
        assertThat(problems(plan(), after)).singleElement().asString().contains("1.1").contains("1.3").contains("renumbered");
    }

    @Test @DisplayName("4.4: planning again: a roadmap is never deleted")
    void roadmapRemoved() {
        var before = plan();
        before.put("ROADMAP-02.md", ROADMAP.replace("ROADMAP-01", "ROADMAP-02"));
        before.put("MILESTONE-02.md", MILESTONE);
        assertThat(problems(before, plan())).singleElement().asString().contains("ROADMAP-02.md").contains("cancelled");
    }

    @Test @DisplayName("an older roadmap planning leaves as it was is the team's history, not plan's to reformat; changed, it is checked")
    void older() {
        String old = "# ROADMAP-02: The old way\n\nPhase one: do things.\n";
        var before = plan();
        before.put("ROADMAP-02.md", old);
        before.put("MILESTONE-02.md", "Progress: some.\n");
        assertThat(problems(before, before)).isEmpty();
        var touched = new HashMap<>(before);
        touched.put("ROADMAP-02.md", old + "Phase two: more things.\n");
        assertThat(problems(before, touched)).anySatisfy(s -> assertThat(s).contains("ROADMAP-02.md"));
        var trackedAgain = new HashMap<>(before);
        trackedAgain.put("MILESTONE-02.md", "Progress: more.\n");
        assertThat(problems(before, trackedAgain)).anySatisfy(s -> assertThat(s).contains("ROADMAP-02.md"));
    }

    @Test @DisplayName("milestone entries are added, never rewritten or removed")
    void entries() {
        String entry = "- **2026-10-13.** Planned from DESIGN_SPEC.md. To confirm in review: notes are refused, not cut.";
        var rewritten = with("MILESTONE-01.md", entry, "- **2026-10-13.** Planned from DESIGN_SPEC.md.");
        assertThat(problems(plan(), rewritten)).singleElement().asString().contains("MILESTONE-01.md").contains("rewritten");
        var added = with("MILESTONE-01.md", entry, entry + "\n- **2026-10-14.** Planned again: 2.2 added.");
        assertThat(problems(plan(), added)).isEmpty();
    }

    @Test @DisplayName("several problems are all reported, so the agent can fix them at once")
    void several() {
        var p = with("ROADMAP-01.md", "**Objective:** a visit records when it happens.", "");
        p.remove("README.md");
        assertThat(problems(p)).hasSize(2);
    }

    @Test @DisplayName("the snapshot is the roadmap directory's Markdown files, by name; none when there is none")
    void snapshot(@TempDir Path project) throws IOException {
        assertThat(PlanCheck.snapshot(project)).isEmpty();
        Path dir = Files.createDirectories(project.resolve("docs/roadmap"));
        Files.writeString(dir.resolve("ROADMAP-01.md"), ROADMAP);
        Files.writeString(dir.resolve("notes.txt"), "not a plan");
        Files.createDirectories(dir.resolve("old"));
        assertThat(PlanCheck.snapshot(project)).containsOnlyKeys("ROADMAP-01.md").containsEntry("ROADMAP-01.md", ROADMAP);
    }
}
