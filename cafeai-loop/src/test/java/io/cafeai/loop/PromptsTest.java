package io.cafeai.loop;

import io.cafeai.loop.tools.ProjectFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROADMAP-03, 2.1: each command's prompt a versioned resource; what a command writes records the version. */
@DisplayName("The prompts: versioned resources, and the version stamped on what they write")
class PromptsTest {

    @TempDir Path project;

    @Test @DisplayName("init's and plan's prompts load from the module, with the version they ship with")
    void load() {
        Prompts.Prompt init = Prompts.of("init");
        assertThat(init.command()).isEqualTo("init");
        assertThat(init.text()).contains("You are `cafeai init`");
        assertThat(init.version()).isEqualTo(Prompts.version()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        assertThat(Prompts.of("plan").text()).contains("You are `cafeai plan`");
    }

    @Test @DisplayName("the version is the build's, not written by hand")
    void buildVersion() throws IOException {
        String gradle = Files.readString(Path.of("..", "build.gradle"));
        assertThat(gradle).contains("version = '" + Prompts.version() + "'");
    }

    @Test @DisplayName("a command without a prompt yet, or not a command, says so")
    void missing() {
        assertThatThrownBy(() -> Prompts.of("design")).hasMessageContaining("no prompt yet").hasMessageContaining("design");
        assertThatThrownBy(() -> Prompts.of("deploy")).hasMessageContaining("not one of the loop's commands");
    }

    @Test @DisplayName("the stamp names the loop's version and the command")
    void stamp() {
        assertThat(Prompts.stamp("plan")).isEqualTo("<!-- cafeai-loop " + Prompts.version() + " · plan -->");
    }

    @Test @DisplayName("an artifact a command writes is stamped on its first line; the stamp is replaced, never repeated")
    void stamped() throws IOException {
        var files = new ProjectFiles(project, () -> true, Prompts.stamp("init"));
        files.write("DESIGN_SPEC.md", "# A product\n\nWhat it is.\n");
        assertThat(Files.readString(project.resolve("DESIGN_SPEC.md")))
                .isEqualTo(Prompts.stamp("init") + "\n# A product\n\nWhat it is.\n");

        var plan = new ProjectFiles(project, () -> true, Prompts.stamp("plan"));
        plan.write("DESIGN_SPEC.md", files.read("DESIGN_SPEC.md").replace("What it is.", "What it is, revised."));
        String revised = Files.readString(project.resolve("DESIGN_SPEC.md"));
        assertThat(revised).startsWith(Prompts.stamp("plan") + "\n# A product").contains("revised");
        assertThat(revised).doesNotContain(Prompts.stamp("init"));
        assertThat(revised.split("cafeai-loop ", -1)).hasSize(2);
    }

    @Test @DisplayName("roadmaps, milestones, design detail and the changelog are artifacts; code and other files are not")
    void whichFiles() throws IOException {
        var files = new ProjectFiles(project, () -> true, Prompts.stamp("plan"));
        for (String artifact : new String[] {"DESIGN_SPEC.md", "CHANGELOG.md", "docs/roadmap/ROADMAP-01.md",
                "docs/roadmap/MILESTONE-01.md", "docs/roadmap/README.md", "docs/design/AUTH.md"}) {
            files.write(artifact, "# x\n");
            assertThat(Files.readString(project.resolve(artifact))).as(artifact).startsWith(Prompts.stamp("plan"));
        }
        for (String other : new String[] {"README.md", "src/main/java/App.java", "docs/guide.md", "notes.md"}) {
            files.write(other, "plain\n");
            assertThat(Files.readString(project.resolve(other))).as(other).isEqualTo("plain\n");
        }
    }

    @Test @DisplayName("without a stamp (the developer's own tools), nothing is added")
    void noStamp() throws IOException {
        new ProjectFiles(project, () -> true).write("DESIGN_SPEC.md", "# x\n");
        assertThat(Files.readString(project.resolve("DESIGN_SPEC.md"))).isEqualTo("# x\n");
    }
}
