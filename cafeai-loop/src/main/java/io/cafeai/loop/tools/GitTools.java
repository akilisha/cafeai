package io.cafeai.loop.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.cafeai.core.internal.Cli;
import io.cafeai.loop.Screen;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * An agent's hands on the project's git repository (ROADMAP-03, 1.3): it starts a working branch
 * and commits to it. It never commits to the default branch, never merges, never pushes: those are
 * the project's change process, and the developer's. Only the git subcommands below can be run at
 * all, so none of that can be reached another way.
 *
 * <p>The branch's name is the caller's: the agent applies the project's conventions (its design
 * spec's Conventions); these tools only refuse what is never allowed.
 */
public final class GitTools {

    /** The only git subcommands the loop runs. Not merge, push, rebase, reset, checkout or a bare switch. */
    static final Set<String> ALLOWED = Set.of("rev-parse", "status", "check-ref-format", "switch", "add", "commit", "log");

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path root;
    private final String defaultBranch;
    private final Function<List<String>, Cli.Result> runner;
    private final String git;
    private final Screen screen;

    /** @param defaultBranch the project's default branch, from its conventions (often {@code main}) */
    public GitTools(Path root, String defaultBranch) {
        this(root, defaultBranch, Screen.none());
    }

    /** Git tools for a command's agent: what it commits (the message, the paths) comes back through {@code screen}, restored. */
    public GitTools(Path root, String defaultBranch, Screen screen) {
        this(root, defaultBranch, command -> Cli.run(command, TIMEOUT),
                Cli.find("git", System::getenv).map(Path::toString).orElse("git"), screen);
    }

    GitTools(Path root, String defaultBranch, Function<List<String>, Cli.Result> runner, String git, Screen screen) {
        this.root = Objects.requireNonNull(root, "root");
        this.defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch");
        this.runner = runner;
        this.git = git;
        this.screen = screen;
    }

    /** The branch checked out now; {@code HEAD} when none is (a detached head). */
    public String currentBranch() {
        return git("rev-parse", "--abbrev-ref", "HEAD").strip();
    }

    /** Whether work may be written and committed now: on a branch, and not the default one. */
    public boolean onWorkingBranch() {
        String current = currentBranch();
        return !current.equals("HEAD") && !current.equals(defaultBranch);
    }

    @Tool("Starts a new working branch from where the project is now, and switches to it.")
    public String startBranch(@P("the branch's name, following the project's conventions") String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name the branch");
        if (name.equals(defaultBranch)) {
            throw new IllegalArgumentException("'" + name + "' is the default branch; work goes on a branch of its own");
        }
        if (!run(List.of("check-ref-format", "--branch", name)).ok()) {
            throw new IllegalArgumentException("Not a valid branch name: " + name);
        }
        git("switch", "-c", name);                     // git itself refuses a branch that already exists
        return "On a new branch, " + name;
    }

    @Tool("Commits the given files to the working branch, with the given message. Never on the default branch.")
    public String commit(@P("the commit message, in the project's style") String message,
                         @P("the files to commit, relative to the project's root") List<String> paths) {
        return commit(message, paths, false);
    }

    /**
     * {@link #commit(String, List)}, with a {@code Signed-off-by} trailer only when the developer has
     * said so: a sign-off is their personal certification, never the agent's to add.
     */
    public String commit(String message, List<String> paths, boolean signedOffByTheDeveloper) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("A commit needs a message");
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("Say which files to commit");
        if (!onWorkingBranch()) {
            throw new IllegalStateException("Commits go on a working branch, never on the default branch ('"
                    + defaultBranch + "') or a detached head: start a branch first");
        }
        var add = new ArrayList<>(List.of("add", "--"));
        paths.forEach(path -> add.add(screen.in(path)));
        git(add.toArray(String[]::new));
        var commit = new ArrayList<>(List.of("commit", "-m", screen.in(message)));
        if (signedOffByTheDeveloper) commit.add("--signoff");
        git(commit.toArray(String[]::new));
        return git("log", "-1", "--format=%h").strip();
    }

    /** Runs an allowed git subcommand in the project, or refuses; a failure is reported with git's words. */
    String git(String... args) {
        Cli.Result result = run(List.of(args));
        if (!result.ok()) {
            throw new IllegalStateException("git " + args[0] + " failed: " + firstLine(result.err().isBlank() ? result.out() : result.err()));
        }
        return result.out();
    }

    private Cli.Result run(List<String> args) {
        if (args.isEmpty() || !ALLOWED.contains(args.get(0))) {
            throw new IllegalArgumentException("git " + (args.isEmpty() ? "" : args.get(0))
                    + " is not something the loop does: merging, pushing and the like are the developer's");
        }
        if (args.get(0).equals("switch") && !(args.size() == 3 && args.get(1).equals("-c"))) {
            throw new IllegalArgumentException("The loop only switches to a branch it has just created");
        }
        var command = new ArrayList<String>();
        command.add(git);
        command.addAll(List.of("-C", root.toString()));
        command.addAll(args);
        return runner.apply(command);
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }
}
