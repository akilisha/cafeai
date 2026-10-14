package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.loop.tools.GitTools;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code cafeai init} from the terminal (ROADMAP-03, phase 3). The conversation is the agent's,
 * following its prompt; the lines the code holds whatever the model does are these: the developer
 * is told what was noticed and what would leave the machine, and asked before anything is read;
 * a folder that isn't a repository is made one only with their yes, its default branch theirs to
 * name; the spec can't be finished incomplete, nor with open questions they haven't agreed to; and
 * every commit is shown and asked (in the git tools).
 */
public final class InitCommand {

    private final Path project;
    private final Developer developer;
    private final Function<Settings, AiProvider> modelFor;
    private final Supplier<Screen> screens;

    /** @param modelFor the model {@code init} uses, from the project's settings ({@link Models#forCommand}) */
    public InitCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor) {
        this(project, developer, modelFor, Screen::new);
    }

    /** With the session's screen from {@code screens}: the evals' is the same every run, so recordings replay. */
    InitCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor, Supplier<Screen> screens) {
        this.project = project;
        this.developer = developer;
        this.modelFor = modelFor;
        this.screens = screens;
    }

    /** Runs {@code init}: 0 when the spec is finished, 1 when not, 2 when it can't start. */
    public int run() {
        AiProvider model;
        try {
            model = modelFor.apply(Settings.read(project));
        } catch (RuntimeException e) {
            developer.say(e.getMessage());
            return 2;
        }
        Surface surface = Surface.of(project);

        String defaultBranch;
        if (surface.gitRepository()) {
            defaultBranch = Conversation.defaultBranch(project, developer);
        } else {
            developer.say("This folder isn't a git repository. The loop works on branches: every change it makes"
                    + " goes on a branch of its own, through your change process.");
            if (!developer.confirm("Create a git repository here?")) {
                developer.say("Then init stops here; run it again in a repository, or let it create one.");
                return 1;
            }
            String named = developer.ask("Its default branch? [main]");
            defaultBranch = named == null || named.isBlank() ? "main" : named.strip();
            GitTools.createRepository(project, defaultBranch);
        }

        boolean readAllowed = true;
        if (surface.codebase()) {
            developer.say("I found " + surface.describe() + ".\n\n"
                    + "Before writing a design spec, I'd like to read the project, so it starts from what is built:"
                    + " the structure first, then the code one package at a time, then the git history.\n"
                    + "What I read leaves this machine: it is sent to the model doing this work, " + model.modelId()
                    + " (" + model.name() + "), with secrets and personal data replaced by placeholders first.");
            readAllowed = developer.confirm("May I read the project?");
        }
        String description = developer.ask("In a sentence or two, what is the product, and who uses it? (Enter to skip)");

        var finish = new InitTools(project, surface.codebase(), developer);
        var options = new Agents.Options(readAllowed, developer, List.of(finish));
        try (Agents.Session session = Agents.session("init", model, project, defaultBranch, screens.get(), options)) {
            Conversation.converse(session, developer, kickoff(surface, readAllowed, defaultBranch, description),
                    finish::finished);
            developer.say(session.usage().summary());
        }
        return finish.finished() ? 0 : 1;
    }

    private static String kickoff(Surface surface, boolean readAllowed, String defaultBranch, String description) {
        return "The project: " + surface.describe() + ".\n"
                + "Reading the project: " + (readAllowed ? "allowed by the developer."
                        : "not allowed by the developer; don't try to read it, ask instead.") + "\n"
                + "Its default branch: " + defaultBranch + ".\n"
                + "The developer says: " + (description == null || description.isBlank() ? "nothing yet." : description)
                + "\nBegin.";
    }
}
