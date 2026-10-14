package io.cafeai.loop;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP-03, 2.2 to 2.5: each command an agent of its own (its prompt, model and tools), models by
 * CafeAI's credential lookup, personal data screened, usage recorded against the command. A
 * stand-in model: no network, no key.
 */
@DisplayName("The agents: own prompt, model and tools; screened; usage recorded")
class AgentsTest {

    @TempDir Path project;
    @TempDir Path home;
    private String before;
    private final List<ChatRequest> requests = new ArrayList<>();
    private Function<ChatRequest, ChatResponse> answer = r -> reply("Done.");

    /** A stand-in for a model: records what it was sent, answers as told. */
    private final class StandIn implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String name() { return "stand-in"; }
        @Override public String modelId() { return "stand-in-model"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }
        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    requests.add(request);
                    return answer.apply(request);
                }
            };
        }
    }

    private static ChatResponse reply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(120, 30)).build();
    }

    @BeforeEach
    void isolate() throws IOException {
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
        Files.writeString(project.resolve("README.md"), "# Demo\nOwner: alice@acme.example\n");
    }

    @AfterEach
    void restore() {
        if (before == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", before);
    }

    private Agents.Session session(String command) {
        return Agents.session(command, new StandIn(), project, "main", new Screen());
    }

    private List<String> toolNames() {
        List<ToolSpecification> tools = requests.getLast().toolSpecifications();
        return tools == null ? List.of() : tools.stream().map(ToolSpecification::name).sorted().toList();
    }

    @Test @DisplayName("2.1, 2.2: the command's own prompt is its system message")
    void prompt() {
        try (var init = session("init")) {
            init.ask("Start a product.");
        }
        ChatMessage first = requests.getFirst().messages().getFirst();
        assertThat(first).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) first).text()).isEqualTo(Prompts.of("init").text());
    }

    @Test @DisplayName("2.2: init and plan read, list, search, write artifacts, branch and commit")
    void initAndPlanTools() {
        for (String command : List.of("init", "plan")) {
            requests.clear();
            try (var s = session(command)) {
                s.ask("Go.");
            }
            assertThat(toolNames()).as(command).containsExactly("commit", "history", "list", "read", "search", "startBranch", "write");
        }
    }

    @Test @DisplayName("2.2: through its tools, init writes its artifacts, stamped, and can't write code")
    void initWritesThroughItsTools() throws IOException {
        String git = io.cafeai.core.internal.Cli.find("git", System::getenv).map(Path::toString).orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(git != null, "git isn't installed");
        for (String[] args : new String[][] {{"init", "-b", "main"}, {"config", "user.name", "T"},
                {"config", "user.email", "t@example.com"}, {"add", "README.md"}, {"commit", "-m", "Initial commit"},
                {"switch", "-c", "feature/spec"}}) {
            var command = new ArrayList<>(List.of(git, "-C", project.toString()));
            command.addAll(List.of(args));
            io.cafeai.core.internal.Cli.run(command, java.time.Duration.ofSeconds(30));
        }
        var calls = new java.util.ArrayDeque<>(List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("1").name("write")
                        .arguments("{\"path\":\"DESIGN_SPEC.md\",\"content\":\"# Spec\\n\"}").build(),
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("2").name("write")
                        .arguments("{\"path\":\"src/App.java\",\"content\":\"class App {}\"}").build()));
        answer = r -> calls.isEmpty() ? reply("Written.")
                : ChatResponse.builder().aiMessage(AiMessage.from(List.of(calls.poll()))).tokenUsage(new TokenUsage(1, 1)).build();
        try (var init = session("init")) {
            init.ask("Write the spec, and some code.");
        }
        assertThat(Files.readString(project.resolve("DESIGN_SPEC.md"))).isEqualTo(Prompts.stamp("init") + "\n# Spec\n");
        assertThat(project.resolve("src/App.java")).doesNotExist();
        String refusal = requests.getLast().messages().stream()
                .filter(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)
                .map(m -> ((dev.langchain4j.data.message.ToolExecutionResultMessage) m).text()).reduce("", String::concat);
        assertThat(refusal).contains("only the loop's artifacts");
    }

    @Test @DisplayName("2.2: run has no file or git tools")
    void runHasNoTools() {
        assertThat(Agents.Session.tools("run", project, "main", new Screen())).isEmpty();
        assertThat(Agents.Session.tools("init", project, "main", new Screen())).hasSize(2);
    }

    @Test @DisplayName("2.5: a session closed twice records its use once")
    void closedTwice() {
        var plan = session("plan");
        plan.ask("One.");
        plan.close();
        plan.close();
        assertThat(Agents.Usage.log()).hasSize(1);
        assertThatThrownBy(() -> plan.ask("Again.")).hasMessageContaining("has finished");
    }

    @Test @DisplayName("2.2, 4.2: a command's tools are its own: init's writes are artifacts only, plan's the roadmaps only")
    void artifactsOnly() {
        assertThat(Agents.writes("init")).isEqualTo(Agents.Writes.ARTIFACTS);
        assertThat(Agents.writes("plan")).isEqualTo(Agents.Writes.ROADMAPS);
        assertThat(Agents.readableAnyway("plan")).isEqualTo(io.cafeai.loop.tools.ProjectFiles.Scope.ARTIFACTS);
        assertThat(Agents.readableAnyway("init")).isEqualTo(io.cafeai.loop.tools.ProjectFiles.Scope.NONE);
        assertThat(Agents.writes("design")).isEqualTo(Agents.Writes.ARTIFACTS);
        assertThat(Agents.writes("review")).isEqualTo(Agents.Writes.ARTIFACTS);
        assertThat(Agents.writes("brew")).isEqualTo(Agents.Writes.ANY);
        assertThat(Agents.writes("run")).isEqualTo(Agents.Writes.NONE);
    }

    @Test @DisplayName("2.2: a command without its prompt yet can't start")
    void noPromptYet() {
        assertThatThrownBy(() -> session("design")).hasMessageContaining("no prompt yet");
    }

    @Test @DisplayName("2.4: the developer's words and what the tools read reach the model screened; the answer comes back restored")
    void screened() {
        answer = r -> {
            boolean readmeRead = r.messages().stream().anyMatch(m -> m.toString().contains("Owner:"));
            if (!readmeRead) {
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("1").name("read")
                                .arguments("{\"path\":\"README.md\"}").build()))).tokenUsage(new TokenUsage(10, 5)).build();
            }
            String readme = r.messages().stream()
                    .filter(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)
                    .map(m -> ((dev.langchain4j.data.message.ToolExecutionResultMessage) m).text())
                    .findFirst().orElseThrow();
            String token = readme.replaceAll("(?s).*?(\\[EMAIL_\\d+_[0-9a-f]{4}\\]).*", "$1");
            return reply("The owner is " + token + ".");
        };
        String said;
        try (var init = session("init")) {
            said = init.ask("My address is bob@acme.example; read the README.");
        }
        String everythingSent = requests.stream().flatMap(r -> r.messages().stream()).map(Object::toString)
                .reduce("", String::concat);
        assertThat(everythingSent).doesNotContain("alice@acme.example").doesNotContain("bob@acme.example");
        assertThat(said).isEqualTo("The owner is alice@acme.example.");
    }

    @Test @DisplayName("2.5: each command's use is shown when it finishes and recorded in the machine's log")
    void usage() throws IOException {
        Agents.Usage used;
        try (var plan = session("plan")) {
            plan.ask("One.");
            plan.ask("Two.");
            used = plan.usage();
        }
        assertThat(used.command()).isEqualTo("plan");
        assertThat(used.model()).isEqualTo("stand-in-model");
        assertThat(used.calls()).isEqualTo(2);
        assertThat(used.inputTokens()).isEqualTo(240);
        assertThat(used.outputTokens()).isEqualTo(60);
        assertThat(used.summary()).contains("plan").contains("2 calls").contains("240").contains("60");

        Path log = home.resolve("loop").resolve("usage.jsonl");
        assertThat(log).exists();
        List<String> lines = Files.readAllLines(log);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("\"command\":\"plan\"").contains("\"inputTokens\":240")
                .contains("\"outputTokens\":60").contains("\"project\":");
        assertThat(Agents.Usage.log()).singleElement().satisfies(u -> assertThat(u.command()).isEqualTo("plan"));
    }

    @Test @DisplayName("2.5: two commands, two records, never mixed")
    void separate() {
        try (var init = session("init")) {
            init.ask("A.");
        }
        try (var plan = session("plan")) {
            plan.ask("B.");
            plan.ask("C.");
        }
        assertThat(Agents.Usage.log()).extracting(Agents.Usage::command, Agents.Usage::calls)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("init", 1L), org.assertj.core.groups.Tuple.tuple("plan", 2L));
    }

    @Test @DisplayName("a command keeps its whole conversation, however many tools a turn calls")
    void wholeConversation() {
        int[] calls = {0};
        answer = r -> {
            if (++calls[0] <= 30) {
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("t" + calls[0]).name("list")
                                .arguments("{\"path\":\"\"}").build()))).tokenUsage(new TokenUsage(10, 5)).build();
            }
            return reply("Read.");
        };
        try (var init = session("init")) {
            assertThat(init.ask("Start a product: a recipe site.")).isEqualTo("Read.");
            init.ask("And now?");
        }
        assertThat(requests.getLast().messages()).as("the opening message, after 30 tool calls")
                .anySatisfy(m -> assertThat(m.toString()).contains("Start a product: a recipe site."));
        assertThat(requests.getLast().messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)).hasSize(30);
    }

    @Test @DisplayName("a reply with no text is an empty answer, never null")
    void noText() {
        answer = r -> ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("x").name("list").arguments("{\"path\":\"\"}").build())))
                .tokenUsage(new TokenUsage(10, 5)).build();
        int[] calls = {0};
        Function<ChatRequest, ChatResponse> toolThenNothing = answer;
        answer = r -> ++calls[0] == 1 ? toolThenNothing.apply(r)
                : ChatResponse.builder().aiMessage(AiMessage.builder().build()).tokenUsage(new TokenUsage(10, 5)).build();
        try (var init = session("init")) {
            assertThat(init.ask("Go.")).isNotNull().isEmpty();
            init.ask("Still there?");
        }
        assertThat(requests.getLast().messages()).as("no empty reply is sent back: providers refuse one")
                .noneMatch(m -> m instanceof AiMessage ai && (ai.text() == null || ai.text().isBlank())
                        && !ai.hasToolExecutionRequests());
    }

    @Test @DisplayName("the memory keeps a reply that only calls tools as it is; one with nothing in it, as \"(no reply)\"")
    void memoryKeepsReplies() {
        var memory = new Agents.WholeConversation();
        var toolsOnly = AiMessage.from(List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("1").name("list")
                .arguments("{}").build()));
        memory.add(toolsOnly);
        memory.add(AiMessage.builder().build());
        memory.add(AiMessage.from("   "));
        memory.add(AiMessage.from("Read."));
        assertThat(memory.messages()).hasSize(4);
        assertThat(memory.messages().get(0)).isSameAs(toolsOnly);
        assertThat(((AiMessage) memory.messages().get(1)).text()).isEqualTo(Agents.WholeConversation.NO_REPLY);
        assertThat(((AiMessage) memory.messages().get(2)).text()).isEqualTo(Agents.WholeConversation.NO_REPLY);
        assertThat(((AiMessage) memory.messages().get(3)).text()).isEqualTo("Read.");
    }

    @Test @DisplayName("the user's message reaches the model as the user's")
    void userMessage() {
        try (var init = session("init")) {
            init.ask("Hello there.");
        }
        assertThat(requests.getFirst().messages()).anySatisfy(m -> {
            assertThat(m).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) m).singleText()).isEqualTo("Hello there.");
        });
    }
}
