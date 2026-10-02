package io.cafeai.dev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A JBang script's dependencies, as JBang resolves them. */
final class Jbang {

    private Jbang() { }

    /** The script's {@code //DEPS} lines, to notice when they change. */
    static List<String> depsLines(Path script) {
        try {
            return Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(l -> l.startsWith("//DEPS")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The jars of the script's {@code //DEPS}, from {@code jbang info classpath --deps-only}. */
    static List<Path> depsClasspath(Path script) {
        if (depsLines(script).isEmpty()) return List.of();
        IOException last = null;
        for (String exe : executables()) {
            try {
                Process p = new ProcessBuilder(exe, "info", "classpath", "--deps-only", script.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
                    throw new IllegalStateException("jbang could not resolve the //DEPS of " + script.getFileName());
                }
                List<Path> jars = new ArrayList<>();
                for (String entry : output.split(File.pathSeparator)) {
                    if (!entry.isBlank()) jars.add(Path.of(entry.trim()));
                }
                return jars;
            } catch (IOException e) {
                last = e;   // not found under this name; try the next
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted resolving //DEPS");
            }
        }
        throw new IllegalStateException("A script with //DEPS needs jbang on the PATH to resolve them"
                + (last == null ? "" : " (" + last.getMessage() + ")"));
    }

    private static List<String> executables() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("jbang.cmd", "jbang") : List.of("jbang");
    }
}
