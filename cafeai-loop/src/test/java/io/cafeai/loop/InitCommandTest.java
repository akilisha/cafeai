package io.cafeai.loop;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP-03, phase 3: {@code cafeai init} from the terminal, with a stand-in model following a
 * script and a scripted developer, against real git. What the model says is the script's; what
 * this tests is what the code enforces whatever the model does: permission before reading, the
 * spec's sections, open questions agreed to, every commit shown and asked, no sign-off unasked.
 */
@DisplayName("cafeai init: what the code enforces, whatever the model does")
class InitCommandTest extends CommandTestBase {

    int init() {
        return new InitCommand(project, developer, settings -> new StandIn()).run();
    }

    @Test @DisplayName("3.1: an existing codebase is noticed, and reading asked for, saying what leaves the machine")
    void asksPermission() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("develop", "y", "A recipe site.", "/quit"));
        script.add(reply("Thanks. What should change?"));
        init();
        String heard = developer.all();
        assertThat(heard).contains("pom.xml").contains("git repository");
        assertThat(heard).contains("May I read the project?").contains("[main]");
        String reading = developer.heard.stream().filter(h -> h.contains("leaves this machine")).findFirst().orElseThrow();
        assertThat(reading).as("the model the code would go to is named").contains("stand-in-model");
        String sent = requests.stream().flatMap(r -> r.messages().stream()).map(Object::toString).reduce("", String::concat);
        assertThat(sent).as("the branch the developer named, not the one offered").contains("Its default branch: develop.");
        assertThat(sent).as("/quit ends the session; it isn't sent").doesNotContain("/quit");
    }

    @Test @DisplayName("3.1: without a yes, nothing is read: the tools refuse, and the model never sees the contents")
    void refused() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "n", "A recipe site.", "/quit"));
        script.add(tool("read", "{\"path\":\"README.md\"}"));
        script.add(tool("history", "{}"));
        script.add(tool("search", "{\"text\":\"ingredient\"}"));
        script.add(reply("Understood; tell me about it instead."));
        init();
        String sent = requests.stream().flatMap(r -> r.messages().stream()).map(Object::toString).reduce("", String::concat);
        assertThat(sent).doesNotContain("nutmeg").doesNotContain("Initial commit");
        List<String> results = requests.getLast().messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).toList();
        assertThat(results).hasSize(3).allSatisfy(t -> assertThat(t).contains("hasn't allowed reading"));
        assertThat(requests.getFirst().messages().getLast().toString()).contains("not allowed");
    }

    @Test @DisplayName("3.1, 3.2: with a yes, the agent reads the project and its history")
    void allowed() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "", "/quit"));
        script.add(tool("read", "{\"path\":\"README.md\"}"));
        script.add(tool("history", "{}"));
        script.add(reply("It's a recipe site with a secret ingredient."));
        init();
        String sent = requests.getLast().messages().stream().map(Object::toString).reduce("", String::concat);
        assertThat(sent).contains("nutmeg").contains("Initial commit").contains("main");
    }

    @Test @DisplayName("a new folder: offered a repository with the default branch the developer names; the whole flow to a commit")
    void newProjectToCommit() {
        developer.answers.addAll(List.of("y", "trunk", "A to-do app for one person.", "Sounds right.", "y"));
        script.add(reply("Who will use it, and on what?"));
        script.add(tool("startBranch", "{\"name\":\"feature/design-spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(SPEC.replace("## 2. As built\nSmall.\n", "")) + "}"));
        script.add(tool("commit", "{\"message\":\"Add the design spec\",\"paths\":[\"DESIGN_SPEC.md\"],\"signOff\":false}"));
        script.add(tool("finish", "{\"summary\":\"The spec is written and committed.\"}"));
        script.add(reply("Done: DESIGN_SPEC.md is on feature/design-spec."));
        assertThat(init()).isZero();
        assertThat(raw("symbolic-ref", "--short", "HEAD").out().strip()).isEqualTo("feature/design-spec");
        assertThat(raw("log", "-1", "--format=%B").out().strip()).isEqualTo("Add the design spec");
        assertThat(developer.all()).contains("isn't a git repository").contains("Commit to feature/design-spec")
                .contains("DESIGN_SPEC.md").contains("Add the design spec");
        assertThat(developer.all()).contains("cafeai init:").contains("tokens");
        assertThat(requests.getFirst().messages().getLast().toString()).contains("Its default branch: trunk.");
        assertThat(developer.heard.stream().filter(">"::equals).count()).as("once finished, nothing more is asked").isOne();
    }

    @Test @DisplayName("a new folder whose developer declines a repository: init explains and stops, writing nothing")
    void noRepository() {
        developer.answers.add("n");
        assertThat(init()).isEqualTo(1);
        assertThat(project.resolve(".git")).doesNotExist();
        assertThat(developer.all()).contains("works on branches");
        assertThat(requests).isEmpty();
    }

    @Test @DisplayName("3.4: init can't finish while the spec lacks a section; the agent is told which")
    void missingSections() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(SPEC.replace("## 9. Risks\nNone.\n", "")) + "}"));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("I'll add the risks."));
        assertThat(init()).isEqualTo(1);
        String results = requests.getLast().messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).reduce("", String::concat);
        assertThat(results).contains("Not finished").contains("Risks");
    }

    @Test @DisplayName("3.4: for an existing codebase, the spec isn't finished without As built")
    void asBuiltRequired() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(SPEC.replace("## 2. As built\nSmall.\n", "")) + "}"));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("I'll describe what is built."));
        assertThat(init()).isEqualTo(1);
        String results = requests.getLast().messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).reduce("", String::concat);
        assertThat(results).contains("Not finished").contains("As built");
    }

    @Test @DisplayName("3.5: open questions are put to the developer; init moves on only with their agreement")
    void openQuestions() throws IOException {
        existingRepository();
        String withOpen = SPEC.replace("None at present.", "- Which database?");
        developer.answers.addAll(List.of("main", "y", "", "n", "Postgres, decided.", "y"));
        script.add(tool("startBranch", "{\"name\":\"feature/spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(withOpen) + "}"));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("Then let's settle it: which database?"));
        script.add(tool("finish", "{\"summary\":\"done\"}"));
        script.add(reply("Finished, with it open as you agreed."));
        assertThat(init()).isZero();
        assertThat(developer.all()).contains("Which database?").contains("Move on with them open?");
        assertThat(developer.heard.stream().filter(h -> h.contains("Move on with them open?")).count()).isEqualTo(2);
    }

    @Test @DisplayName("3.6: every commit is shown and asked; declined, nothing is committed")
    void commitDeclined() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "", "n", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(SPEC) + "}"));
        script.add(tool("commit", "{\"message\":\"Add the design spec\",\"paths\":[\"DESIGN_SPEC.md\"],\"signOff\":false}"));
        script.add(reply("Left uncommitted."));
        init();
        assertThat(raw("log", "-1", "--format=%s").out().strip()).isEqualTo("Initial commit");
        assertThat(developer.all()).contains("Commit to feature/spec").contains("Add the design spec");
    }

    @Test @DisplayName("3.6: a sign-off only with the developer's own yes; without it, nothing is committed")
    void signOff() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "", "y", "n", "/quit"));
        script.add(tool("startBranch", "{\"name\":\"feature/spec\"}"));
        script.add(tool("write", "{\"path\":\"DESIGN_SPEC.md\",\"content\":" + json(SPEC) + "}"));
        script.add(tool("commit", "{\"message\":\"Add the design spec\",\"paths\":[\"DESIGN_SPEC.md\"],\"signOff\":true}"));
        script.add(reply("Not committed without your sign-off."));
        init();
        assertThat(raw("log", "-1", "--format=%s").out().strip()).isEqualTo("Initial commit");
        assertThat(developer.all()).contains("Sign off in your name?");

        developer.answers.addAll(List.of("main", "y", "", "y", "y", "/quit"));
        script.add(tool("commit", "{\"message\":\"Add the design spec\",\"paths\":[\"DESIGN_SPEC.md\"],\"signOff\":true}"));
        script.add(reply("Committed, signed off."));
        new InitCommand(project, developer, settings -> new StandIn()).run();
        assertThat(raw("log", "-1", "--format=%B").out()).contains("Signed-off-by: Test Developer <dev@example.com>");
    }

    @Test @DisplayName("no model named: init says how to name one, and stops before anything else")
    void noModel() throws IOException {
        existingRepository();
        int status = new InitCommand(project, developer, settings -> Models.forCommand(settings, "init")).run();
        assertThat(status).isEqualTo(2);
        assertThat(developer.all()).contains("models.default").doesNotContain("May I read");
    }

    @Test @DisplayName("the session screens through the screen it is given")
    void givenScreen() throws IOException {
        existingRepository();
        developer.answers.addAll(List.of("main", "y", "Mail me at dev@example.com.", "/quit"));
        script.add(reply("Noted."));
        new InitCommand(project, developer, settings -> new StandIn(), () -> new Screen("beef")).run();
        assertThat(sent()).contains("[EMAIL_1_beef]").doesNotContain("dev@example.com");
    }
}
