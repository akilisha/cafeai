package io.cafeai.core.login;

import com.fasterxml.jackson.databind.JsonNode;
import io.cafeai.core.internal.AnthropicProfile;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * {@code cafeai login claude}: a Claude Console sign-in through Anthropic's own {@code ant auth
 * login}, which goes through the company's SSO and needs no API key. CafeAI never handles the
 * sign-in itself; it runs {@code ant} for it, then reads and renews the profile {@code ant} saved.
 */
final class ClaudeLogin {

    /** Runs a program: with the terminal handed over ({@code interactive}), or with its output captured. */
    interface Processes {
        /** Returns the exit status; {@code output} receives stdout and stderr when not interactive. */
        int run(List<String> command, boolean interactive, StringBuilder output);
    }

    static final String INSTALL = """
            `cafeai login claude` uses Anthropic's `ant` CLI to sign in. Install it:

              macOS:            brew install anthropics/tap/ant
              Windows, Linux:   download it from https://github.com/anthropics/anthropic-cli/releases
              with Go:          go install github.com/anthropics/anthropic-cli/cmd/ant@latest

            then put it on the PATH, or point CAFEAI_ANT at it.""";

    private final PrintStream out;
    private final PrintStream err;
    private final UnaryOperator<String> env;
    private final Processes processes;

    ClaudeLogin(PrintStream out, PrintStream err, UnaryOperator<String> env, Processes processes) {
        this.out = out;
        this.err = err;
        this.env = env;
        this.processes = processes;
    }

    int login() {
        Optional<Path> found = findAnt();
        if (found.isEmpty()) {
            err.println(INSTALL);
            return 1;
        }
        String ant = found.get().toString();
        var version = new StringBuilder();
        int status = processes.run(List.of(ant, "--version"), false, version);
        String said = version.toString().strip();
        if (status != 0 || !said.startsWith("ant version")) {
            if (said.contains("Apache Ant")) {
                err.println("The `ant` at " + ant + " is Apache Ant, the Java build tool, not Anthropic's CLI.\n\n"
                        + INSTALL.replace("then put it on the PATH, or point", "then point"));
            } else {
                err.println("The `ant` at " + ant + " doesn't look like Anthropic's CLI (`ant --version` said: "
                        + (said.isEmpty() ? "nothing" : firstLine(said)) + ").\n\n" + INSTALL);
            }
            return 1;
        }

        out.println("Signing in with Anthropic's CLI (" + firstLine(said) + "). Your browser opens on the Claude Console;");
        out.println("sign in there, through your company's single sign-on if it has one, and pick the workspace.");
        int signedIn = processes.run(List.of(ant, "auth", "login"), true, null);
        if (signedIn != 0) {
            err.println("cafeai: `ant auth login` failed (exit " + signedIn + "); not signed in.");
            return 1;
        }
        Optional<AnthropicProfile> profile = AnthropicProfile.active(env);
        if (profile.isEmpty() || profile.get().stored().isEmpty()) {
            err.println("cafeai: `ant auth login` finished, but no sign-in was saved where CafeAI looks ("
                    + AnthropicProfile.dir(env) + ").");
            return 1;
        }
        out.println("Signed in to Claude: " + describe(profile.get().stored().get()) + " (Anthropic profile '"
                + profile.get().name() + "').");
        if (present(env.apply("ANTHROPIC_API_KEY"))) {
            out.println("Note: ANTHROPIC_API_KEY is set in this environment, and apps started from it use that key"
                    + " instead. Unset it to use the sign-in.");
        }
        return 0;
    }

    int logout() {
        Optional<AnthropicProfile> profile = AnthropicProfile.active(env);
        if (profile.isPresent() && profile.get().signOut()) {
            out.println("Removed the Claude sign-in (Anthropic profile '" + profile.get().name() + "') from this machine,"
                    + " as `ant auth logout` does.");
        } else {
            out.println("Not signed in to Claude.");
        }
        return 0;
    }

    /** The {@code cafeai status} line for Claude. */
    String status() {
        if (present(env.apply("ANTHROPIC_API_KEY"))) return "from ANTHROPIC_API_KEY";
        Optional<AnthropicProfile> profile;
        try {
            profile = AnthropicProfile.active(env);
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
        if (profile.isEmpty()) return "not signed in (cafeai login claude)";
        if (!profile.get().isSignIn()) return "profile '" + profile.get().name() + "' is " + profile.get().type()
                + ", not a sign-in (cafeai login claude)";
        Optional<JsonNode> stored = profile.get().stored();
        if (stored.isEmpty()) return "not signed in (cafeai login claude)";
        JsonNode expires = stored.get().get("expires_at");
        String validity = expires == null || !expires.canConvertToLong() ? ""
                : Instant.ofEpochSecond(expires.asLong()).isAfter(Instant.now())
                        ? ", token valid until " + Instant.ofEpochSecond(expires.asLong())
                        : ", token expired, renewed on the next call";
        return "signed in: " + describe(stored.get()) + " (profile '" + profile.get().name() + "'" + validity + ")";
    }

    private static String describe(JsonNode stored) {
        String org = stored.path("organization_name").asText("");
        String email = stored.path("account_email").asText("");
        String workspace = stored.path("workspace_name").asText(stored.path("workspace_id").asText(""));
        var parts = new ArrayList<String>();
        if (!org.isEmpty()) parts.add(org);
        if (!email.isEmpty()) parts.add("as " + email);
        if (!workspace.isEmpty()) parts.add("workspace " + workspace);
        return parts.isEmpty() ? "Claude Console" : String.join(", ", parts);
    }

    /** {@code $CAFEAI_ANT}, else {@code ant} on the PATH. */
    Optional<Path> findAnt() {
        String explicit = env.apply("CAFEAI_ANT");
        if (present(explicit)) return Files.isRegularFile(Path.of(explicit)) ? Optional.of(Path.of(explicit)) : Optional.empty();
        String path = env.apply("PATH");
        if (path == null) path = env.apply("Path");
        if (path == null) return Optional.empty();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        // Anthropic ships ant.exe on Windows; Apache Ant is ant.bat / ant.cmd, which this then reports.
        List<String> names = windows ? List.of("ant.exe", "ant.bat", "ant.cmd") : List.of("ant");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                Path candidate = Path.of(dir.strip()).resolve(name);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Real processes: the terminal handed over, or the output captured. */
    static int runProcess(List<String> command, boolean interactive, StringBuilder output) {
        try {
            var builder = new ProcessBuilder(command);
            if (interactive) {
                builder.inheritIO();
                return builder.start().waitFor();
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            process.getOutputStream().close();
            output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            return process.waitFor();
        } catch (IOException e) {
            output.append(e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
