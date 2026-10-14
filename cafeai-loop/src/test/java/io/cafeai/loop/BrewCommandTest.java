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
 * ROADMAP-04, phase 1: {@code cafeai brew} picks up where the roadmap stands, on a branch, with the
 * project's standards. A stand-in model and a scripted developer, against real git.
 */
@DisplayName("cafeai brew: picking up where the roadmap stands")
class BrewCommandTest extends CommandTestBase {

    int brew() {
        return new BrewCommand(project, developer, settings -> new StandIn()).run();
    }

    /** A repository with a committed spec and plan. */
    void planned(String milestone) throws IOException {
        existingRepository();
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC);
        Files.createDirectories(project.resolve("docs/roadmap"));
        Files.writeString(project.resolve("docs/roadmap/ROADMAP-01.md"), ROADMAP);
        Files.writeString(project.resolve("docs/roadmap/MILESTONE-01.md"), milestone);
        Files.writeString(project.resolve("docs/roadmap/README.md"), INDEX);
        raw("add", ".");
        raw("commit", "-m", "Add the spec and the plan");
    }

    String kickoff() {
        return requests.getFirst().messages().getLast().toString();
    }

    @Test @DisplayName("1.1: it starts from the first criterion not yet met, saying which, with its phase's objective and tests")
    void picksUp() throws IOException {
        planned(MILESTONE.replace("- **1.1** open", "- **1.1** met: tested, commit abc1234."));
        developer.answers.addAll(List.of("develop", "/quit"));
        script.add(reply("Starting 1.2."));
        brew();
        assertThat(developer.all()).contains("ROADMAP-01").contains("1.2").contains("1 of 3");
        assertThat(kickoff()).contains("ROADMAP-01, phase 1 (A visit's time), criterion 1.2")
                .contains("Notes hold up to 1,024 characters").contains("a visit records when it happens.")
                .contains("1,024 accepted, 1,025 refused").contains("Its default branch: develop.");
    }

    @Test @DisplayName("1.1: phases held back are said, to the developer and the agent")
    void heldBack() throws IOException {
        planned(MILESTONE);
        String blocked = ROADMAP.replace("**Status:** `defined`\n\n## Phase 2", "**Status:** `blocked`\n\n## Phase 2");
        Files.writeString(project.resolve("docs/roadmap/ROADMAP-01.md"), blocked);
        raw("commit", "-am", "Phase 1 blocked");
        developer.answers.addAll(List.of("main", "/quit"));
        script.add(reply("Starting 2.1."));
        brew();
        assertThat(developer.all()).contains("phase 1: blocked");
        assertThat(kickoff()).contains("criterion 2.1").contains("phase 1: blocked");
    }

    @Test @DisplayName("everything met: brew says so and stops, asking no model")
    void nothingLeft() throws IOException {
        planned(MILESTONE.replace("- **1.1** open", "- **1.1** met: a.").replace("- **1.2** open", "- **1.2** met: b.")
                .replace("- **2.1** open", "- **2.1** waived: the developer."));
        assertThat(brew()).isZero();
        assertThat(developer.all()).contains("every criterion").doesNotContain("May I");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("no plan: brew says plan comes first, and stops")
    void noPlan() throws IOException {
        existingRepository();
        Files.writeString(project.resolve("DESIGN_SPEC.md"), SPEC);
        assertThat(brew()).isEqualTo(2);
        assertThat(developer.all()).contains("`cafeai plan`");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("not a repository: brew says it works on a branch, and stops")
    void notRepository() throws IOException {
        Files.createDirectories(project.resolve("docs/roadmap"));
        Files.writeString(project.resolve("docs/roadmap/ROADMAP-01.md"), ROADMAP);
        assertThat(brew()).isEqualTo(2);
        assertThat(developer.all()).contains("isn't a git repository");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("no model named: brew says how to name one, and stops")
    void noModel() throws IOException {
        planned(MILESTONE);
        int status = new BrewCommand(project, developer, settings -> Models.forCommand(settings, "brew")).run();
        assertThat(status).isEqualTo(2);
        assertThat(developer.all()).contains("models.default");
    }

    @Test @DisplayName("no permission asked: running brew is the permission; the model the code goes to is said")
    void noCeremony() throws IOException {
        planned(MILESTONE);
        developer.answers.addAll(List.of("main", "/quit"));
        script.add(reply("Starting 1.1."));
        assertThat(brew()).isZero();
        assertThat(developer.all()).doesNotContain("May I").contains("Building with stand-in-model");
        assertThat(requests).isNotEmpty();
    }

    @Test @DisplayName("1.3: the project's standards reach the agent in full; without them, it is told there are none")
    void standards() throws IOException {
        planned(MILESTONE);
        Files.writeString(project.resolve("docs/STANDARDS.md"), "# Standards\n- Never use field injection.\n");
        developer.answers.addAll(List.of("main", "/quit"));
        script.add(reply("Noted."));
        brew();
        assertThat(kickoff()).contains("Never use field injection.");

        Files.delete(project.resolve("docs/STANDARDS.md"));
        requests.clear();
        developer.answers.addAll(List.of("main", "/quit"));
        script.add(reply("Noted."));
        brew();
        assertThat(kickoff()).contains("no docs/STANDARDS.md");
    }

    @Test @DisplayName("1.2: it writes code only on a working branch, never the default one")
    void branch() throws IOException {
        planned(MILESTONE);
        developer.answers.addAll(List.of("main", "/quit"));
        script.add(write("src/Visit.java", "class Visit {}"));
        script.add(tool("startBranch", "{\"name\":\"feature/visit-time\"}"));
        script.add(write("src/Visit.java", "class Visit {}"));
        script.add(reply("Started on feature/visit-time."));
        brew();
        assertThat(toolResults()).contains("only allowed on a working branch").contains("Wrote src/Visit.java");
        assertThat(raw("symbolic-ref", "--short", "HEAD").out().strip()).isEqualTo("feature/visit-time");
        assertThat(project.resolve("src/Visit.java")).exists();
        assertThat(kickoff()).contains("a branch of its own for this phase");
    }

    @Test @DisplayName("the session screens through the screen it is given")
    void givenScreen() throws IOException {
        planned(MILESTONE);
        developer.answers.addAll(List.of("main", "Mail me at dev@example.com.", "/quit"));
        script.add(reply("Noted."));
        script.add(reply("Noted again."));
        new BrewCommand(project, developer, settings -> new StandIn(), () -> new Screen("beef")).run();
        assertThat(sent()).contains("[EMAIL_1_beef]").doesNotContain("dev@example.com");
    }
}
