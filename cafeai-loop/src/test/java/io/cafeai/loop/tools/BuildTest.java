package io.cafeai.loop.tools;

import io.cafeai.core.internal.Cli;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROADMAP-04, phase 2: `brew` runs the project's own build, as it is, adding nothing to it. */
@DisplayName("The project's build: found, run as it is, its outcome read")
class BuildTest {

    @TempDir Path project;
    private final List<List<String>> ran = new ArrayList<>();
    private Cli.Result next = new Cli.Result(0, "BUILD SUCCESSFUL", "");

    private Build build() {
        return Build.of(project, command -> { ran.add(command); return next; }).orElseThrow();
    }

    private static String wrapper(String name) {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? name + ".bat" : name;
    }

    @Test @DisplayName("a Gradle wrapper: compiles with testClasses, runs the named tests, pointed at the project")
    void gradle() throws IOException {
        Files.writeString(project.resolve(wrapper("gradlew")), "");
        Files.writeString(project.resolve("build.gradle"), "");
        Build build = build();
        assertThat(build.name()).isEqualTo("Gradle");
        build.compile();
        build.test(List.of("org.acme.VisitTests", "org.acme.OwnerTests"));
        assertThat(ran.get(0)).startsWith(project.resolve(wrapper("gradlew")).toString()).contains("-p", project.toString(), "testClasses");
        assertThat(ran.get(1)).contains("test", "--tests", "org.acme.VisitTests", "--tests", "org.acme.OwnerTests");
    }

    @Test @DisplayName("Maven, with its wrapper: compiles with test-compile, runs the named tests, pointed at the pom")
    void maven() throws IOException {
        Files.writeString(project.resolve(wrapper("mvnw")), "");
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Build build = build();
        assertThat(build.name()).isEqualTo("Maven");
        build.compile();
        build.test(List.of("org.acme.VisitTests"));
        assertThat(ran.get(0)).contains("-f", project.resolve("pom.xml").toString(), "test-compile");
        assertThat(ran.get(1)).contains("-Dtest=org.acme.VisitTests", "-Dsurefire.failIfNoSpecifiedTests=false", "test");
    }

    @Test @DisplayName("both builds: the Gradle wrapper is used")
    void both() throws IOException {
        Files.writeString(project.resolve(wrapper("gradlew")), "");
        Files.writeString(project.resolve(wrapper("mvnw")), "");
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        assertThat(build().name()).isEqualTo("Gradle");
    }

    @Test @DisplayName("no build it knows: none")
    void none() {
        assertThat(Build.of(project, command -> next)).isEmpty();
    }

    @Test @DisplayName("the outcome: passed or not, and the end of the output, failures first")
    void outcome() throws IOException {
        Files.writeString(project.resolve(wrapper("gradlew")), "");
        next = new Cli.Result(1, "line\n".repeat(3000) + "VisitTests > notesAtTheLimit() FAILED\n", "BUILD FAILED");
        Build.Outcome outcome = build().test(List.of("org.acme.VisitTests"));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.output()).contains("notesAtTheLimit() FAILED").contains("BUILD FAILED");
        assertThat(outcome.output().length()).isLessThanOrEqualTo(Build.MAX_OUTPUT + 200);
        next = new Cli.Result(0, "BUILD SUCCESSFUL", "");
        assertThat(build().test(List.of("org.acme.VisitTests")).passed()).isTrue();
    }

    @Test @DisplayName("no test ran is told apart from tests that failed: Gradle's and Maven's words for it")
    void noTests() throws IOException {
        Files.writeString(project.resolve(wrapper("gradlew")), "");
        next = new Cli.Result(1, "> No tests found for given includes: [org.acme.NoSuchTests](--tests filter)", "");
        assertThat(build().test(List.of("org.acme.NoSuchTests")).ran()).isFalse();
        next = new Cli.Result(0, "[INFO] No tests to run.", "");
        assertThat(build().test(List.of("org.acme.NoSuchTests")).ran()).isFalse();
        next = new Cli.Result(0, "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0", "");
        assertThat(build().test(List.of("org.acme.NoSuchTests")).ran()).isFalse();
        next = new Cli.Result(1, "VisitTests > limit() FAILED\nTests run: 3, Failures: 1", "");
        assertThat(build().test(List.of("org.acme.VisitTests")).ran()).isTrue();
        assertThat(build().compile().ran()).as("compiling runs no tests, and isn't asked").isTrue();
    }

    @Test @DisplayName("a test file's class: its path under src/test/java, as a name; anything else isn't a test")
    void testClass() {
        assertThat(Build.testClass("src/test/java/org/acme/VisitTests.java")).contains("org.acme.VisitTests");
        assertThat(Build.testClass("src/main/java/org/acme/Visit.java")).isEmpty();
        assertThat(Build.testClass("src/test/resources/data.sql")).isEmpty();
        assertThat(Build.testClass("src/test/java/org/acme/data.json")).as("not Java").isEmpty();
        assertThatThrownBy(() -> Build.testClass(null)).isInstanceOf(NullPointerException.class);
    }
}
