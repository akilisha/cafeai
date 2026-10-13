package io.cafeai.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    public enum Writes { ARTIFACTS, ANY, NONE }

    /** The interface the agent answers through: one message in, its reply out. */
    public interface CommandAgent {
        String chat(String message);
    }

    private Agents() {}

    /** What a command may write ({@code DESIGN_SPEC.md}, the agents' tool table). */
    public static Writes writes(String command) {
        return switch (command) {
            case "init", "design", "plan", "review" -> Writes.ARTIFACTS;
            case "brew" -> Writes.ANY;
            case "run" -> Writes.NONE;
            default -> throw new IllegalArgumentException("'" + command + "' is not one of the loop's commands");
        };
    }

    /** A session of {@code command} in the project, with the model the settings name for it. */
    public static Session session(String command, Settings settings, Path project, String defaultBranch) {
        return session(command, Models.forCommand(settings, command), project, defaultBranch, new Screen());
    }

    /** A session of {@code command} in the project, with the given model and screen. */
    public static Session session(String command, AiProvider model, Path project, String defaultBranch, Screen screen) {
        return new Session(command, model, project, defaultBranch, screen);
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

        private Session(String command, AiProvider model, Path project, String defaultBranch, Screen screen) {
            Prompts.Prompt prompt = Prompts.of(command);
            this.command = command;
            this.model = model;
            this.project = project;
            this.screen = screen;
            this.app = CafeAI.create();
            AgentConfig<CommandAgent> config = app.agent(command, CommandAgent.class)
                    .system(prompt.text()).model(model).memory(MemoryStrategy.inMemory());
            for (Object tool : tools(command, project, defaultBranch, screen)) config.tool(tool);
            this.agent = app.agent(command, CommandAgent.class, command);
        }

        static List<Object> tools(String command, Path project, String defaultBranch, Screen screen) {
            Writes writes = writes(command);
            if (writes == Writes.NONE) return List.of();
            var git = new GitTools(project, defaultBranch, screen);
            var files = new ProjectFiles(project, git::onWorkingBranch, Prompts.stamp(command), screen,
                    writes == Writes.ARTIFACTS);
            return List.of(files, git);
        }

        /** Says {@code message} to the agent, screened; its reply comes back restored. */
        public String ask(String message) {
            if (closed) throw new IllegalStateException("This session of `cafeai " + command + "` has finished");
            return screen.in(agent.chat(screen.out(message)));
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
