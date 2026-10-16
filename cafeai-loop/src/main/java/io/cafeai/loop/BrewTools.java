package io.cafeai.loop;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.cafeai.loop.tools.Build;
import io.cafeai.loop.tools.GitTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code brew}'s own tools for building a criterion (ROADMAP-04, phase 2; DESIGN_SPEC.md, How
 * `brew` builds). The model writes the tests and the code; these hold the lines whatever it does:
 * the code is changed only after the criterion's tests have been seen failing; the tests are run by
 * the loop, not taken on the model's word; each mutation the model proposes is applied, checked to
 * compile, tested and undone from a copy; and the criterion is done only when its tests pass, no
 * mutation survives unwaived, and every change to the code has been broken on purpose at least once
 * since it was last changed.
 */
public final class BrewTools {

    /** Where the code is; changes there need their tests first, and are what mutations break. */
    static final String CODE_ROOT = "src/main/";

    private final Path project;
    private final Build build;
    private final GitTools git;
    private final Screen screen;
    private final List<String> testFiles = new ArrayList<>();
    private boolean red;
    private boolean green;
    private volatile boolean finished;
    /** Each mutation by its change, with whether it was caught and the file's content it was made on. */
    private final Map<String, Mutation> mutations = new LinkedHashMap<>();
    private final Map<String, String> waivers = new LinkedHashMap<>();

    private record Mutation(String path, int line, boolean caught, String madeOn) {}

    public BrewTools(Path project, Build build, GitTools git, Screen screen) {
        this.project = project;
        this.build = Objects.requireNonNull(build, "build");
        this.git = git;
        this.screen = screen;
    }

    /** Why a write to {@code relativePath} is refused now, or {@code null}: the code waits for its tests (2.1). */
    public String refuseWrite(String relativePath) {
        String p = relativePath.replace('\\', '/');
        if (p.startsWith(CODE_ROOT) && !red) {
            return "Tests first: write the criterion's tests, and run testsFirst to see them fail, before changing the code"
                    + " (" + relativePath + ").";
        }
        return null;
    }

    @Tool("Runs the criterion's new tests before the code exists. They must fail: tests that already pass test nothing new."
            + " Until they have failed here, the code can't be changed.")
    public String testsFirst(@P("the test files, relative to the project's root (src/test/java/...)") List<String> tests) {
        if (tests == null || tests.isEmpty()) throw new IllegalArgumentException("Name the tests: the criterion's test files");
        var files = new ArrayList<String>();
        for (String t : tests) {
            String path = screen.in(t).strip().replace('\\', '/');
            if (Build.testClass(path).isEmpty()) throw new IllegalArgumentException(t + " isn't a test source (src/test/java/...)");
            if (!Files.isRegularFile(project.resolve(path))) throw new IllegalArgumentException("No such file: " + t);
            files.add(path);
        }
        Build.Outcome outcome = run(files);
        if (!outcome.ran()) return noTestRan(outcome);
        if (outcome.passed()) {
            return "These tests pass before the code exists, so they test nothing the code doesn't already do: make them"
                    + " fail until the criterion is built, then run testsFirst again.";
        }
        testFiles.clear();
        testFiles.addAll(files);
        red = true;
        return screen.out("The tests fail, as they should before the code: now write the code. What failed:\n" + outcome.output());
    }

    @Tool("Runs the criterion's tests, as testsFirst named them. They must pass once the code is written.")
    public String testsPass() {
        if (!red) return "Run testsFirst first: the criterion's tests, failing before the code.";
        Build.Outcome outcome = run(testFiles);
        if (!outcome.ran()) {
            green = false;
            return noTestRan(outcome);
        }
        green = outcome.passed();
        return green ? "The tests pass. Now break the code on purpose with mutate, to show the tests would catch it."
                : screen.out("The tests still fail:\n" + outcome.output());
    }

