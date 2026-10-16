package io.cafeai.loop.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.cafeai.core.internal.Cli;
import io.cafeai.loop.Developer;
import io.cafeai.loop.Screen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An agent's hands on the project's git repository (ROADMAP-03, 1.3 and 3.6): it starts a working
 * branch and commits to it, and reads the history when allowed. It never commits to the default
 * branch, never merges, never pushes: those are the project's change process, and the developer's.
 * Only the git subcommands below can be run at all, so none of that can be reached another way.
 * With a developer to ask, every commit is shown to them and made only with their yes, and a
 * sign-off only with a second, explicit yes.
 *
 * <p>The branch's name is the caller's: the agent applies the project's conventions (its design
 * spec's Conventions); these tools only refuse what is never allowed.
 */
public final class GitTools {

    /** The only git subcommands the loop runs. Not merge, push, rebase, reset, checkout or a bare switch. */
    static final Set<String> ALLOWED = Set.of("symbolic-ref", "status", "check-ref-format", "switch", "add", "commit",
            "log", "for-each-ref", "diff");

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String NOT_ALLOWED = "The developer hasn't allowed reading the project.";

    private final Path root;
    private final String defaultBranch;
    private final Function<List<String>, Cli.Result> runner;
    private final String git;
    private final Screen screen;
    private final BooleanSupplier readAllowed;
    private final Developer approver;

    /** @param defaultBranch the project's default branch, from its conventions (often {@code main}) */
    public GitTools(Path root, String defaultBranch) {
        this(root, defaultBranch, Screen.none());
    }

    /** Git tools for a command's agent: what it commits (the message, the paths) comes back through {@code screen}, restored. */
    public GitTools(Path root, String defaultBranch, Screen screen) {
        this(root, defaultBranch, screen, () -> true, null);
    }

    /**
     * Git tools for a command's agent that may read the history only when {@code readAllowed}, and
     * whose every commit is put to {@code approver} first ({@code null}: not asked).
     */
    public GitTools(Path root, String defaultBranch, Screen screen, BooleanSupplier readAllowed, Developer approver) {
        this(root, defaultBranch, command -> Cli.run(command, TIMEOUT), gitPath(), screen, readAllowed, approver);
    }

    GitTools(Path root, String defaultBranch, Function<List<String>, Cli.Result> runner, String git, Screen screen,
             BooleanSupplier readAllowed, Developer approver) {
        this.root = Objects.requireNonNull(root, "root");
        this.defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch");
        this.runner = runner;
        this.git = git;
        this.screen = screen;
        this.readAllowed = readAllowed;
        this.approver = approver;
    }

    /** Creates a git repository at {@code root}, its default branch named {@code defaultBranch}: the developer's yes first. */
    public static void createRepository(Path root, String defaultBranch) {
        var check = Cli.run(List.of(gitPath(), "check-ref-format", "--branch", defaultBranch), TIMEOUT);
        if (!check.ok()) throw new IllegalArgumentException("Not a valid branch name: " + defaultBranch);
        var init = Cli.run(List.of(gitPath(), "-C", root.toString(), "init", "-b", defaultBranch), TIMEOUT);
        if (!init.ok()) throw new IllegalStateException("git init failed: " + init.err().strip());
    }

    /** The default branch the repository's remote names ({@code origin/HEAD}), if it names one. */
    public static Optional<String> remoteDefault(Path root) {
        var result = Cli.run(List.of(gitPath(), "-C", root.toString(), "symbolic-ref", "--short", "-q",
                "refs/remotes/origin/HEAD"), TIMEOUT);
        String ref = result.ok() ? result.out().strip() : "";
        return ref.contains("/") ? Optional.of(ref.substring(ref.indexOf('/') + 1)) : Optional.empty();
    }

