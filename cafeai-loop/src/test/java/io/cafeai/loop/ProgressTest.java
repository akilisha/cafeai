package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.cafeai.loop.PlanCheckTest.INDEX;
import static io.cafeai.loop.PlanCheckTest.MILESTONE;
import static io.cafeai.loop.PlanCheckTest.ROADMAP;
import static org.assertj.core.api.Assertions.assertThat;

/** ROADMAP-04, 1.1: where `brew` picks up: the first criterion not yet met, in roadmap order. */
@DisplayName("Where the plan stands: the first criterion not yet met, and what is held back")
class ProgressTest {

    private static Map<String, String> plan() {
        return new HashMap<>(Map.of("ROADMAP-01.md", ROADMAP, "MILESTONE-01.md", MILESTONE, "README.md", INDEX));
    }

    private static Map<String, String> with(Map<String, String> plan, String file, String from, String to) {
        assertThat(plan.get(file)).as("the fixture has the text being changed").contains(from);
        plan.put(file, plan.get(file).replace(from, to));
        return plan;
    }

    @Test @DisplayName("nothing done: the first criterion of the first phase, with its phase's objective and tests")
    void nothingDone() {
        Progress.Next next = Progress.of(plan()).next().orElseThrow();
        assertThat(next.roadmap()).isEqualTo("ROADMAP-01");
        assertThat(next.phase()).isEqualTo(1);
        assertThat(next.criterion()).isEqualTo("1.1");
        assertThat(next.text()).isEqualTo("A visit has a date and time.");
        assertThat(next.phaseName()).isEqualTo("A visit's time");
        assertThat(next.objective()).isEqualTo("a visit records when it happens.");
        assertThat(next.testing()).contains("1,024 accepted, 1,025 refused");
    }

    @Test @DisplayName("met and waived criteria are done; the next one in order is picked, across phases")
    void someDone() {
        var p = with(plan(), "MILESTONE-01.md", "- **1.1** open", "- **1.1** met: tested in `VisitTests`, commit abc1234.");
        assertThat(Progress.of(p).next().orElseThrow().criterion()).isEqualTo("1.2");
        with(p, "MILESTONE-01.md", "- **1.2** open", "- **1.2** waived: the developer, 2026-10-14.");
        Progress.Next next = Progress.of(p).next().orElseThrow();
        assertThat(next.criterion()).isEqualTo("2.1");
        assertThat(next.phase()).isEqualTo(2);
        assertThat(Progress.of(p).met()).isEqualTo(2);
        assertThat(Progress.of(p).total()).isEqualTo(3);
    }

    @Test @DisplayName("a criterion cancelled in the roadmap is skipped")
    void cancelled() {
        var p = with(plan(), "ROADMAP-01.md", "- **1.1** A visit", "- **1.1** *(cancelled)* A visit");
        assertThat(Progress.of(p).next().orElseThrow().criterion()).isEqualTo("1.2");
    }

    @Test @DisplayName("blocked, escalated and deferred phases are held back and said; completed, cancelled and out-of-scope ones are done")
    void phaseStatuses() {
        for (String held : new String[] {"blocked", "escalated", "deferred"}) {
            var p = with(plan(), "ROADMAP-01.md", "**Status:** `defined`\n\n## Phase 2", "**Status:** `" + held + "`\n\n## Phase 2");
            Progress progress = Progress.of(p);
            assertThat(progress.next().orElseThrow().criterion()).as(held).isEqualTo("2.1");
            assertThat(progress.heldBack()).as(held).singleElement().asString()
                    .contains("ROADMAP-01").contains("phase 1").contains(held);
        }
        for (String done : new String[] {"completed", "cancelled", "out-of-scope"}) {
            var p = with(plan(), "ROADMAP-01.md", "**Status:** `defined`\n\n## Phase 2", "**Status:** `" + done + "`\n\n## Phase 2");
            Progress progress = Progress.of(p);
            assertThat(progress.next().orElseThrow().criterion()).as(done).isEqualTo("2.1");
            assertThat(progress.heldBack()).as(done).isEmpty();
        }
    }

    @Test @DisplayName("everything met: nothing next")
    void allMet() {
        var p = plan();
        for (String id : new String[] {"1.1", "1.2", "2.1"}) with(p, "MILESTONE-01.md", "- **" + id + "** open", "- **" + id + "** met: done.");
        assertThat(Progress.of(p).next()).isEmpty();
        assertThat(Progress.of(p).met()).isEqualTo(Progress.of(p).total());
    }

    @Test @DisplayName("roadmaps in number order (10 after 9); one without milestones is all open; one in an older format is not the loop's")
    void order() {
        var p = new HashMap<String, String>();
        p.put("ROADMAP-10.md", ROADMAP.replace("ROADMAP-01", "ROADMAP-10"));
        p.put("ROADMAP-09.md", ROADMAP.replace("ROADMAP-01", "ROADMAP-09"));
        p.put("MILESTONE-09.md", MILESTONE.replace("- **1.1** open", "- **1.1** met: done.")
                .replace("- **1.2** open", "- **1.2** met: done.").replace("- **2.1** open", "- **2.1** met: done."));
        p.put("ROADMAP-02.md", "# ROADMAP-02: An older roadmap\n\nPhase one: things.\n");
        Progress.Next next = Progress.of(p).next().orElseThrow();
        assertThat(next.roadmap()).isEqualTo("ROADMAP-10");
        assertThat(next.criterion()).isEqualTo("1.1");
    }

    @Test @DisplayName("roadmaps by their number, not their name's letters: 99 before 100")
    void numbers() {
        var p = new HashMap<String, String>();
        p.put("ROADMAP-100.md", ROADMAP.replace("ROADMAP-01", "ROADMAP-100"));
        p.put("ROADMAP-99.md", ROADMAP.replace("ROADMAP-01", "ROADMAP-99"));
        assertThat(Progress.of(p).next().orElseThrow().roadmap()).isEqualTo("ROADMAP-99");
    }

    @Test @DisplayName("a field ends where the next one starts, blank line or not")
    void adjacentFields() {
        var p = with(plan(), "ROADMAP-01.md", "**Objective:** a visit records when it happens.\n",
                "**Objective:** a visit records when it happens.\n**Inputs:** a Visit with a date.\n");
        assertThat(Progress.of(p).next().orElseThrow().objective()).isEqualTo("a visit records when it happens.");
    }

    @Test @DisplayName("no plan at all: nothing next, nothing held")
    void noPlan() {
        assertThat(Progress.of(Map.of()).next()).isEmpty();
        assertThat(Progress.of(Map.of()).total()).isZero();
    }
}
