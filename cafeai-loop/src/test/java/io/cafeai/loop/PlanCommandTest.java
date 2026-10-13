package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static io.cafeai.loop.PlanCheckTest.INDEX;
import static io.cafeai.loop.PlanCheckTest.MILESTONE;
import static io.cafeai.loop.PlanCheckTest.ROADMAP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP-03, phase 4: {@code cafeai plan} from the terminal, with a stand-in model following a
 * script and a scripted developer, against real git. What this tests is what the code enforces
 * whatever the model does: a complete spec to plan from; the code read only with a yes, the
 * artifacts in any case; the spec never written by plan; the plan in format, its estimates the
 * team's, its numbers kept.
 */
@DisplayName("cafeai plan: what the code enforces, whatever the model does")
class PlanCommandTest extends CommandTestBase {

    int plan() {
        return new PlanCommand(project, developer, settings -> new StandIn()).run();
    }

    /** A repository with a committed, complete design spec. */
    void specRepository() throws IOException {
        existingRepository();
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC);
        raw("add", "DESIGN_SPEC.md");
        raw("commit", "-m", "Add the design spec");
    }

    /** The agent writing a whole first plan, then finishing. */
    void scriptPlan() {
        script.add(tool("startBranch", "{\"name\":\"feature/plan\"}"));
        script.add(write("docs/roadmap/ROADMAP-01.md", ROADMAP));
        script.add(write("docs/roadmap/MILESTONE-01.md", MILESTONE));
        script.add(write("docs/roadmap/README.md", INDEX));
    }

    @Test @DisplayName("no design spec: plan says init writes one, and stops before any model is asked")
    void noSpec() throws IOException {
        existingRepository();
        assertThat(plan()).isEqualTo(2);
        assertThat(developer.all()).contains("`cafeai init`").doesNotContain("May I read");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("an incomplete spec: plan names what it lacks, and stops")
    void incompleteSpec() throws IOException {
        existingRepository();
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC.replace("## 9. Risks\nNone.\n", ""));
        assertThat(plan()).isEqualTo(2);
        assertThat(developer.all()).contains("Risks");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("a new project's spec, with nothing built yet and so no As built, is planned from")
    void newProjectSpec() throws IOException {
        existingRepository();
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC.replace("## 2. As built\nSmall.\n", ""));
        developer.answers.addAll(List.of("main", "y", "/quit"));
        script.add(reply("What should the first roadmap cover?"));
        assertThat(plan()).isEqualTo(1);
        assertThat(requests).isNotEmpty();
    }

    @Test @DisplayName("not a repository: plan says it works on a branch, and stops")
    void notRepository() throws IOException {
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC);
        assertThat(plan()).isEqualTo(2);
        assertThat(developer.all()).contains("isn't a git repository");
        assertThat(project.resolve(".git")).doesNotExist();
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("no model named: plan says how to name one, and stops before anything else")
    void noModel() throws IOException {
        specRepository();
        int status = new PlanCommand(project, developer, settings -> Models.forCommand(settings, "plan")).run();
        assertThat(status).isEqualTo(2);
        assertThat(developer.all()).contains("models.default").doesNotContain("May I read");
    }

    @Test @DisplayName("what leaves the machine is said, naming the model; reading the code is asked for; the default branch is the developer's")
    void asks() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("develop", "y", "/quit"));
        script.add(reply("What should the first roadmap cover?"));
        assertThat(plan()).isEqualTo(1);
        String told = developer.heard.stream().filter(h -> h.contains("leave this machine")).findFirst().orElseThrow();
        assertThat(told).contains("stand-in-model").contains("design spec");
        assertThat(developer.all()).contains("May I read the code as well?").contains("[main]");
        assertThat(requests.getFirst().messages().getLast().toString()).contains("Its default branch: develop.")
                .contains("Reading the code: allowed").contains("this is the first plan");
        assertThat(sent()).doesNotContain("/quit");
        assertThat(developer.all()).contains("cafeai plan:").contains("tokens");
    }

    @Test @DisplayName("without a yes, the code isn't read, but the spec and the artifacts are: they are plan's inputs")
    void codeRefused() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("main", "n", "/quit"));
        script.add(tool("read", "{\"path\":\"DESIGN_SPEC.md\"}"));
        script.add(tool("read", "{\"path\":\"README.md\"}"));
        script.add(tool("search", "{\"text\":\"ingredient\"}"));
        script.add(tool("list", "{\"path\":\"\"}"));
        script.add(reply("I'll plan from the spec."));
        plan();
        assertThat(sent()).contains("## 5. Intended changes").doesNotContain("nutmeg").doesNotContain("pom.xml");
        assertThat(toolResults()).contains("hasn't allowed reading the project beyond the loop's artifacts");
        assertThat(requests.getFirst().messages().getLast().toString()).contains("not allowed by the developer");
    }

    @Test @DisplayName("4.1: a first plan: the roadmap, its milestones and the index, stamped, committed with a yes; finished")
    void firstPlan() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("main", "y", "y"));
        scriptPlan();
        script.add(tool("commit", "{\"message\":\"Add the plan\",\"paths\":[\"docs/roadmap\"],\"signOff\":false}"));
        script.add(tool("finish", "{\"summary\":\"One roadmap, two phases, three criteria.\"}"));
        script.add(reply("Planned: ROADMAP-01. Next, your review of its criteria."));
        assertThat(plan()).isZero();
        assertThat(Files.readString(project.resolve("docs/roadmap/ROADMAP-01.md"))).startsWith(Prompts.stamp("plan"));
        assertThat(raw("log", "-1", "--format=%s").out().strip()).isEqualTo("Add the plan");
        assertThat(raw("show", "--name-only", "--format=", "HEAD").out()).contains("docs/roadmap/MILESTONE-01.md");
        assertThat(developer.all()).contains("Commit to feature/plan");
        assertThat(developer.heard.stream().filter(">"::equals).count()).as("once finished, nothing more is asked").isZero();
    }

    @Test @DisplayName("4.1: plan can't finish while the plan is out of format; the agent is told what to fix")
    void outOfFormat() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("main", "y", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/plan\"}"));
        script.add(write("docs/roadmap/ROADMAP-01.md", ROADMAP));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("I'll write the milestones and the index."));
        assertThat(plan()).isEqualTo(1);
        assertThat(toolResults()).contains("Not finished").contains("MILESTONE-01.md").contains("README.md");
    }

    @Test @DisplayName("4.2: plan never writes the spec: a change to the design is the developer's to make first")
    void specNotWritten() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("main", "y", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/plan\"}"));
        script.add(write("DESIGN_SPEC.md", SPEC.replace("One.", "One, with opening hours.")));
        script.add(reply("That answer changes the design: it goes into the spec first."));
        plan();
        assertThat(toolResults()).contains("the design spec is the developer's to change");
        assertThat(Files.readString(project.resolve("DESIGN_SPEC.md"))).isEqualTo(SPEC);
    }

    @Test @DisplayName("4.3: plan can't finish with an estimate of its own: they are the team's")
    void estimates() throws IOException {
        specRepository();
        developer.answers.addAll(List.of("main", "y", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/plan\"}"));
        script.add(write("docs/roadmap/ROADMAP-01.md", ROADMAP));
        script.add(write("docs/roadmap/MILESTONE-01.md", MILESTONE.replace("| `defined` | — | — |", "| `defined` | 3 days | — |")));
        script.add(write("docs/roadmap/README.md", INDEX));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("I'll leave the estimates to the team."));
        assertThat(plan()).isEqualTo(1);
        assertThat(toolResults()).contains("Not finished").contains("team");
    }

    @Test @DisplayName("4.4: planning again: told what is already there; a criterion removed is refused; one added is fine")
    void planAgain() throws IOException {
        specRepository();
        Files.createDirectories(project.resolve("docs/roadmap"));
        Files.writeString(project.resolve("docs/roadmap/ROADMAP-01.md"), ROADMAP);
        Files.writeString(project.resolve("docs/roadmap/MILESTONE-01.md"), MILESTONE);
        Files.writeString(project.resolve("docs/roadmap/README.md"), INDEX);
        raw("add", "docs");
        raw("commit", "-m", "Add the plan");
        developer.answers.addAll(List.of("main", "y", "Add showing a booking.", "/quit"));

        script.add(tool("startBranch", "{\"name\":\"feature/replan\"}"));
        script.add(write("docs/roadmap/ROADMAP-01.md", ROADMAP.replace("- **1.2** Notes hold up to 1,024 characters;\n  more is refused with a message.\n", "")));
        script.add(write("docs/roadmap/MILESTONE-01.md", MILESTONE.replace("- **1.2** open\n", "")));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("1.2 stays, marked cancelled. What else?"));
        script.add(write("docs/roadmap/ROADMAP-01.md", ROADMAP.replace("- **2.1** A booking that overlaps is refused.\n",
                "- **2.1** A booking that overlaps is refused.\n- **2.2** A booking is shown on the owner's page.\n")));
        script.add(write("docs/roadmap/MILESTONE-01.md", MILESTONE.replace("- **2.1** open\n", "- **2.1** open\n- **2.2** open\n")
                .replace("**Entries**\n\nNone yet.", "**Entries**\n\n- **2026-10-14.** Planned again: 2.2 added.")));
        script.add(tool("finish", "{\"summary\":\"2.2 added.\"}"));
        script.add(reply("Planned again: 2.2 added, nothing renumbered."));

        assertThat(plan()).isZero();
        assertThat(requests.getFirst().messages().getLast().toString()).contains("ROADMAP-01.md").contains("keeps every number");
        String first = requests.get(4).messages().getLast().toString();
        assertThat(first).as("the removal refused").contains("Not finished").contains("1.2").contains("cancelled");
        assertThat(Files.readString(project.resolve("docs/roadmap/ROADMAP-01.md"))).contains("**1.2**").contains("**2.2**");
    }
}
