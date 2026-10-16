package io.cafeai.loop;

import io.cafeai.core.internal.Cli;
import io.cafeai.loop.tools.Build;
import io.cafeai.loop.tools.GitTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROADMAP-04, phase 2: the model writes the tests and the code; the loop's code makes the tests come
 * first, runs them itself, applies the model's mutations, and won't call a criterion built while a
 * mutation survives or a change goes untested. Against real git, with a stand-in build that passes
 * the tests only when the code holds the limit: {@code LIMIT = 1024}.
 */
@DisplayName("brew's tools: tests first, run by the loop, the code broken on purpose")
class BrewToolsTest {

    static final String TEST = "src/test/java/org/acme/VisitTests.java";
    static final String CODE = "src/main/java/org/acme/Visit.java";
    static final String GOOD = "package org.acme;\nclass Visit {\n    static final int LIMIT = 1024;\n    // the notes' limit\n}\n";

    @TempDir Path project;
    private String gitPath;
    private final List<List<String>> builds = new ArrayList<>();
    private BrewTools tools;

    private Cli.Result raw(String... args) {
        var command = new ArrayList<>(List.of(gitPath, "-C", project.toString()));
        command.addAll(List.of(args));
        return Cli.run(command, Duration.ofSeconds(30));
    }

    /** The stand-in build: compiles unless the code says it can't; the tests pass when there is a test and the limit holds. */
    private Cli.Result build(List<String> command) {
        builds.add(command);
        try {
            Path code = project.resolve(CODE);
            String text = Files.isRegularFile(code) ? Files.readString(code) : "";
            if (text.contains("DOES NOT COMPILE")) return new Cli.Result(1, "", "error: does not compile");
            if (command.contains("testClasses")) return new Cli.Result(0, "BUILD SUCCESSFUL", "");
            Path test = project.resolve(TEST);
            boolean strong = Files.isRegularFile(test) && Files.readString(test).contains("checks the comment");
            if (Files.isRegularFile(test) && Files.readString(test).contains("NO TESTS")) {
                return new Cli.Result(1, "> No tests found for given includes: [org.acme.VisitTests](--tests filter)", "");
            }
            boolean passes = Files.isRegularFile(test) && text.contains("LIMIT = 1024;")
                    && (!strong || text.contains("// the notes' limit"));
            return passes ? new Cli.Result(0, "BUILD SUCCESSFUL", "") : new Cli.Result(1, "VisitTests > limit() FAILED", "BUILD FAILED");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        gitPath = Cli.find("git", System::getenv).map(Path::toString).orElse(null);
        assumeTrue(gitPath != null, "git isn't installed");
        Files.writeString(project.resolve(System.getProperty("os.name").toLowerCase().startsWith("windows") ? "gradlew.bat" : "gradlew"), "");
        raw("init", "-b", "main");
        raw("config", "user.name", "Test Developer");
        raw("config", "user.email", "dev@example.com");
        raw("config", "commit.gpgsign", "false");
        raw("add", ".");
        raw("commit", "-m", "Initial commit");
        var git = new GitTools(project, "main");
        git.startBranch("feature/notes");
        tools = new BrewTools(project, Build.of(project, this::build).orElseThrow(), git, Screen.none());
    }

    private void write(String path, String text) throws IOException {
        Files.createDirectories(project.resolve(path).getParent());
        Files.writeString(project.resolve(path), text);
    }

    /** Tests written, failing, then the code: where every mutation test starts. */
    private void built() throws IOException {
        write(TEST, "class VisitTests {}");
        assertThat(tools.testsFirst(List.of(TEST))).contains("fail");
        write(CODE, GOOD);
        assertThat(tools.testsPass()).contains("pass");
    }

    @Test @DisplayName("2.1: no code before its tests: writing code is refused until the tests have failed; tests can always be written")
    void testsFirst() throws IOException {
        assertThat(tools.refuseWrite(CODE)).contains("Tests first");
        assertThat(tools.refuseWrite(TEST)).isNull();
        assertThat(tools.refuseWrite("README.md")).isNull();
        write(TEST, "class VisitTests {}");
        tools.testsFirst(List.of(TEST));
        assertThat(tools.refuseWrite(CODE)).isNull();
    }

    @Test @DisplayName("2.1: tests that pass before the code exists test nothing new: refused, code still blocked")
    void testsThatPass() throws IOException {
        write(CODE, GOOD);
        write(TEST, "class VisitTests {}");
        assertThat(tools.testsFirst(List.of(TEST))).contains("pass before").contains("test nothing");
        assertThat(tools.refuseWrite(CODE)).contains("Tests first");
    }

    @Test @DisplayName("2.1: a run where no test ran is no failure: the code stays blocked; nor is it a pass")
    void noTestRan() throws IOException {
        write(TEST, "class VisitTests { /* NO TESTS */ }");
        assertThat(tools.testsFirst(List.of(TEST))).contains("No test ran");
        assertThat(tools.refuseWrite(CODE)).contains("Tests first");
        write(TEST, "class VisitTests {}");
        tools.testsFirst(List.of(TEST));
        write(CODE, GOOD);
        write(TEST, "class VisitTests { /* NO TESTS */ }");
        assertThat(tools.testsPass()).contains("No test ran");
        assertThat(tools.mutate(CODE, "1024", "1025", "x")).contains("tests pass first");
    }

    @Test @DisplayName("2.1: only test sources, that exist, are named as the criterion's tests")
    void namedTests() {
        assertThatThrownBy(() -> tools.testsFirst(List.of(CODE))).hasMessageContaining("isn't a test");
        assertThatThrownBy(() -> tools.testsFirst(List.of(TEST))).hasMessageContaining("No such file");
        assertThatThrownBy(() -> tools.testsFirst(List.of())).hasMessageContaining("Name the tests");
    }

    @Test @DisplayName("the loop runs the tests itself, the named ones: failing, it says what failed; passing, they're green")
    void runsTests() throws IOException {
        write(TEST, "class VisitTests {}");
        tools.testsFirst(List.of(TEST));
        write(CODE, "package org.acme;\nclass Visit { static final int LIMIT = 255; }\n");
        assertThat(tools.testsPass()).contains("still fail").contains("limit() FAILED");
        write(CODE, GOOD);
        assertThat(tools.testsPass()).contains("pass");
        assertThat(builds.getLast()).contains("--tests", "org.acme.VisitTests");
    }

    @Test @DisplayName("2.2: a mutation the tests catch is counted; the code is restored, byte for byte")
    void caught() throws IOException {
        built();
        assertThat(tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved")).startsWith("Caught");
        assertThat(Files.readString(project.resolve(CODE))).isEqualTo(GOOD);
    }

    @Test @DisplayName("2.2: a mutation that survives is said, and blocks done until it is caught by stronger tests")
    void survived() throws IOException {
        built();
        assertThat(tools.mutate(CODE, "// the notes' limit", "// a comment", "a comment changed")).startsWith("Survived");
        assertThat(tools.done("built")).contains("Not done").contains("survived");
        assertThat(Files.readString(project.resolve(CODE))).isEqualTo(GOOD);
    }

    @Test @DisplayName("2.2: a mutation that doesn't compile isn't counted, and the code is restored")
    void doesNotCompile() throws IOException {
        built();
        assertThat(tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1024; DOES NOT COMPILE", "broken")).contains("doesn't compile");
        assertThat(Files.readString(project.resolve(CODE))).isEqualTo(GOOD);
        assertThat(tools.done("built")).contains("Not done");
    }

    @Test @DisplayName("2.2: mutations only on green, on the code, of text found once")
    void mutationRules() throws IOException {
        write(TEST, "class VisitTests {}");
        tools.testsFirst(List.of(TEST));
        write(CODE, GOOD);
        assertThat(tools.mutate(CODE, "1024", "1025", "x")).contains("tests pass first");
        tools.testsPass();
        assertThatThrownBy(() -> tools.mutate(TEST, "class", "interface", "x")).hasMessageContaining("the code, not the tests");
        assertThatThrownBy(() -> tools.mutate(CODE, "absent", "x", "x")).hasMessageContaining("not found");
        write(CODE, GOOD + "// LIMIT = 1024;\n");
        assertThatThrownBy(() -> tools.mutate(CODE, "LIMIT = 1024;", "x", "x")).hasMessageContaining("2 times");
    }

    @Test @DisplayName("done: tests passing, nothing surviving, and every change to the code broken on purpose at least once")
    void done() throws IOException {
        built();
        assertThat(tools.done("built")).contains("Not done").contains(CODE).contains("no mutation");
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        assertThat(tools.done("built")).startsWith("Done");
        assertThat(tools.finished()).isTrue();
    }

    @Test @DisplayName("done: a mutation made before the code changed again doesn't count")
    void staleMutation() throws IOException {
        built();
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        write(CODE, GOOD.replace("// the notes' limit", "// the notes' limit, in characters"));
        assertThat(tools.done("built")).contains("Not done").contains("no mutation");
    }

    @Test @DisplayName("done: a survivor caught again, once the tests are stronger, no longer blocks")
    void survivorCaughtLater() throws IOException {
        built();
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed");
        assertThat(tools.done("built")).contains("survived");
        write(TEST, "class VisitTests { /* checks the comment */ }");
        assertThat(tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed")).startsWith("Caught");
        assertThat(tools.done("built")).startsWith("Done");
    }

    @Test @DisplayName("done: a survivor that changes nothing a test could see is waived, with its reason; one never made can't be")
    void waived() throws IOException {
        built();
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed");
        assertThat(tools.waive(CODE, "// the notes' limit", "// a comment", "a comment: no behaviour to test")).contains("waived");
        assertThat(tools.done("built")).startsWith("Done").contains("a comment: no behaviour to test");
        assertThatThrownBy(() -> tools.waive(CODE, "LIMIT", "x", "why")).hasMessageContaining("no surviving mutation");
    }

    @Test @DisplayName("the order holds: testsPass before testsFirst, and done before green, are refused")
    void order() {
        assertThat(tools.testsPass()).contains("Run testsFirst first");
        assertThat(tools.done("built")).contains("haven't passed yet");
        assertThat(tools.finished()).isFalse();
    }

    @Test @DisplayName("waivers: a caught mutation can't be waived; a waiver goes once its mutation is caught")
    void waiverRules() throws IOException {
        built();
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        assertThatThrownBy(() -> tools.waive(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "why")).hasMessageContaining("no surviving mutation");
        tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed");
        tools.waive(CODE, "// the notes' limit", "// a comment", "WAIVED-REASON");
        write(TEST, "class VisitTests { /* checks the comment */ }");
        tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed");
        assertThat(tools.done("built")).startsWith("Done").doesNotContain("WAIVED-REASON");
    }

    @Test @DisplayName("done: a waived mutation breaks nothing: a change only waived mutations touched is still untested")
    void waivedCoversNothing() throws IOException {
        built();
        tools.mutate(CODE, "// the notes' limit", "// a comment", "the comment changed");
        tools.waive(CODE, "// the notes' limit", "// a comment", "a comment");
        assertThat(tools.done("built")).contains("Not done").contains("no mutation has broken it");
    }

    @Test @DisplayName("done: each change, by its lines: a mutation in one doesn't cover another in the same file")
    void everyChange() throws IOException {
        raw("checkout", "-q", "main");
        String base = "package org.acme;\nclass Visit {\n    static final int LIMIT = 255;\n    int a;\n    int b;\n"
                + "    int c;\n    int d;\n    int e;\n    static final String UNIT = \"chars\";\n}\n";
        write(CODE, base);
        raw("add", ".");
        raw("commit", "-q", "-m", "Visit");
        raw("checkout", "-q", "-b", "feature/two");
        write(TEST, "class VisitTests {}");
        tools.testsFirst(List.of(TEST));
        write(CODE, base.replace("LIMIT = 255;", "LIMIT = 1024;").replace("\"chars\"", "\"characters\""));
        tools.testsPass();
        assertThat(tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved")).startsWith("Caught");
        assertThat(tools.done("built")).contains("Not done").contains("lines 9 to 9").doesNotContain("lines 3 to 3");
    }

    @Test @DisplayName("done: the tests must still pass when it is called")
    void stillPassing() throws IOException {
        built();
        tools.mutate(CODE, "LIMIT = 1024;", "LIMIT = 1025;", "the limit moved");
        write(CODE, GOOD.replace("1024", "2048"));
        assertThat(tools.done("built")).contains("Not done").contains("fail");
    }
}
