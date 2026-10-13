package io.cafeai.loop.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.cafeai.loop.Screen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An agent's hands on the project's files: reading, listing and searching inside the project, and
 * writing inside it on the working branch (ROADMAP-03, 1.2). Nothing outside the project can be
 * read or written, by any path: a parent step ({@code ..}), an absolute path, or a symbolic link
 * pointing out. The git directory is out of bounds too: it holds the remotes' addresses and the
 * history, which are the git tools' business.
 */
public final class ProjectFiles {

    /** The most a search reports, and the largest file it reads. */
    static final int MAX_MATCHES = 200;
    static final long MAX_SEARCHED_BYTES = 1_000_000;

    /**
     * Parts of the project, by path: what a command may write, and what it may read without the
     * developer's yes. The loop's artifacts carry the stamp of the version and command that wrote them.
     */
    public enum Scope {
        ANY(".*", "anything"),
        NONE("(?!)", "nothing"),
        ARTIFACTS("DESIGN_SPEC\\.md|CHANGELOG\\.md|docs/roadmap/[^/]+\\.md|docs/design/.+\\.md",
                "the loop's artifacts (DESIGN_SPEC.md, CHANGELOG.md, docs/roadmap/, docs/design/)"),
        ROADMAPS("docs/roadmap/[^/]+\\.md", "the roadmaps, their milestones and the index (docs/roadmap/); the design"
                + " spec is the developer's to change (or `cafeai design`'s), then plan runs again");

        private final Pattern paths;
        private final String description;

        Scope(String paths, String description) {
            this.paths = Pattern.compile(paths);
            this.description = description;
        }

        /** Whether the path, relative to the project's root with '/' between names, is in this scope. */
        public boolean covers(String relative) {
            return paths.matcher(relative).matches();
        }
    }

    private static final Pattern STAMP = Pattern.compile("<!-- cafeai-loop .* -->");

    private final Path root;
    private final BooleanSupplier onWorkingBranch;
    private final String stamp;
    private final Screen screen;
    private final Scope writable;
    private final BooleanSupplier readAllowed;
    private final Scope readableAnyway;

    /**
     * @param root            the project's root directory
     * @param onWorkingBranch whether writing is allowed now: true only on a working branch, never on
     *                        the default branch ({@link GitTools#onWorkingBranch()})
     */
    public ProjectFiles(Path root, BooleanSupplier onWorkingBranch) {
        this(root, onWorkingBranch, null);
    }

    /**
     * Tools for a command's agent: every artifact it writes starts with {@code stamp}
     * ({@code Prompts.stamp(command)}), replacing an earlier one.
     */
    public ProjectFiles(Path root, BooleanSupplier onWorkingBranch, String stamp) {
        this(root, onWorkingBranch, stamp, Screen.none(), false);
    }

    /**
     * Tools for a command's agent, screened: what they read leaves through {@code screen}, what they
     * write comes back through it, restored. With {@code artifactsOnly}, only the loop's artifacts
     * can be written (the commands that plan rather than build).
     */
    public ProjectFiles(Path root, BooleanSupplier onWorkingBranch, String stamp, Screen screen, boolean artifactsOnly) {
        this(root, onWorkingBranch, stamp, screen, artifactsOnly, () -> true);
    }

    /** As above, reading only when {@code readAllowed}: the developer's permission, asked for by {@code init}. */
    public ProjectFiles(Path root, BooleanSupplier onWorkingBranch, String stamp, Screen screen, boolean artifactsOnly,
                        BooleanSupplier readAllowed) {
        this(root, onWorkingBranch, stamp, screen, artifactsOnly ? Scope.ARTIFACTS : Scope.ANY, readAllowed, Scope.NONE);
    }

