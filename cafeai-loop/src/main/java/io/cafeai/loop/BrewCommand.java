package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.loop.tools.Build;
import io.cafeai.loop.tools.GitTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code cafeai brew} from the terminal (ROADMAP-04, phase 1): the model the developer signed in to
 * builds the product from its plan, one criterion at a time, with the loop coordinating it
 * (DESIGN_SPEC.md, How `brew` builds). The lines the code holds, whatever the model does: it starts
 * from the first criterion not yet met, read from the artifacts alone, and says what is held back;
 * the developer is told which model the code goes to, not asked (running brew is the permission); the
 * project's standards reach the model in full; nothing is written on the default branch, and every
 * commit is shown and asked (in the tools).
 */
public final class BrewCommand {

    private final Path project;
    private final Developer developer;
    private final Function<Settings, AiProvider> modelFor;
    private final Supplier<Screen> screens;
    private final Function<Path, Optional<Build>> builds;

    /** @param modelFor the model {@code brew} uses, from the project's settings ({@link Models#forCommand}) */
    public BrewCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor) {
        this(project, developer, modelFor, Screen::new, Build::of);
    }

    /** With the session's screen from {@code screens}: the evals' is the same every run, so recordings replay. */
    BrewCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor, Supplier<Screen> screens) {
        this(project, developer, modelFor, screens, Build::of);
    }

    /** With the project's build from {@code builds}: a stand-in, for tests. */
    BrewCommand(Path project, Developer developer, Function<Settings, AiProvider> modelFor, Supplier<Screen> screens,
                Function<Path, Optional<Build>> builds) {
        this.project = project;
        this.developer = developer;
        this.modelFor = modelFor;
        this.screens = screens;
        this.builds = builds;
    }

    /** Runs {@code brew}: 0 when there is nothing left or the criterion is done, 1 when not, 2 when it can't start. */
    public int run() {
        AiProvider model;
        try {
            model = modelFor.apply(Settings.read(project));
        } catch (RuntimeException e) {
            developer.say(e.getMessage());
            return 2;
        }
        Progress progress = Progress.of(PlanCheck.snapshot(project));
        if (progress.total() == 0) {
            developer.say("There is no plan here yet: brew builds from the roadmaps in docs/roadmap/, which"
                    + " `cafeai plan` writes.");
            return 2;
        }
        if (!Surface.of(project).gitRepository()) {
            developer.say("This folder isn't a git repository: brew works on a branch of its own, through your change"
                    + " process. `cafeai init` can create one.");
            return 2;
        }
        if (progress.next().isEmpty()) {
            developer.say("Nothing left to brew: every criterion is met, waived or cancelled (" + progress.met() + " of "
                    + progress.total() + ")" + held(progress) + ".");
            return 0;
        }
        Optional<Build> found = builds.apply(project);
        if (found.isEmpty()) {
            developer.say("brew builds with the project's own Gradle or Maven, and found neither here (a Gradle wrapper,"
                    + " or a pom.xml with Maven's wrapper or Maven on the PATH).");
            return 2;
        }
        Build build = found.get();
        Progress.Next next = progress.next().get();
        developer.say("Where the plan stands: " + progress.met() + " of " + progress.total() + " criteria done"
                + held(progress) + ".\nNext: " + where(next) + ": " + next.text());

        String defaultBranch = Conversation.defaultBranch(project, developer);
        // No permission asked: running brew on a project the developer planned is the permission; brew stops to ask
        // only at a real fork (brew.md, §3). Which model the code goes to is said, not asked.
        developer.say("Building with " + model.modelId() + " (" + model.name() + "); secrets and personal data are"
                + " replaced by placeholders before anything is sent.");

        Screen screen = screens.get();
        var tools = new BrewTools(project, build, new GitTools(project, defaultBranch), screen);
        var options = new Agents.Options(true, developer, List.of(tools), tools::refuseWrite);
        try (Agents.Session session = Agents.session("brew", model, project, defaultBranch, screen, options)) {
            Conversation.converse(session, developer, kickoff(progress, next, defaultBranch, build), tools::finished);
            developer.say(session.usage().summary());
        }
        return tools.finished() ? 0 : 1;
    }

    private static String where(Progress.Next next) {
        return next.roadmap() + ", phase " + next.phase() + " (" + next.phaseName() + "), criterion " + next.criterion();
    }

    private static String held(Progress progress) {
        return progress.heldBack().isEmpty() ? "" : "; held back: " + String.join("; ", progress.heldBack());
    }

    private String kickoff(Progress progress, Progress.Next next, String defaultBranch, Build build) {
        return "Where the plan stands: " + progress.met() + " of " + progress.total() + " criteria done"
                + held(progress) + ".\n"
                + "Next: " + where(next) + ": " + next.text() + "\n"
                + "The phase's objective: " + (next.objective().isEmpty() ? "none written." : next.objective()) + "\n"
                + "Its testing criteria: " + (next.testing().isEmpty() ? "none written." : next.testing()) + "\n"
                + standards() + "\n"
                + "Its build: " + build.name() + ", run by the loop through your tools (testsFirst, testsPass, mutate).\n"
                + "Its default branch: " + defaultBranch + ". Work on a branch of its own for this phase, named by the"
                + " project's conventions; if you are already on it, carry on there.\n"
                + "Begin.";
    }

    /** The project's standards in full, or that there are none. */
    private String standards() {
        Path file = project.resolve("docs").resolve("STANDARDS.md");
        if (!Files.isRegularFile(file)) return "The project's standards: there is no docs/STANDARDS.md.";
        try {
            return "The project's standards (docs/STANDARDS.md), to follow:\n" + Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read docs/STANDARDS.md", e);
        }
    }
}