    /** The branch checked out now, even one with no commits yet; {@code HEAD} when none is (a detached head). */
    public String currentBranch() {
        Cli.Result result = run(List.of("symbolic-ref", "--short", "-q", "HEAD"));
        return result.ok() ? result.out().strip() : "HEAD";
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

    @Tool("The project's git history, read only: its branches, its recent commit subjects, how often merges,"
            + " sign-offs (Signed-off-by) and co-author lines appear, and which hooks are installed.")
    public String history() {
        if (!readAllowed.getAsBoolean()) throw new IllegalStateException(NOT_ALLOWED);
        var b = new StringBuilder();
        b.append("Branches:\n").append(indent(run(List.of("for-each-ref", "--format=%(refname:short)",
                "refs/heads", "refs/remotes")).out()));
        Cli.Result subjects = run(List.of("log", "-n", "50", "--format=%s"));
        if (!subjects.ok()) {
            b.append("No commits yet.\n");
        } else {
            b.append("Recent commit subjects, newest first:\n").append(indent(subjects.out()));
            String bodies = run(List.of("log", "-n", "300", "--format=%B%x00")).out();
            long commits = bodies.chars().filter(c -> c == 0).count();
            long merges = run(List.of("log", "-n", "300", "--merges", "--format=%h")).out().lines().count();
            String lower = bodies.toLowerCase(Locale.ROOT);
            b.append("Of the last ").append(commits).append(" commits: ").append(merges).append(" merges, ")
                    .append(count(lower, "\nsigned-off-by:") + (lower.startsWith("signed-off-by:") ? 1 : 0))
                    .append(" Signed-off-by lines, ").append(count(lower, "co-authored-by:")).append(" Co-authored-by lines.\n");
        }
        b.append("Hooks installed: ").append(hooks()).append('\n');
        return screen.out(b.toString());
    }

    @Tool(name = "commit", value = "Commits the given files to the working branch with the given message, once the"
            + " developer has seen what will be committed and said yes. Never on the default branch. signOff: true only"
            + " when the project's conventions require a Signed-off-by line; the developer is asked separately, and"
            + " nothing is committed without their own yes.")
    public String commitAsked(@P("the commit message, in the project's style") String message,
                              @P("the files to commit, relative to the project's root") List<String> paths,
                              @P("whether the project's conventions require a sign-off") boolean signOff) {
        if (approver == null) {
            if (signOff) return "A sign-off needs the developer's own yes, and there is no developer to ask; nothing was committed.";
            return commit(message, paths, false);
        }
        String shown = "Commit to " + currentBranch() + ":\n  files: "
                + String.join(", ", paths == null ? List.of() : paths.stream().map(screen::in).toList())
                + "\n  message: " + screen.in(message) + "\nCommit it?";
        if (!approver.confirm(shown)) return "The developer declined this commit; nothing was committed.";
        if (signOff && !approver.confirm("The project asks for a sign-off (a Signed-off-by line): your personal"
                + " certification that you may submit this work. Sign off in your name?")) {
            return "The developer didn't sign off; nothing was committed.";
        }
        return commit(message, paths, signOff);
    }

    /** Commits the files with the message, no sign-off; not asked of anyone (the developer's own tools, tests). */
    public String commit(String message, List<String> paths) {
        return commit(message, paths, false);
    }

    /**
     * Commits the files with the message, with a {@code Signed-off-by} trailer only when the developer has
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

    /**
     * The lines changed under {@code folder} since the default branch, in the working tree, by file
     * (relative, with '/'): each a range of the file's lines now, first to last. A new file is changed
     * whole; a deletion leaves no line to name, so it holds none. For {@code brew}: where the code a
     * criterion changed is, so each change can be broken on purpose.
     */
    public Map<String, List<int[]>> changedLines(String folder) {
        var changed = new TreeMap<String, List<int[]>>();
        String file = null;
        for (String line : git("diff", "-U0", "--no-color", "--no-ext-diff", defaultBranch, "--", folder).split("\\R")) {
            if (line.startsWith("+++ ")) {
                file = line.equals("+++ /dev/null") ? null : line.substring(line.indexOf('/') + 1);
            } else if (line.startsWith("@@") && file != null) {
                Matcher m = HUNK.matcher(line);
                if (!m.find()) continue;
                int start = Integer.parseInt(m.group(1));
                int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                if (count > 0) changed.computeIfAbsent(file, f -> new ArrayList<>()).add(new int[] {start, start + count - 1});
            }
        }
        for (String line : git("status", "--porcelain", "--untracked-files=all", "--", folder).split("\\R")) {
            if (!line.startsWith("?? ")) continue;
            String path = line.substring(3).strip();
            try {
                long lines = Files.readAllLines(root.resolve(path)).size();
                if (lines > 0) changed.put(path, List.of(new int[] {1, (int) lines}));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + path, e);
            }
        }
        return changed;
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\S+ \\+(\\d+)(?:,(\\d+))? @@");

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

    private String hooks() {
        Path dir = root.resolve(".git").resolve("hooks");
        if (!Files.isDirectory(dir)) return "none";
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(".sample")).sorted().toList();
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String indent(String lines) {
        var b = new StringBuilder();
        lines.lines().filter(l -> !l.isBlank()).forEach(l -> b.append("  ").append(l).append('\n'));
        return b.length() == 0 ? "  none\n" : b.toString();
    }

    private static long count(String text, String needle) {
        long n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String gitPath() {
        return Cli.find("git", System::getenv).map(Path::toString).orElse("git");
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }
}
