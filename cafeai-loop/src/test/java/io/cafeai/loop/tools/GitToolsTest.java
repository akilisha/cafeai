package io.cafeai.loop.tools;

import io.cafeai.core.internal.Cli;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ROADMAP-03, 1.3: a working branch and commits to it; never the default branch, a merge or a push. Against real git. */
@DisplayName("The git tools: a working branch and its commits, nothing else")
class GitToolsTest {

    @TempDir Path repo;
    private String gitPath;
    private GitTools git;

    private Cli.Result raw(String... args) {
        var command = new ArrayList<>(List.of(gitPath, "-C", repo.toString()));
        command.addAll(List.of(args));
        return Cli.run(command, Duration.ofSeconds(30));
    }

    @BeforeEach
    void setUp() throws IOException {
        gitPath = Cli.find("git", System::getenv).map(Path::toString).orElse(null);
        assumeTrue(gitPath != null, "git isn't installed");
        assertThat(raw("init", "-b", "main").ok()).isTrue();
        raw("config", "user.name", "Test Developer");
        raw("config", "user.email", "dev@example.com");
        raw("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("README.md"), "# Demo\n");
        raw("add", "README.md");
        assertThat(raw("commit", "-m", "Initial commit").ok()).isTrue();
        git = new GitTools(repo, "main");
    }

    private String log(String format) {
        return raw("log", "-1", "--format=" + format).out().strip();
    }

    @Test @DisplayName("on the default branch: not a working branch, and commits are refused")
    void defaultBranch() throws IOException {
        assertThat(git.currentBranch()).isEqualTo("main");
        assertThat(git.onWorkingBranch()).isFalse();
        Files.writeString(repo.resolve("README.md"), "# Changed\n");
        assertThatThrownBy(() -> git.commit("Change the readme", List.of("README.md")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("default branch");
        assertThat(log("%s")).isEqualTo("Initial commit");
    }

    @Test @DisplayName("starts a working branch and commits to it, with the message as given and no sign-off")
    void workingBranch() throws IOException {
        assertThat(git.startBranch("feature/visit-model")).contains("feature/visit-model");
        assertThat(git.currentBranch()).isEqualTo("feature/visit-model");
        assertThat(git.onWorkingBranch()).isTrue();
        Files.writeString(repo.resolve("SPEC.md"), "# Spec\n");
        String hash = git.commit("Add the design spec", List.of("SPEC.md"));
        assertThat(hash).isEqualTo(log("%h"));
        assertThat(log("%B")).isEqualTo("Add the design spec").doesNotContain("Signed-off-by");
        assertThat(raw("rev-parse", "main").out()).as("the default branch didn't move")
                .isNotEqualTo(raw("rev-parse", "HEAD").out());
    }

    @Test @DisplayName("screened: a commit message written with placeholders is committed with the originals")
    void screenedMessage() throws IOException {
        var screen = new io.cafeai.loop.Screen();
        String placeholder = screen.out("alice@acme.example");
        var screened = new GitTools(repo, "main", screen);
        screened.startBranch("feature/contact");
        Files.writeString(repo.resolve("CONTACT.md"), "alice@acme.example\n");
        screened.commit("Add contact " + placeholder, List.of("CONTACT.md"));
        assertThat(log("%s")).isEqualTo("Add contact alice@acme.example");

        Files.writeString(repo.resolve("notes-alice@acme.example.md"), "notes\n");
        screened.commit("Add notes", List.of("notes-" + placeholder + ".md"));
        assertThat(raw("show", "--name-only", "--format=", "HEAD").out().strip()).isEqualTo("notes-alice@acme.example.md");
    }

    @Test @DisplayName("a sign-off only when the developer has said so")
    void signOff() throws IOException {
        git.startBranch("feature/signed");
        Files.writeString(repo.resolve("SPEC.md"), "# Spec\n");
        git.commit("Add the design spec", List.of("SPEC.md"), true);
        assertThat(log("%B")).contains("Signed-off-by: Test Developer <dev@example.com>");
    }

    @Test @DisplayName("only the files named are committed")
    void onlyNamedFiles() throws IOException {
        git.startBranch("feature/two");
        Files.writeString(repo.resolve("A.md"), "a\n");
        Files.writeString(repo.resolve("B.md"), "b\n");
        git.commit("Add A", List.of("A.md"));
        assertThat(raw("show", "--name-only", "--format=", "HEAD").out().strip()).isEqualTo("A.md");
        assertThat(raw("status", "--porcelain").out()).contains("B.md");
    }

    @Test @DisplayName("the default branch, an existing branch and an invalid name can't be started")
    void startRefusals() {
        assertThatThrownBy(() -> git.startBranch("main")).hasMessageContaining("default branch");
        git.startBranch("feature/one");
        assertThatThrownBy(() -> git.startBranch("feature/one")).hasMessageContaining("already exists");
        assertThatThrownBy(() -> git.startBranch("bad..name")).hasMessageContaining("Not a valid branch name");
        assertThatThrownBy(() -> git.startBranch(" ")).hasMessageContaining("Name the branch");
    }

    @Test @DisplayName("a detached head isn't a working branch")
    void detached() {
        raw("checkout", "--detach");
        assertThat(git.currentBranch()).isEqualTo("HEAD");
        assertThat(git.onWorkingBranch()).isFalse();
    }

    @ParameterizedTest(name = "git {0} is refused")
    @ValueSource(strings = {"merge", "push", "rebase", "reset", "checkout", "pull", "fetch", "branch", "tag", "config", "remote"})
    @DisplayName("merging, pushing and the like can't be run at all")
    void neverAllowed(String subcommand) {
        assertThatThrownBy(() -> git.git(subcommand, "main"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not something the loop does");
    }

    @Test @DisplayName("switching is only to a branch just created, so never back to the default branch")
    void noBareSwitch() {
        git.startBranch("feature/x");
        assertThatThrownBy(() -> git.git("switch", "main")).hasMessageContaining("just created");
        assertThat(git.currentBranch()).isEqualTo("feature/x");
    }

    @Test @DisplayName("a commit needs a message and files; git's own failure is reported")
    void commitRefusals() {
        git.startBranch("feature/y");
        assertThatThrownBy(() -> git.commit(" ", List.of("README.md"))).hasMessageContaining("message");
        assertThatThrownBy(() -> git.commit("Msg", List.of())).hasMessageContaining("which files");
        assertThatThrownBy(() -> git.commit("Msg", List.of("missing.md"))).hasMessageContaining("git add failed");
    }

    @Test @DisplayName("with no developer to ask, a sign-off is refused and nothing is committed")
    void signOffWithNobodyToAsk() throws IOException {
        git.startBranch("feature/x");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        assertThat(git.commitAsked("Add a", List.of("a.txt"), true)).contains("nothing was committed");
        assertThat(log("%s")).isEqualTo("Initial commit");
        git.commitAsked("Add a", List.of("a.txt"), false);
        assertThat(log("%B")).isEqualTo("Add a");
    }

    @Test @DisplayName("a new repository: its default branch as named, and an invalid name refused before anything is created")
    void createRepository(@TempDir Path fresh) {
        assertThatThrownBy(() -> GitTools.createRepository(fresh, "bad..name")).hasMessageContaining("Not a valid branch name");
        assertThat(fresh.resolve(".git")).doesNotExist();
        GitTools.createRepository(fresh, "trunk");
        assertThat(new GitTools(fresh, "trunk").currentBranch()).isEqualTo("trunk");
    }

    @Test @DisplayName("the remote's default branch, by its name, when the remote names one")
    void remoteDefault() {
        assertThat(GitTools.remoteDefault(repo)).isEmpty();
        raw("update-ref", "refs/remotes/origin/trunk", "HEAD");
        raw("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/trunk");
        assertThat(GitTools.remoteDefault(repo)).contains("trunk");
    }

    @Test @DisplayName("a project's default branch need not be main")
    void otherDefault() {
        raw("branch", "-m", "main", "trunk");
        var tools = new GitTools(repo, "trunk");
        assertThat(tools.onWorkingBranch()).isFalse();
        assertThat(new GitTools(repo, "main").onWorkingBranch()).as("with the wrong default named").isTrue();
    }
}
