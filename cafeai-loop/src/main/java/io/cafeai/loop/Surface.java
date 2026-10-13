package io.cafeai.loop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What {@code init} notices about a project before it may read it (ROADMAP-03, 3.1): the names at
 * its top level only (a git repository, build files, deployment files, directories), never what is
 * in them.
 */
public record Surface(boolean codebase, boolean gitRepository, List<String> buildFiles, List<String> deployment,
                      List<String> topLevel) {

    private static final Set<String> BUILD = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "package.json", "go.mod", "Cargo.toml", "pyproject.toml", "requirements.txt");
    private static final Set<String> DEPLOYMENT = Set.of("Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "compose.yml", "compose.yaml", "k8s", "helm", "charts", ".github/workflows", ".gitlab-ci.yml", "Jenkinsfile");

    /** What is at the project's top level, by name. */
    public static Surface of(Path root) {
        var names = new ArrayList<String>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(p -> names.add(p.getFileName() + (Files.isDirectory(p) ? "/" : "")));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not look at " + root, e);
        }
        names.sort(null);
        List<String> visible = names.stream()
                .filter(n -> !n.startsWith(".") && !n.equals(Settings.FILE)).toList();
        List<String> build = names.stream().filter(BUILD::contains).toList();
        var deployment = new ArrayList<String>();
        for (String d : DEPLOYMENT) {
            Path p = root.resolve(d);
            if (Files.exists(p)) deployment.add(d + (Files.isDirectory(p) ? "/" : ""));
        }
        deployment.sort(null);
        return new Surface(!visible.isEmpty(), Files.isDirectory(root.resolve(".git")), build, deployment, visible);
    }

    /** What was noticed, said plainly. */
    public String describe() {
        if (!codebase) return "an empty folder: a new project" + (gitRepository ? ", already a git repository" : "");
        var parts = new ArrayList<String>();
        parts.add(gitRepository ? "a git repository" : "not a git repository");
        if (!buildFiles.isEmpty()) parts.add("built with " + String.join(", ", buildFiles));
        if (!deployment.isEmpty()) parts.add("deployment files: " + String.join(", ", deployment));
        parts.add("at its top level: " + String.join(", ", topLevel));
        return "an existing project: " + String.join("; ", parts);
    }
}
