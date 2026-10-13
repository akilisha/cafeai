package io.cafeai.loop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The commands' system prompts (ROADMAP-03, 2.1): resources of this module, versioned with CafeAI,
 * so a project knows which version of the loop produced its artifacts. They are the framework's,
 * open in its repository; a project steers through its own artifacts, never these.
 */
public final class Prompts {

    /** A command's system prompt, and the version it ships with. */
    public record Prompt(String command, String text, String version) { }

    private static final String VERSION = loadVersion();

    private Prompts() {}

    /** The command's system prompt. */
    public static Prompt of(String command) {
        if (!Settings.COMMANDS.contains(command)) {
            throw new IllegalArgumentException("'" + command + "' is not one of the loop's commands ("
                    + String.join(", ", Settings.COMMANDS) + ")");
        }
        try (InputStream in = Prompts.class.getResourceAsStream("prompts/" + command + ".md")) {
            if (in == null) throw new IllegalStateException("`cafeai " + command + "` has no prompt yet");
            return new Prompt(command, new String(in.readAllBytes(), StandardCharsets.UTF_8), VERSION);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the prompt of " + command, e);
        }
    }

    /** The loop's version: CafeAI's, as built. */
    public static String version() {
        return VERSION;
    }

    /**
     * The line an artifact written by {@code command} starts with: an HTML comment, invisible when
     * the Markdown is read, naming the loop's version and the command.
     */
    public static String stamp(String command) {
        return "<!-- cafeai-loop " + VERSION + " · " + command + " -->";
    }

    private static String loadVersion() {
        try (InputStream in = Prompts.class.getResourceAsStream("version.properties")) {
            if (in == null) throw new IllegalStateException("The build didn't write cafeai-loop's version");
            var properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read cafeai-loop's version", e);
        }
    }
}
