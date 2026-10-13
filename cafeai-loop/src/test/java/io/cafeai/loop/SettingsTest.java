package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROADMAP-03, 1.4: the dials, from cafeai-loop.yaml at the project's root, with the spec's defaults. */
@DisplayName("The settings: cafeai-loop.yaml, and the spec's defaults")
class SettingsTest {

    @TempDir Path project;

    private Settings read(String yaml) throws IOException {
        Files.writeString(project.resolve("cafeai-loop.yaml"), yaml);
        return Settings.read(project);
    }

    private static void assertDefaults(Settings s) {
        assertThat(s.branches()).isEqualTo(Settings.Branches.PER_ARTIFACT);
        assertThat(s.stopAt()).isEqualTo(Settings.StopAt.CRITERION);
        assertThat(s.brewCommands()).isEqualTo(Settings.BrewCommands.ASK);
        assertThat(s.models()).isEmpty();
        assertThat(s.model("init")).isEmpty();
        assertThat(s.mutation()).isTrue();
        assertThat(s.live()).isEmpty();
        assertThat(s.childrenDepth()).isEqualTo(1);
        assertThat(s.childrenParallel()).isEqualTo(4);
        assertThat(s.childrenDeadline()).isEqualTo(Duration.ofSeconds(60));
        assertThat(s.uploadsKeep()).isEqualTo(Settings.UploadsKeep.REFERENCE);
    }

    @Test @DisplayName("no settings file: the spec's defaults")
    void noFile() {
        assertDefaults(Settings.read(project));
        assertDefaults(Settings.defaults());
    }

    @Test @DisplayName("an empty file, or one of comments: the defaults")
    void empty() throws IOException {
        assertDefaults(read(""));
        assertDefaults(read("# nothing set yet\n"));
    }

    @Test @DisplayName("every dial set")
    void everyDial() throws IOException {
        Settings s = read("""
                branches: single
                brew:
                  stopAt: phase
                  commands: ["./gradlew build", "docker compose up -d"]
                models:
                  init: claude-opus-5-5
                  brew: claude-haiku-5-5
                validation:
                  mutation: off
                  live: [mysql, postgres]
                children:
                  depth: 2
                  parallel: 8
                  deadline: 2m
                uploads:
                  keep: original
                """);
        assertThat(s.branches()).isEqualTo(Settings.Branches.SINGLE);
        assertThat(s.stopAt()).isEqualTo(Settings.StopAt.PHASE);
        assertThat(s.brewCommands().allowed()).containsExactly("./gradlew build", "docker compose up -d");
        assertThat(s.brewCommands().mode()).isEqualTo(Settings.BrewCommands.Mode.ALLOWED);
        assertThat(s.models()).containsExactlyInAnyOrderEntriesOf(Map.of("init", "claude-opus-5-5", "brew", "claude-haiku-5-5"));
        assertThat(s.model("brew")).contains("claude-haiku-5-5");
        assertThat(s.model("plan")).as("unset: one model for all").isEmpty();
        assertThat(s.mutation()).isFalse();
        assertThat(s.live()).containsExactly("mysql", "postgres");
        assertThat(s.childrenDepth()).isEqualTo(2);
        assertThat(s.childrenParallel()).isEqualTo(8);
        assertThat(s.childrenDeadline()).isEqualTo(Duration.ofMinutes(2));
        assertThat(s.uploadsKeep()).isEqualTo(Settings.UploadsKeep.ORIGINAL);
    }

