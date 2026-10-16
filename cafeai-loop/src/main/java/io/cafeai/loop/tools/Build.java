package io.cafeai.loop.tools;

import io.cafeai.core.internal.Cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The project's own build, run as it is (ROADMAP-04, phase 2; DESIGN_SPEC.md, How `brew` builds):
 * Gradle through its wrapper, else Maven through its wrapper or the one on the PATH. Nothing is added
 * to it. It compiles the code and the tests, and runs the tests it is given; the outcome is whether
 * they passed, and the end of the output, where the failures are.
 */
public final class Build {

    /** The most of a build's output kept: the end, where the failures and the verdict are. */
    static final int MAX_OUTPUT = 6_000;
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    /** Whether it passed, and what it said. */
    public record Outcome(boolean passed, String output) {

        /** What Gradle and Maven say when the tests named hold none they can run. */
        private static final Pattern NO_TESTS =
                Pattern.compile("No tests found for given includes|No tests to run|Tests run: 0, Failures: 0");

        /** Whether any test ran: a run with none neither fails nor passes a criterion's tests. */
        public boolean ran() {
            return !NO_TESTS.matcher(output).find();
        }
    }

    private final String name;
    private final List<String> base;
    private final List<String> compile;
    private final Function<List<String>, List<String>> test;
    private final Function<List<String>, Cli.Result> runner;

    private Build(String name, List<String> base, List<String> compile, Function<List<String>, List<String>> test,
                  Function<List<String>, Cli.Result> runner) {
        this.name = name;
        this.base = base;
        this.compile = compile;
        this.test = test;
        this.runner = runner;
    }

    /** The project's build, if it has one the loop knows. */
    public static Optional<Build> of(Path project) {
        return of(project, command -> Cli.run(command, TIMEOUT));
    }

    /** The same, running each command through {@code runner}: a stand-in for tests, or a runner of the caller's own. */
    public static Optional<Build> of(Path project, Function<List<String>, Cli.Result> runner) {
        Path gradlew = project.resolve(windows() ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(gradlew)) {
            return Optional.of(new Build("Gradle", List.of(gradlew.toString(), "-p", project.toString(), "--console=plain"),
                    List.of("testClasses"), classes -> {
                        var args = new ArrayList<>(List.of("test"));
                        classes.forEach(c -> { args.add("--tests"); args.add(c); });
                        return args;
                    }, runner));
        }
        Path pom = project.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            Path mvnw = project.resolve(windows() ? "mvnw.cmd" : "mvnw");
            if (windows() && !Files.isRegularFile(mvnw)) mvnw = project.resolve("mvnw.bat");
            String maven = Files.isRegularFile(mvnw) ? mvnw.toString()
                    : Cli.find("mvn", System::getenv).map(Path::toString).orElse(null);
            if (maven == null) return Optional.empty();
            return Optional.of(new Build("Maven", List.of(maven, "-B", "-f", pom.toString()), List.of("test-compile"),
                    classes -> List.of("-Dtest=" + String.join(",", classes), "-Dsurefire.failIfNoSpecifiedTests=false",
                            "test"), runner));
        }
        return Optional.empty();
    }

    /** Gradle or Maven. */
    public String name() {
        return name;
    }

    /** Compiles the code and its tests. */
    public Outcome compile() {
        return run(compile);
    }

    /** Runs the tests named (classes, by their full names). */
    public Outcome test(List<String> classes) {
        return run(test.apply(classes));
    }

    private Outcome run(List<String> args) {
        var command = new ArrayList<>(base);
        command.addAll(args);
        Cli.Result result = runner.apply(command);
        String output = (result.out() + "\n" + result.err()).strip();
        if (output.length() > MAX_OUTPUT) output = "...\n" + output.substring(output.length() - MAX_OUTPUT);
        return new Outcome(result.ok(), output);
    }

    /** A test source's class name ({@code src/test/java/a/b/CTest.java} is {@code a.b.CTest}); none for anything else. */
    public static Optional<String> testClass(String relativePath) {
        Objects.requireNonNull(relativePath, "path");
        String p = relativePath.replace('\\', '/');
        String root = "src/test/java/";
        if (!p.startsWith(root) || !p.endsWith(".java")) return Optional.empty();
        return Optional.of(p.substring(root.length(), p.length() - ".java".length()).replace('/', '.'));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
