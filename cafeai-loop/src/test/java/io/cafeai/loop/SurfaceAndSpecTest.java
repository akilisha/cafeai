package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ROADMAP-03, 3.1 and 3.4/3.5: what init notices without reading; whether a spec is complete, and what's open. */
@DisplayName("Noticing a project, and checking a spec")
class SurfaceAndSpecTest {

    @TempDir Path project;

    @Test @DisplayName("an empty folder, or one with only the loop's settings, is a new project")
    void newProject() throws IOException {
        assertThat(Surface.of(project).codebase()).isFalse();
        Files.writeString(project.resolve(Settings.FILE), "models: {default: \"anthropic:x\"}\n");
        Files.createDirectories(project.resolve(".idea"));
        assertThat(Surface.of(project).codebase()).isFalse();
    }

    @Test @DisplayName("an existing codebase: its build, deployment and git noticed by name, nothing read")
    void existing() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("build.gradle"), "plugins {}");
        Files.writeString(project.resolve("docker-compose.yml"), "services: {}");
        Files.createDirectories(project.resolve("k8s"));
        Files.createDirectories(project.resolve(".github/workflows"));
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve(".git"));
        Surface s = Surface.of(project);
        assertThat(s.codebase()).isTrue();
        assertThat(s.gitRepository()).isTrue();
        assertThat(s.buildFiles()).containsExactly("build.gradle", "pom.xml");
        assertThat(s.deployment()).containsExactly(".github/workflows/", "docker-compose.yml", "k8s/");
        assertThat(s.describe()).contains("a git repository").contains("pom.xml").contains("build.gradle")
                .contains("docker-compose.yml").contains("src/");
    }

    private static final String COMPLETE = """
            # Demo — design spec

            ## 1. The product
            A demo.
            ## 2. As built
            Small.
            ## 3. Tech stack
            Java.
            ## 4. Conventions
            main.
            ## 5. Intended changes
            One.
            ## 6. Decisions
            None.
            ## 7. Assumptions
            None.
            ## 8. Open questions
            None at present.
            ## 9. Risks
            None.
            """;

    @Test @DisplayName("a complete spec has nothing missing and nothing open")
    void complete() {
        SpecCheck check = SpecCheck.of(COMPLETE, true);
        assertThat(check.missing()).isEmpty();
        assertThat(check.openQuestions()).isEmpty();
    }

    @Test @DisplayName("missing sections are named; As built is required only of an existing codebase")
    void missing() {
        String noRisksNoStack = COMPLETE.replace("## 9. Risks\nNone.\n", "").replace("## 3. Tech stack\nJava.\n", "");
        assertThat(SpecCheck.of(noRisksNoStack, false).missing()).containsExactly("Tech stack", "Risks");
        String newProject = COMPLETE.replace("## 2. As built\nSmall.\n", "");
        assertThat(SpecCheck.of(newProject, false).missing()).isEmpty();
        assertThat(SpecCheck.of(newProject, true).missing()).containsExactly("As built");
    }

    @Test @DisplayName("open questions are read from their section, as written")
    void open() {
        String withOpen = COMPLETE.replace("## 8. Open questions\nNone at present.\n",
                "## 8. Open questions\n- Which database?\n- How many users, at most?\n");
        assertThat(SpecCheck.of(withOpen, true).openQuestions()).containsExactly("Which database?", "How many users, at most?");
        for (String none : List.of("None.", "None at present.", "", "*(none)*")) {
            String text = COMPLETE.replace("None at present.", none);
            assertThat(SpecCheck.of(text, true).openQuestions()).as(none).isEmpty();
        }
    }

    @Test @DisplayName("open questions end at the next section, and their heading is found in any case")
    void openSection() {
        String spec = COMPLETE.replace("## 8. Open questions\nNone at present.\n", "## Open Questions\n- Which database?\n")
                .replace("## 9. Risks\nNone.\n", "## 9. Risks\n- The data may be lost.\n");
        assertThat(SpecCheck.of(spec, true).openQuestions()).containsExactly("Which database?");
    }

    @Test @DisplayName("headings are matched by name, not number or case")
    void headings() {
        String renumbered = COMPLETE.replace("## 3. Tech stack", "## Tech Stack").replace("## 9. Risks", "### 10. risks");
        assertThat(SpecCheck.of(renumbered, true).missing()).isEmpty();
    }
}
