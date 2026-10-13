package io.cafeai.loop;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.Cli;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROADMAP-03, phase 3: {@code cafeai init} from the terminal, with a stand-in model following a
 * script and a scripted developer, against real git. What the model says is the script's; what
 * this tests is what the code enforces whatever the model does: permission before reading, the
 * spec's sections, open questions agreed to, every commit shown and asked, no sign-off unasked.
 */
@DisplayName("cafeai init: what the code enforces, whatever the model does")
class InitCommandTest {

    @TempDir Path project;
    @TempDir Path home;
    private String before;
    private String git;
    private final List<ChatRequest> requests = new ArrayList<>();
    private final Deque<Function<ChatRequest, ChatResponse>> script = new ArrayDeque<>();

    /** A developer answering from a script, and the record of what they were told and asked. */
    private final class Scripted implements Developer {
        final Deque<String> answers = new ArrayDeque<>();
        final List<String> heard = new ArrayList<>();
        @Override public void say(String text) { heard.add(text); }
        @Override public String ask(String prompt) { heard.add(prompt); return answers.poll(); }
        @Override public boolean confirm(String question) {
            heard.add(question);
            String a = answers.poll();
            return a != null && (a.equalsIgnoreCase("y") || a.equalsIgnoreCase("yes"));
        }
        String all() { return String.join("\n", heard); }
    }

    private final Scripted developer = new Scripted();

    private final class StandIn implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String name() { return "stand-in"; }
        @Override public String modelId() { return "stand-in-model"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }
        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    requests.add(request);
                    Function<ChatRequest, ChatResponse> next = script.poll();
                    return next == null ? say("(the script has ended)") : next.apply(request);
                }
            };
        }
    }

    private static ChatResponse say(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(10, 5)).build();
    }

    private static Function<ChatRequest, ChatResponse> tool(String name, String arguments) {
        return r -> ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id(name + arguments.hashCode()).name(name).arguments(arguments).build())))
                .tokenUsage(new TokenUsage(10, 5)).build();
    }

    private static Function<ChatRequest, ChatResponse> reply(String text) {
        return r -> say(text);
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static final String SPEC = """
            # Demo — design spec

            ## 1. The product
            A demo.
            ## 2. As built
            Small.
            ## 3. Tech stack
            Java.
            ## 4. Conventions
            main.
            ## 5. Intended changes
            One.
            ## 6. Decisions
            None.
            ## 7. Assumptions
            None.
            ## 8. Open questions
            None at present.
            ## 9. Risks
            None.
            """;

    private Cli.Result raw(String... args) {
        var command = new ArrayList<>(List.of(git, "-C", project.toString()));
        command.addAll(List.of(args));
        return Cli.run(command, Duration.ofSeconds(30));
    }

    private void existingRepository() throws IOException {
        Files.writeString(project.resolve("README.md"), "# Demo\nThe secret ingredient is nutmeg.\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        raw("init", "-b", "main");
        raw("config", "user.name", "Test Developer");
        raw("config", "user.email", "dev@example.com");
        raw("config", "commit.gpgsign", "false");
        raw("add", ".");
        raw("commit", "-m", "Initial commit");
    }

    private int init() {
        return new InitCommand(project, developer, settings -> new StandIn()).run();
    }

    @BeforeEach
    void setUp() {
        git = Cli.find("git", System::getenv).map(Path::toString).orElse(null);
        assumeTrue(git != null, "git isn't installed");
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
    }

    @AfterEach
    void tearDown() {
        if (before == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", before);
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
}
