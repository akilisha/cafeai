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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the commands' tests share: a stand-in model following a script, a scripted developer, and a
 * project in real git. What the model says is the script's; what the tests check is what the code
 * enforces whatever the model does.
 */
abstract class CommandTestBase {

    @TempDir Path project;
    @TempDir Path home;
    private String before;
    String git;
    final List<ChatRequest> requests = new ArrayList<>();
    final Deque<Function<ChatRequest, ChatResponse>> script = new ArrayDeque<>();

    /** A developer answering from a script, and the record of what they were told and asked. */
    final class Scripted implements Developer {
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

    final Scripted developer = new Scripted();

    final class StandIn implements AiProvider, LangchainBridge.ChatModelAccess {
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

    static ChatResponse say(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(10, 5)).build();
    }

    static Function<ChatRequest, ChatResponse> tool(String name, String arguments) {
        return r -> ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id(name + arguments.hashCode()).name(name).arguments(arguments).build())))
                .tokenUsage(new TokenUsage(10, 5)).build();
    }

    static Function<ChatRequest, ChatResponse> reply(String text) {
        return r -> say(text);
    }

    /** The agent writing a file, as a tool call. */
    static Function<ChatRequest, ChatResponse> write(String path, String content) {
        return tool("write", "{\"path\":\"" + path + "\",\"content\":" + json(content) + "}");
    }

    static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** What the tools answered, in the last request to the model. */
    String toolResults() {
        return requests.getLast().messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).reduce("", (a, b) -> a + b + "\n");
    }

    /** Everything sent to the model, in every request. */
    String sent() {
        return requests.stream().flatMap(r -> r.messages().stream()).map(Object::toString).reduce("", String::concat);
    }

    static final String SPEC = """
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

    Cli.Result raw(String... args) {
        var command = new ArrayList<>(List.of(git, "-C", project.toString()));
        command.addAll(List.of(args));
        return Cli.run(command, Duration.ofSeconds(30));
    }

    void existingRepository() throws IOException {
        Files.writeString(project.resolve("README.md"), "# Demo\nThe secret ingredient is nutmeg.\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        raw("init", "-b", "main");
        raw("config", "user.name", "Test Developer");
        raw("config", "user.email", "dev@example.com");
        raw("config", "commit.gpgsign", "false");
        raw("add", ".");
        raw("commit", "-m", "Initial commit");
    }

    @BeforeEach
    void setUpCommand() {
        git = Cli.find("git", System::getenv).map(Path::toString).orElse(null);
        assumeTrue(git != null, "git isn't installed");
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
    }

    @AfterEach
    void tearDownCommand() {
        if (before == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", before);
    }
}
