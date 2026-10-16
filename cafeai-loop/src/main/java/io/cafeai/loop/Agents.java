package io.cafeai.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import io.cafeai.core.CafeAI;
import io.cafeai.core.agents.AgentConfig;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.internal.SavedKeys;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.loop.tools.GitTools;
import io.cafeai.loop.tools.ProjectFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Each command an agent of its own (ROADMAP-03, phase 2; {@code DESIGN_SPEC.md}, The agents): its
 * own prompt (a versioned resource), its own model (from the settings, through CafeAI's credential
 * lookup), only its own tools, everything it sends screened, and its usage recorded against it.
 *
 * <p>A command runs as a {@link Session} on a CafeAI application of its own, so its model use is
 * its own: when it closes, the use is shown and recorded in the machine's log.
 */
public final class Agents {

    /** What a command's agent may write. */
    public enum Writes {
        ARTIFACTS(ProjectFiles.Scope.ARTIFACTS), ROADMAPS(ProjectFiles.Scope.ROADMAPS), ANY(ProjectFiles.Scope.ANY),
        NONE(ProjectFiles.Scope.NONE);

        private final ProjectFiles.Scope scope;

        Writes(ProjectFiles.Scope scope) {
            this.scope = scope;
        }
    }

    /** The interface the agent answers through: one message in, its reply out. */
    public interface CommandAgent {
        String chat(String message);
    }

    private Agents() {}

    /** What a command may write ({@code DESIGN_SPEC.md}, the agents' tool table). */
    public static Writes writes(String command) {
        return switch (command) {
            case "init", "design", "review" -> Writes.ARTIFACTS;
            case "plan" -> Writes.ROADMAPS;
            case "brew" -> Writes.ANY;
            case "run" -> Writes.NONE;
            default -> throw new IllegalArgumentException("'" + command + "' is not one of the loop's commands");
        };
    }

    /**
     * What a command reads whatever the developer answered about reading the project: {@code plan}
     * the loop's artifacts, its inputs; the others nothing.
     */
    public static ProjectFiles.Scope readableAnyway(String command) {
        return command.equals("plan") ? ProjectFiles.Scope.ARTIFACTS : ProjectFiles.Scope.NONE;
    }

    /** A session of {@code command} in the project, with the model the settings name for it. */
    public static Session session(String command, Settings settings, Path project, String defaultBranch) {
        return session(command, Models.forCommand(settings, command), project, defaultBranch, new Screen());
    }

    /** A session of {@code command} in the project, with the given model and screen. */
    public static Session session(String command, AiProvider model, Path project, String defaultBranch, Screen screen) {
        return session(command, model, project, defaultBranch, screen, Options.DEFAULT);
    }

    /** A session of {@code command} in the project, with the given model, screen and options. */
    public static Session session(String command, AiProvider model, Path project, String defaultBranch, Screen screen,
                                  Options options) {
        return new Session(command, model, project, defaultBranch, screen, options);
    }

    /**
     * How a session's tools behave: whether the agent may read the project (the developer's
     * permission), who is asked before each commit ({@code null}: nobody), and tools of the
     * command's own (for {@code init}, {@code finish}).
     */
    public record Options(boolean readAllowed, Developer developer, List<Object> extraTools,
                          Function<String, String> writeGate) {
        public static final Options DEFAULT = new Options(true, null, List.of());

        public Options {
            extraTools = List.copyOf(extraTools);
            Objects.requireNonNull(writeGate, "writeGate");
        }

        /** No gate on writing beyond the command's own scope. */
        public Options(boolean readAllowed, Developer developer, List<Object> extraTools) {
            this(readAllowed, developer, extraTools, path -> null);
        }
    }

    /** One command's conversation with its agent. Closing it records its use. */
    public static final class Session implements AutoCloseable {
        private final String command;
        private final AiProvider model;
        private final Path project;
        private final Screen screen;
        private final CafeAI app;
        private final CommandAgent agent;
        private boolean closed;

        private Session(String command, AiProvider model, Path project, String defaultBranch, Screen screen,
                        Options options) {
            Prompts.Prompt prompt = Prompts.of(command);
            this.command = command;
            this.model = model;
            this.project = project;
            this.screen = screen;
            this.app = CafeAI.create();
            // The whole conversation, not CafeAI's window of recent messages: reading a codebase, one turn can
            // call a tool dozens of times, and a window would drop the developer's opening words with them.
            AgentConfig<CommandAgent> config = app.agent(command, CommandAgent.class)
                    .system(prompt.text()).model(model).memory(MemoryStrategy.inMemory())
                    .configure(services -> services.chatMemory(new WholeConversation()));
            for (Object tool : tools(command, project, defaultBranch, screen, options)) config.tool(tool);
            this.agent = app.agent(command, CommandAgent.class, command);
        }