    @Test @DisplayName("some dials set: the rest keep their defaults")
    void partly() throws IOException {
        Settings s = read("brew:\n  stopAt: milestone\n");
        assertThat(s.stopAt()).isEqualTo(Settings.StopAt.MILESTONE);
        assertThat(s.branches()).isEqualTo(Settings.Branches.PER_ARTIFACT);
        assertThat(s.brewCommands()).isEqualTo(Settings.BrewCommands.ASK);
        assertThat(s.childrenDeadline()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test @DisplayName("brew.commands: ask, none, or a list")
    void brewCommands() throws IOException {
        assertThat(read("brew: {commands: none}").brewCommands()).isEqualTo(Settings.BrewCommands.NONE);
        assertThat(read("brew: {commands: ask}").brewCommands()).isEqualTo(Settings.BrewCommands.ASK);
        assertThat(read("brew: {commands: [make]}").brewCommands().allowed()).containsExactly("make");
        assertThatThrownBy(() -> read("brew: {commands: []}")).hasMessageContaining("brew.commands").hasMessageContaining("none");
        assertThatThrownBy(() -> read("brew: {commands: [make, \"\"]}")).hasMessageContaining("brew.commands");
        assertThatThrownBy(() -> read("brew: {commands: always}")).hasMessageContaining("brew.commands");
    }

    @Test @DisplayName("validation.mutation: on, off, true or false")
    void mutation() throws IOException {
        assertThat(read("validation: {mutation: on}").mutation()).isTrue();
        assertThat(read("validation: {mutation: true}").mutation()).isTrue();
        assertThat(read("validation: {mutation: \"off\"}").mutation()).isFalse();
        assertThat(read("validation: {mutation: false}").mutation()).isFalse();
        assertThatThrownBy(() -> read("validation: {mutation: sometimes}")).hasMessageContaining("validation.mutation");
    }

    @Test @DisplayName("the deadline: seconds, minutes, or a number of seconds")
    void deadline() throws IOException {
        assertThat(read("children: {deadline: 90s}").childrenDeadline()).isEqualTo(Duration.ofSeconds(90));
        assertThat(read("children: {deadline: 3m}").childrenDeadline()).isEqualTo(Duration.ofMinutes(3));
        assertThat(read("children: {deadline: 45}").childrenDeadline()).isEqualTo(Duration.ofSeconds(45));
        assertThatThrownBy(() -> read("children: {deadline: 0s}")).hasMessageContaining("children.deadline");
        assertThatThrownBy(() -> read("children: {deadline: soon}")).hasMessageContaining("children.deadline");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "branches: many                     | branches         | per-artifact, single",
            "brew: {stopAt: feature}            | brew.stopAt      | criterion, milestone, phase",
            "uploads: {keep: everything}        | uploads.keep     | reference, original",
            "children: {depth: -1}              | children.depth   | 0 or more",
            "children: {depth: deep}            | children.depth   | 0 or more",
            "children: {parallel: 0}            | children.parallel | 1 or more",
            "validation: {live: mysql}          | validation.live  | a list",
            "models: {init: 42}                 | models.init      | a model id",
            "models: {init: \"\"}               | models.init      | a model id",
            "models: {deploy: claude-opus-5-5}  | models.deploy    | init, design, plan, brew, run, review"})
    @DisplayName("a malformed value is refused, naming the dial and what it takes")
    void malformed(String yaml, String dial, String expected) {
        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cafeai-loop.yaml")
                .hasMessageContaining(dial)
                .hasMessageContaining(expected);
    }

    @Test @DisplayName("a dial the loop doesn't have is refused, so a typo isn't silently ignored")
    void unknownDials() {
        assertThatThrownBy(() -> read("brew: {stopat: phase}")).hasMessageContaining("brew.stopat").hasMessageContaining("stopAt");
        assertThatThrownBy(() -> read("brach: single")).hasMessageContaining("brach").hasMessageContaining("branches");
        assertThatThrownBy(() -> read("children: {width: 2}")).hasMessageContaining("children.width");
    }

    @Test @DisplayName("a file that isn't YAML, or isn't a set of dials, says so")
    void notYaml() {
        assertThatThrownBy(() -> read("brew: [stopAt: phase\n")).hasMessageContaining("cafeai-loop.yaml").hasMessageContaining("YAML");
        assertThatThrownBy(() -> read("- one\n- two\n")).hasMessageContaining("cafeai-loop.yaml");
        assertThatThrownBy(() -> read("brew: phase\n")).hasMessageContaining("brew");
    }

    @Test @DisplayName("the file's dials are listed for help and errors")
    void known() {
        assertThat(Settings.DIALS).containsExactly("branches", "brew.stopAt", "brew.commands", "models.<command>",
                "validation.mutation", "validation.live", "children.depth", "children.parallel", "children.deadline",
                "uploads.keep");
        assertThat(Settings.COMMANDS).isEqualTo(List.of("init", "design", "plan", "brew", "run", "review"));
    }
}
