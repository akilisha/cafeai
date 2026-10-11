package io.cafeai.core.login;

import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.internal.SavedKeys;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@code cafeai} command: sign in to a model vendor once, so CafeAI apps on this machine
 * reach it without a key in code, in a shell profile, or in an environment variable.
 *
 * <pre>
 *   cafeai login claude      signs in to the Claude Console with Anthropic's ant CLI (no key)
 *   cafeai login openai      asks for the key (hidden) and saves it
 *   cafeai logout openai     forgets the saved key
 *   cafeai status            where each vendor's credential comes from
 * </pre>
 *
 * <p>When the key is piped in ({@code echo $KEY | cafeai login openai}) it is read from standard
 * input. A vendor's environment variable, when set, still takes precedence over the saved key;
 * {@code cafeai status} shows which one an app will use.
 */
public final class CafeLogin {

    /** Reads a key: hidden at a terminal, else a line from standard input. */
    interface KeyReader {
        String read(String prompt);
    }

    private final PrintStream out;
    private final PrintStream err;
    private final KeyReader keys;
    private final UnaryOperator<String> env;
    private final ClaudeLogin claude;

    CafeLogin(PrintStream out, PrintStream err, KeyReader keys, UnaryOperator<String> env,
              ClaudeLogin.Processes processes) {
        this.out = out;
        this.err = err;
        this.keys = keys;
        this.env = env;
        this.claude = new ClaudeLogin(out, err, env, processes);
    }

    public static void main(String[] args) {
        var login = new CafeLogin(System.out, System.err, CafeLogin::readKey, System::getenv, ClaudeLogin::runProcess);
        System.exit(login.run(args));
    }

    /** Runs one command; returns the exit status (0 done, 1 failed, 2 wrong usage). */
    int run(String... args) {
        if (args.length == 0) return usage();
        try {
            return switch (args[0]) {
                case "login"  -> args.length == 2 ? login(args[1]) : usage();
                case "logout" -> args.length == 2 ? logout(args[1]) : usage();
                case "status" -> args.length == 1 ? status() : usage();
                case "help", "-h", "--help" -> { help(out); yield 0; }
                default -> usage();
            };
        } catch (RuntimeException e) {
            err.println("cafeai: " + e.getMessage());
            return 1;
        }
    }

    private int login(String name) {
        if (isClaude(name)) return claude.login();
        Optional<KeyVendor> found = vendor(name);
        if (found.isEmpty()) return 2;
        KeyVendor vendor = found.get();

        out.println("Create a " + vendor.displayName() + " API key at " + vendor.keysPage());
        String key = keys.read("Paste the key: ");
        key = key == null ? "" : key.strip();
        if (key.isEmpty()) {
            err.println("cafeai: no key given; nothing saved.");
            return 1;
        }
        if (key.chars().anyMatch(Character::isWhitespace)) {
            err.println("cafeai: that doesn't look like a key (it contains spaces); nothing saved.");
            return 1;
        }
        SavedKeys.save(vendor, key);
        out.println("Saved the " + vendor.displayName() + " key (" + masked(key) + ") in " + SavedKeys.file());
        String fromEnv = env.apply(vendor.envVar());
        if (fromEnv != null && !fromEnv.isBlank()) {
            out.println("Note: " + vendor.envVar() + " is set in this environment, and apps started from it use that"
                    + " instead. Unset it to use the saved key.");
        }
        return 0;
    }

    private int logout(String name) {
        if (isClaude(name)) return claude.logout();
        Optional<KeyVendor> found = vendor(name);
        if (found.isEmpty()) return 2;
        KeyVendor vendor = found.get();
        if (SavedKeys.remove(vendor)) {
            out.println("Forgot the saved " + vendor.displayName() + " key. (Revoke it at "
                    + vendor.keysPage() + " if it should stop working everywhere.)");
        } else {
            out.println("No " + vendor.displayName() + " key was saved.");
        }
        return 0;
    }

    private int status() {
        out.println("Keys file: " + SavedKeys.file());
        out.println();
        out.printf("  %-9s %s%n", "claude", claude.status());
        for (KeyVendor vendor : KeyVendor.values()) {
            SavedKeys.Resolved r = SavedKeys.resolve(vendor, env);
            String where = switch (r.source()) {
                case ENVIRONMENT -> "from " + vendor.envVar() + "  " + masked(r.key());
                case SAVED -> "saved by cafeai login  " + masked(r.key());
                case NONE -> "not set (cafeai login " + vendor.id() + ")";
            };
            out.printf("  %-9s %s%n", vendor.id(), where);
        }
        return 0;
    }

    private Optional<KeyVendor> vendor(String name) {
        Optional<KeyVendor> vendor = KeyVendor.byId(name);
        if (vendor.isEmpty()) {
            err.println("cafeai: unknown vendor '" + name + "'. Known vendors: claude, " + KeyVendor.ids());
        }
        return vendor;
    }

    private int usage() {
        help(err);
        return 2;
    }

    private static void help(PrintStream to) {
        to.println("Usage:");
        to.println("  cafeai login claude      sign in to the Claude Console (Anthropic's ant CLI, no key)");
        to.println("  cafeai login <vendor>    save the vendor's API key on this machine");
        to.println("  cafeai logout <vendor>   sign out, or forget the saved key");
        to.println("  cafeai status            where each vendor's credential comes from");
        to.println();
        to.println("Vendors: claude, " + KeyVendor.ids());
    }

    private static boolean isClaude(String name) {
        return name.equalsIgnoreCase("claude");
    }

    /** The last four characters only, e.g. {@code ****wxyz}. */
    static String masked(String key) {
        return key.length() <= 8 ? "****" : "****" + key.substring(key.length() - 4);
    }

    private static String readKey(String prompt) {
        Console console = System.console();
        if (console != null && console.isTerminal()) {
            char[] typed = console.readPassword("%s", prompt);
            return typed == null ? null : new String(typed);
        }
        // Piped in, or no terminal (an IDE's run window, Git Bash's mintty): a plain line.
        try {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