        static List<Object> tools(String command, Path project, String defaultBranch, Screen screen) {
            return tools(command, project, defaultBranch, screen, Options.DEFAULT);
        }

        static List<Object> tools(String command, Path project, String defaultBranch, Screen screen, Options options) {
            Writes writes = writes(command);
            if (writes == Writes.NONE) return List.copyOf(options.extraTools());
            var git = new GitTools(project, defaultBranch, screen, options::readAllowed, options.developer());
            var files = new ProjectFiles(project, git::onWorkingBranch, Prompts.stamp(command), screen, writes.scope,
                    options::readAllowed, readableAnyway(command)).writeGate(options.writeGate());
            var tools = new ArrayList<Object>(List.of(files, git));
            tools.addAll(options.extraTools());
            return tools;
        }

        /** Says {@code message} to the agent, screened; its reply comes back restored, empty when it said nothing. */
        public String ask(String message) {
            if (closed) throw new IllegalStateException("This session of `cafeai " + command + "` has finished");
            String reply = agent.chat(screen.out(message));
            return reply == null ? "" : screen.in(reply);
        }

        /** The command's model use so far. */
        public Usage usage() {
            UsageReport.RouteUsage used = app.usage().route(UsageReport.NO_REQUEST).orElse(null);
            return new Usage(Instant.now(), project.toAbsolutePath().toString(), command, model.modelId(),
                    used == null ? 0 : used.calls(), used == null ? 0 : used.inputTokens(),
                    used == null ? 0 : used.outputTokens());
        }

        /** Finishes the session: its use is recorded in the machine's log. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            usage().record();
        }
    }

    /**
     * A command's memory: the whole conversation. A reply with nothing in it (no text, no tool call) is
     * kept as "(no reply)": sent back empty on the next turn, providers refuse it (OpenAI wants content,
     * Anthropic a non-empty text).
     */
    static final class WholeConversation implements ChatMemory {
        static final String NO_REPLY = "(no reply)";
        private final ChatMemory memory = MessageWindowChatMemory.withMaxMessages(Integer.MAX_VALUE);

        @Override public Object id() { return memory.id(); }
        @Override public List<ChatMessage> messages() { return memory.messages(); }
        @Override public void clear() { memory.clear(); }

        @Override
        public void add(ChatMessage message) {
            boolean empty = message instanceof AiMessage ai && (ai.text() == null || ai.text().isBlank())
                    && !ai.hasToolExecutionRequests();
            memory.add(empty ? AiMessage.from(NO_REPLY) : message);
        }
    }

    /** A command's model use: tokens for now (CafeAI ships no price list). */
    public record Usage(Instant at, String project, String command, String model, long calls, long inputTokens,
                        long outputTokens) {

        private static final ObjectMapper JSON = new ObjectMapper();

        /** Where every command's use on this machine is recorded: not in the project (cost data isn't a project artifact). */
        public static Path file() {
            return SavedKeys.dir().resolve("loop").resolve("usage.jsonl");
        }

        /** How it is shown when the command finishes. */
        public String summary() {
            return "cafeai " + command + ": " + calls + (calls == 1 ? " call, " : " calls, ") + inputTokens + " input and "
                    + outputTokens + " output tokens (" + model + ")";
        }

        void record() {
            ObjectNode line = JSON.createObjectNode()
                    .put("at", at.toString()).put("project", project).put("command", command).put("model", model)
                    .put("calls", calls).put("inputTokens", inputTokens).put("outputTokens", outputTokens);
            try {
                Files.createDirectories(file().getParent());
                Files.writeString(file(), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not record the use of `cafeai " + command + "` in " + file(), e);
            }
        }

        /** Every use recorded on this machine, oldest first. */
        public static List<Usage> log() {
            if (!Files.isRegularFile(file())) return List.of();
            var uses = new ArrayList<Usage>();
            try {
                for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode n = JSON.readTree(line);
                    uses.add(new Usage(Instant.parse(n.path("at").asText()), n.path("project").asText(),
                            n.path("command").asText(), n.path("model").asText(), n.path("calls").asLong(),
                            n.path("inputTokens").asLong(), n.path("outputTokens").asLong()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + file(), e);
            }
            return uses;
        }
    }
}