    @Tool("Breaks the code on purpose, once: replaces text found exactly once in a code file, checks it compiles, runs the"
            + " criterion's tests, and puts the code back. The tests should fail: if they still pass, the mutation survived,"
            + " and the tests need making stronger.")
    public String mutate(@P("the code file, relative to the project's root (src/main/...)") String path,
                         @P("text found exactly once in it") String find,
                         @P("what to put instead") String replace,
                         @P("what the change breaks, in a few words") String why) {
        if (!green) return "Mutations come once the tests pass first: run testsPass.";
        String file = screen.in(path).strip().replace('\\', '/');
        if (!file.startsWith(CODE_ROOT)) throw new IllegalArgumentException("Mutations break the code, not the tests: " + path);
        Path target = project.resolve(file);
        String original = read(target);
        String from = screen.in(find), to = screen.in(replace);
        int count = count(original, from);
        if (count == 0) throw new IllegalArgumentException("The text was not found in " + path);
        if (count > 1) throw new IllegalArgumentException("The text is found " + count + " times in " + path + ": give enough to find it once");
        int line = original.substring(0, original.indexOf(from)).split("\n", -1).length;
        boolean caught;
        try {
            write(target, original.replace(from, to));
            if (!build.compile().passed()) return "The mutation doesn't compile, so it shows nothing: it isn't counted. Try another.";
            Build.Outcome outcome = run(testFiles);
            if (!outcome.ran()) return noTestRan(outcome);
            caught = !outcome.passed();
        } finally {
            write(target, original);
        }
        String key = file + "\0" + from + "\0" + to;
        mutations.put(key, new Mutation(file, line, caught, original));
        if (caught) waivers.remove(key);
        return caught ? "Caught: the tests failed with the code broken (" + why + "), and the code is back as it was."
                : "Survived: the tests still pass with the code broken (" + why + "). Make the tests stronger and mutate again,"
                        + " or, if the change can't be seen by any test, waive it with its reason. The code is back as it was.";
    }

    @Tool("Waives a mutation that survived because no test could see it (a comment, a log message, an equivalent change),"
            + " with the reason. The reason is recorded with the criterion's evidence.")
    public String waive(@P("the code file, as given to mutate") String path, @P("the text, as given to mutate") String find,
                        @P("the replacement, as given to mutate") String replace, @P("why no test could see it") String why) {
        String key = screen.in(path).strip().replace('\\', '/') + "\0" + screen.in(find) + "\0" + screen.in(replace);
        Mutation m = mutations.get(key);
        if (m == null || m.caught()) throw new IllegalArgumentException("There is no surviving mutation with that change to waive");
        waivers.put(key, why);
        return "Mutation waived: " + why;
    }

    @Tool("Finishes the criterion: done only when its tests pass, no mutation survives unwaived, and every change to the"
            + " code has been broken on purpose at least once since it last changed. Otherwise it says what is missing.")
    public String done(@P("what was built, in a sentence") String summary) {
        if (!green) return "Not done: the tests haven't passed yet (testsFirst, then the code, then testsPass).";
        var missing = new ArrayList<String>();
        Build.Outcome outcome = run(testFiles);
        if (!outcome.ran()) missing.add("no test ran: the test files hold no test the build could find");
        else if (!outcome.passed()) missing.add("the criterion's tests fail now:\n" + outcome.output());
        mutations.forEach((key, m) -> {
            if (!m.caught() && !waivers.containsKey(key) && current(m)) {
                missing.add("a mutation survived in " + m.path() + " at line " + m.line() + ": make the tests catch it, or waive it");
            }
        });
        git.changedLines(CODE_ROOT.substring(0, CODE_ROOT.length() - 1)).forEach((file, ranges) -> {
            for (int[] r : ranges) {
                boolean broken = mutations.values().stream().anyMatch(m -> m.caught() && m.path().equals(file)
                        && current(m) && m.line() >= r[0] && m.line() <= r[1]);
                if (!broken) missing.add(file + ", lines " + r[0] + " to " + r[1] + ": changed, and no mutation has broken it");
            }
        });
        if (!missing.isEmpty()) return screen.out("Not done:\n- " + String.join("\n- ", missing));
        finished = true;
        var said = new StringBuilder("Done: " + summary + ". The tests pass, and " + mutations.values().stream()
                .filter(m -> m.caught() && current(m)).count() + " mutations were caught.");
        waivers.values().forEach(w -> said.append(" Waived: ").append(w).append('.'));
        return screen.out(said.toString());
    }

    private String noTestRan(Build.Outcome outcome) {
        return screen.out("No test ran: the test files hold no test the build could find, so this shows nothing. What the"
                + " build said:\n" + outcome.output());
    }

    /** Whether {@code done} has succeeded. */
    public boolean finished() {
        return finished;
    }

    /** Whether a mutation was made on the code as it is now (a later change makes it stale). */
    private boolean current(Mutation m) {
        Path file = project.resolve(m.path());
        return Files.isRegularFile(file) && read(file).equals(m.madeOn());
    }

    private Build.Outcome run(List<String> files) {
        return build.test(files.stream().map(f -> Build.testClass(f).orElseThrow()).toList());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