    /**
     * Tools for a command's agent that writes only what is in {@code writable}, and reads only when
     * {@code readAllowed}, except what is in {@code readableAnyway}, which it reads by path whatever
     * the answer ({@code plan}: the loop's artifacts, its inputs).
     */
    public ProjectFiles(Path root, BooleanSupplier onWorkingBranch, String stamp, Screen screen, Scope writable,
                        BooleanSupplier readAllowed, Scope readableAnyway) {
        this.readAllowed = readAllowed;
        this.readableAnyway = readableAnyway;
        this.stamp = stamp;
        this.screen = screen;
        this.writable = writable;
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("The project's root doesn't exist: " + root, e);
        }
        this.onWorkingBranch = onWorkingBranch;
    }

    @Tool("Reads a file of the project, by its path from the project's root.")
    public String read(@P("the file's path, relative to the project's root") String path) {
        Path file = inside(screen.in(path));
        if (!readAllowed.getAsBoolean() && !readableAnyway.covers(relative(file))) throw notAllowed();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No such file in the project: " + path);
        try {
            return screen.out(Files.readString(file, StandardCharsets.UTF_8));
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("Not a text file: " + path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    @Tool("Lists a directory of the project: its files, and its directories ending in '/'.")
    public List<String> list(@P("the directory's path, relative to the project's root; empty for the root") String path) {
        mayRead();
        Path dir = inside(screen.in(path));
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("No such directory in the project: " + path);
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> !isGitDirectory(p))
                    .map(p -> relative(p) + (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                    .sorted().map(screen::out).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + path, e);
        }
    }

    @Tool("Searches the project's text files for lines containing the given text, ignoring case.")
    public List<String> search(@P("the text to look for") String text) {
        mayRead();
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Say what to search for");
        String needle = screen.in(text).toLowerCase(Locale.ROOT);
        var matches = new ArrayList<String>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return isGitDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || attributes.size() > MAX_SEARCHED_BYTES) return FileVisitResult.CONTINUE;
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    } catch (MalformedInputException binary) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(screen.out(relative(file) + ":" + (i + 1) + ": " + lines.get(i).strip()));
                            if (matches.size() >= MAX_MATCHES) return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not search the project", e);
        }
        return matches;
    }

    @Tool("Writes a file of the project, creating its directories. Only on a working branch.")
    public String write(@P("the file's path, relative to the project's root") String path,
                        @P("the file's whole new content") String content) {
        if (!onWorkingBranch.getAsBoolean()) {
            throw new IllegalStateException("Writing is only allowed on a working branch, never on the default"
                    + " branch: start one first");
        }
        Path file = inside(screen.in(path));
        if (Files.isDirectory(file)) throw new IllegalArgumentException("That's a directory: " + path);
        if (!writable.covers(relative(file))) {
            throw new IllegalArgumentException("This command writes only " + writable.description + ", not " + path);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, stamped(relative(file), screen.in(content)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
        return screen.out("Wrote " + relative(file));
    }

    private void mayRead() {
        if (!readAllowed.getAsBoolean()) throw notAllowed();
    }

    private IllegalStateException notAllowed() {
        return new IllegalStateException(readableAnyway == Scope.NONE ? "The developer hasn't allowed reading the project."
                : "The developer hasn't allowed reading the project beyond " + readableAnyway.description
                        + ", which can be read by path.");
    }

    /** The content as written: an artifact starts with the stamp, replacing an earlier one. */
    private String stamped(String path, String content) {
        if (stamp == null || !Scope.ARTIFACTS.covers(path)) return content;
        String body = content;
        int end = body.indexOf('\n');
        if (STAMP.matcher(end < 0 ? body : body.substring(0, end)).matches()) body = end < 0 ? "" : body.substring(end + 1);
        return stamp + "\n" + body;
    }

    /** The path inside the project that {@code path} names, or a refusal saying why it isn't one. */
    Path inside(String path) {
        String given = path == null ? "" : path.strip();
        Path relative;
        try {
            relative = Path.of(given);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a path: " + path);
        }
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new IllegalArgumentException("Paths are relative to the project's root, not absolute: " + path);
        }
        for (Path part : relative) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException("Paths may not step out of the project ('..'): " + path);
            }
        }
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equals(".git")) {
            throw new IllegalArgumentException("The git directory is the git tools' business, not the file tools': " + path);
        }
        Path full = root.resolve(relative).normalize();
        Path existing = full;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        try {
            if (existing == null || !existing.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("That path leads out of the project: " + path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("That path can't be followed: " + path);
        }
        return full;
    }

    private String relative(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private boolean isGitDirectory(Path p) {
        return root.resolve(".git").equals(p);
    }
}
