package io.cafeai.loop.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ROADMAP-03, 1.2: inside the project, nothing outside it, writing only on a working branch. */
@DisplayName("The file tools: inside the project, never outside it")
class ProjectFilesTest {

    @TempDir Path dir;
    private Path project;
    private Path outside;
    private boolean working = true;
    private ProjectFiles files;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(dir.resolve("project"));
        outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "the neighbour's secret");
        Files.createDirectories(project.resolve("src/main"));
        Files.writeString(project.resolve("README.md"), "# Demo\nA visit has a date.\n");
        Files.writeString(project.resolve("src/main/App.java"), "class App {\n  // Visit scheduling\n}\n");
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), "url = https://token@example.com/repo.git\nvisit\n");
        files = new ProjectFiles(project, () -> working);
    }

    @Test @DisplayName("reads, lists and searches inside the project")
    void inside() {
        assertThat(files.read("README.md")).contains("A visit has a date.");
        assertThat(files.read("src/main/App.java")).contains("Visit scheduling");
        assertThat(files.list("")).containsExactly("README.md", "src/");
        assertThat(files.list("src/main")).containsExactly("src/main/App.java");
        assertThat(files.search("VISIT")).containsExactlyInAnyOrder(
                "README.md:2: A visit has a date.", "src/main/App.java:2: // Visit scheduling");
    }

    @Test @DisplayName("writes inside the project on a working branch, creating directories")
    void writes() throws IOException {
        assertThat(files.write("docs/roadmap/ROADMAP-01.md", "# ROADMAP-01")).isEqualTo("Wrote docs/roadmap/ROADMAP-01.md");
        assertThat(Files.readString(project.resolve("docs/roadmap/ROADMAP-01.md"))).isEqualTo("# ROADMAP-01");
        files.write("README.md", "# Changed");
        assertThat(Files.readString(project.resolve("README.md"))).isEqualTo("# Changed");
    }

    @Test @DisplayName("writing is refused off a working branch (on the default branch), and nothing changes")
    void notOnAWorkingBranch() throws IOException {
        working = false;
        assertThatThrownBy(() -> files.write("README.md", "# Changed"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("working branch");
        assertThat(Files.readString(project.resolve("README.md"))).startsWith("# Demo");
        assertThat(files.read("README.md")).as("reading is still allowed").startsWith("# Demo");
    }

    @ParameterizedTest(name = "refused: {0}")
    @ValueSource(strings = {"../outside/secret.txt", "src/../../outside/secret.txt", "..", "./../outside/secret.txt"})
    @DisplayName("a path stepping out ('..') is refused, for reading and writing")
    void parentSteps(String path) {
        assertThatThrownBy(() -> files.read(path)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.write(path, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside.resolve("secret.txt")).hasContent("the neighbour's secret");
    }

    @Test @DisplayName("any '..' is refused, even one that would stay inside the project")
    void anyParentStep() {
        assertThatThrownBy(() -> files.read("src/../README.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("step out of the project");
        assertThatThrownBy(() -> files.write("src/../NEW.md", "x")).hasMessageContaining("step out of the project");
        assertThat(project.resolve("NEW.md")).doesNotExist();
    }

    @Test @DisplayName("an absolute path is refused, even one inside the project")
    void absolute() {
        assertThatThrownBy(() -> files.read(outside.resolve("secret.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative");
        assertThatThrownBy(() -> files.read(project.resolve("README.md").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relative");
        assertThatThrownBy(() -> files.write(outside.resolve("new.txt").toString(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outside.resolve("new.txt")).doesNotExist();
    }

    @Test @DisplayName("a drive-relative path is refused on Windows")
    void driveRelative() {
        assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        assertThatThrownBy(() -> files.read("C:secret.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("a symbolic link pointing out is refused, as a file and as a directory, for reading and writing")
    void symbolicLinks() throws IOException {
        try {
            Files.createSymbolicLink(project.resolve("linked.txt"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(project.resolve("linked-dir"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this machine can't create symbolic links: " + e);
        }
        assertThatThrownBy(() -> files.read("linked.txt")).hasMessageContaining("out of the project");
        assertThatThrownBy(() -> files.read("linked-dir/secret.txt")).hasMessageContaining("out of the project");
        assertThatThrownBy(() -> files.write("linked.txt", "overwritten")).hasMessageContaining("out of the project");
        assertThatThrownBy(() -> files.write("linked-dir/new.txt", "x")).hasMessageContaining("out of the project");
        assertThatThrownBy(() -> files.list("linked-dir")).hasMessageContaining("out of the project");
        assertThat(outside.resolve("secret.txt")).hasContent("the neighbour's secret");
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThat(files.search("neighbour")).as("the search doesn't follow links out").isEmpty();
    }

    @Test @DisplayName("the git directory is out of bounds: not read, written, listed or searched")
    void gitDirectory() {
        assertThatThrownBy(() -> files.read(".git/config")).hasMessageContaining("git directory");
        assertThatThrownBy(() -> files.write(".git/hooks/pre-commit", "x")).hasMessageContaining("git directory");
        assertThatThrownBy(() -> files.list(".git")).hasMessageContaining("git directory");
        assertThat(files.list("")).doesNotContain(".git/");
        assertThat(files.search("token")).isEmpty();
        assertThat(files.search("visit")).noneMatch(line -> line.startsWith(".git"));
    }

    @Test @DisplayName("screened: what is read leaves as placeholders; what is written with them lands as the original")
    void screened() throws IOException {
        Files.writeString(project.resolve("config.properties"), "admin=alice@acme.example\nport=8080\n");
        var screen = new io.cafeai.loop.Screen();
        var screenedFiles = new ProjectFiles(project, () -> working, null, screen, false);
        String read = screenedFiles.read("config.properties");
        assertThat(read).doesNotContain("alice@acme.example").contains("port=8080");
        String placeholder = read.substring("admin=".length(), read.indexOf('\n'));

        screenedFiles.write("config.properties", "admin=" + placeholder + "\nport=9090\n");
        assertThat(Files.readString(project.resolve("config.properties"))).isEqualTo("admin=alice@acme.example\nport=9090\n");

        assertThat(screenedFiles.search(placeholder)).as("searching by placeholder finds the original")
                .singleElement().satisfies(line -> assertThat(line).startsWith("config.properties:1:").doesNotContain("alice"));
    }

    @Test @DisplayName("screened: a file named with personal data is listed as a placeholder, and read back by it")
    void screenedNames() throws IOException {
        Files.writeString(project.resolve("notes-bob@acme.example.md"), "notes\n");
        var screenedFiles = new ProjectFiles(project, () -> working, null, new io.cafeai.loop.Screen(), false);
        String listed = screenedFiles.list("").stream().filter(name -> name.contains("[EMAIL_")).findFirst().orElseThrow();
        assertThat(screenedFiles.list("")).noneMatch(name -> name.contains("bob@acme.example"));
        assertThat(screenedFiles.read(listed)).isEqualTo("notes\n");
    }

    @Test @DisplayName("artifacts only: the loop's artifacts can be written; code and other files can't")
    void artifactsOnly() throws IOException {
        var planning = new ProjectFiles(project, () -> working, null, io.cafeai.loop.Screen.none(), true);
        planning.write("DESIGN_SPEC.md", "# Spec\n");
        planning.write("docs/roadmap/ROADMAP-01.md", "# ROADMAP-01\n");
        assertThatThrownBy(() -> planning.write("src/main/App.java", "class App {}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only the loop's artifacts");
        assertThatThrownBy(() -> planning.write("README.md", "# Changed")).hasMessageContaining("only the loop's artifacts");
        assertThat(Files.readString(project.resolve("src/main/App.java"))).startsWith("class App {\n");
    }

    @Test @DisplayName("what isn't there, isn't a directory, or isn't text says so")
    void notThere() throws IOException {
        assertThatThrownBy(() -> files.read("missing.md")).hasMessageContaining("No such file");
        assertThatThrownBy(() -> files.read("src")).hasMessageContaining("No such file");
        assertThatThrownBy(() -> files.list("README.md")).hasMessageContaining("No such directory");
        assertThatThrownBy(() -> files.write("src", "x")).hasMessageContaining("directory");
        Files.write(project.resolve("image.bin"), new byte[] {(byte) 0xC3, (byte) 0x28, 0, 1});
        assertThatThrownBy(() -> files.read("image.bin")).hasMessageContaining("Not a text file");
        assertThatThrownBy(() -> files.search(" ")).hasMessageContaining("search for");
    }

    @Test @DisplayName("the search skips binary and very large files, and stops at its limit")
    void searchLimits() throws IOException {
        Files.write(project.resolve("image.bin"), new byte[] {(byte) 0xC3, (byte) 0x28, 'v', 'i', 's', 'i', 't'});
        Files.writeString(project.resolve("big.txt"), "visit\n".repeat((int) (ProjectFiles.MAX_SEARCHED_BYTES / 6) + 10),
                StandardCharsets.UTF_8);
        assertThat(files.search("visit")).noneMatch(l -> l.startsWith("image.bin") || l.startsWith("big.txt"));
        Files.writeString(project.resolve("many.txt"), "visit\n".repeat(ProjectFiles.MAX_MATCHES + 50));
        assertThat(files.search("visit")).hasSize(ProjectFiles.MAX_MATCHES);
    }
}
