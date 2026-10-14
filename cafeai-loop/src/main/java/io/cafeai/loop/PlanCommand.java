package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code cafeai plan} from the terminal (ROADMAP-03, phase 4): the design spec's intended changes
 * as roadmaps and milestones. The conversation is the agent's, following its prompt; the lines the
 * code holds whatever the model does are these: it plans only from a complete spec, in a repository;
 * the developer is told what leaves the machine, and asked before the code is read (the loop's
 * artifacts are read in any case: they are what plan works from); it writes only the roadmap
 * directory, never the spec, so a change to the design goes into the spec first, by the developer;
 * and it can't finish with the plan out of format, an estimate of its own, or a number planned
 * before changed ({@link PlanCheck}).
 */
public final class PlanCommand {

    private final Path project;
    private final Developer developer;
    private final Function<Settings, AiProvider> modelFor;
    private final Supplier<Screen> screens;

    /** @param modelFor the model {@code plan} uses, from the project's settings ({@link Models#forCommand}) */
    public PlanCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor) {
        this(project, developer, modelFor, Screen::new);
    }

    /** With the session's screen from {@code screens}: the evals' is the same every run, so recordings replay. */
    PlanCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor, Supplier<Screen> screens) {
        this.project = project;
        this.developer = developer;
        this.modelFor = modelFor;
        this.screens = screens;
    }

    /** Runs {@code plan}: 0 when the plan is finished, 1 when not, 2 when it can't start. */
    public int run() {
        AiProvider model;
        try {
            model = modelFor.apply(Settings.read(project));
        } catch (RuntimeException e) {
            developer.say(e.getMessage());
            return 2;
        }
        Path spec = project.resolve("DESIGN_SPEC.md");
        if (!Files.isRegularFile(spec)) {
            developer.say("There is no design spec here yet: plan works from DESIGN_SPEC.md, which `cafeai init` writes.");
            return 2;
        }
        SpecCheck check;
        try {
            check = SpecCheck.of(Files.readString(spec, StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read DESIGN_SPEC.md", e);
        }
        if (!check.missing().isEmpty()) {
            developer.say("DESIGN_SPEC.md lacks these sections: " + String.join(", ", check.missing())
                    + ". Plan works from a complete spec: finish it with `cafeai init`, or by hand, first.");
            return 2;
        }
        if (!Surface.of(project).gitRepository()) {
            developer.say("This folder isn't a git repository: plan works on a branch of its own, through your change"
                    + " process. `cafeai init` can create one.");
            return 2;
        }

        String defaultBranch = Conversation.defaultBranch(project, developer);
        developer.say("To plan, I read the design spec and the loop's other artifacts (roadmaps, milestones, their"
                + " index). They leave this machine: they are sent to the model doing this work, " + model.modelId()
                + " (" + model.name() + "), with secrets and personal data replaced by placeholders first.\n"
                + "Where a criterion depends on the code (a column's length, a date rule, what is deployed), I'd like"
                + " to read the code too.");
        boolean readCode = developer.confirm("May I read the code as well?");

        Map<String, String> before = PlanCheck.snapshot(project);
        var finish = new PlanTools(project, before);
        var options = new Agents.Options(readCode, developer, List.of(finish));
        try (Agents.Session session = Agents.session("plan", model, project, defaultBranch, screens.get(), options)) {
            Conversation.converse(session, developer, kickoff(readCode, defaultBranch, before), finish::finished);
            developer.say(session.usage().summary());
        }
        return finish.finished() ? 0 : 1;
    }

    private static String kickoff(boolean readCode, String defaultBranch, Map<String, String> before) {
        return "Its default branch: " + defaultBranch + ".\n"
                + "Reading the code: " + (readCode ? "allowed by the developer."
                        : "not allowed by the developer; read the spec and the artifacts, and ask for what the code would"
                        + " have told you.") + "\n"
                + "Already in docs/roadmap/: " + (before.isEmpty() ? "nothing; this is the first plan."
                        : String.join(", ", before.keySet()) + ". Planning again keeps every number they have.") + "\n"
                + "Begin with DESIGN_SPEC.md.";
    }
}
