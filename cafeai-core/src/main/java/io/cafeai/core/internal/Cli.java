package io.cafeai.core.internal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Finding and running another program's command-line tool ({@code az}, {@code aws},
 * {@code gcloud}): on the PATH, including Windows' {@code .exe} / {@code .cmd} / {@code .bat};
 * its output captured with a time limit, or the terminal handed over to it. Public only so
 * {@code io.cafeai.core.login} and {@code cafeai-identity} can reach it.
 */
public final class Cli {

    /** A finished run: its exit status and what it printed. */
    public record Result(int exit, String out, String err) {
        public boolean ok() { return exit == 0; }
    }

    private Cli() {}

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The program on the PATH, if there. */
    public static Optional<Path> find(String name, UnaryOperator<String> env) {
        String path = env.apply("PATH");
        if (path == null) path = env.apply("Path");
        if (path == null) return Optional.empty();
        List<String> names = windows() ? List.of(name + ".exe", name + ".cmd", name + ".bat", name) : List.of(name);
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String candidate : names) {
                Path file;
                try {
                    file = Path.of(dir.strip()).resolve(candidate);
                } catch (RuntimeException e) {
                    continue;   // a PATH entry that isn't a valid path
                }
                if (Files.isRegularFile(file) && (windows() || Files.isExecutable(file))) return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /** Runs the command with its output captured; past {@code timeout} it is stopped and reported. */
    public static Result run(List<String> command, Duration timeout) {
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile("cafeai-cli", ".out");
            err = Files.createTempFile("cafeai-cli", ".err");
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(-1, "", String.join(" ", command) + " did not finish within " + timeout.toSeconds() + " s");
            }
            return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result(-1, "", "Could not run " + command.getFirst() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "Interrupted while running " + command.getFirst());
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    /** Runs the command with the terminal handed over to it; returns its exit status. */
    public static int handOver(List<String> command) {
        try {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static File nullDevice() {
        return new File(windows() ? "NUL" : "/dev/null");
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a temp file; the OS cleans its temp directory
        }
    }
}
