package io.cafeai.loop;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dials (ROADMAP-03, 1.4; {@code DESIGN_SPEC.md}, The dials): the team's style, from
 * {@code cafeai-loop.yaml} at the project's root. A dial not set takes the spec's default; a file
 * that isn't there means every default. A value the dial doesn't take, or a dial the loop doesn't
 * have, is refused with the dial's name and what it takes, so a typo is never silently ignored.
 */
public record Settings(Branches branches, StopAt stopAt, BrewCommands brewCommands, Map<String, String> models,
                       boolean mutation, List<String> live, int childrenDepth, int childrenParallel,
                       Duration childrenDeadline, UploadsKeep uploadsKeep) {

    public static final String FILE = "cafeai-loop.yaml";

    /** Every dial, as written in the file. */
    public static final List<String> DIALS = List.of("branches", "brew.stopAt", "brew.commands", "models.default", "models.<command>",
            "validation.mutation", "validation.live", "children.depth", "children.parallel", "children.deadline",
            "uploads.keep");

    /** The loop's commands with agents of their own, which {@code models.<command>} can name. */
    public static final List<String> COMMANDS = List.of("init", "design", "plan", "brew", "run", "review");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern DEADLINE = Pattern.compile("(\\d+)\\s*(s|m)?");
    private static final Map<String, Set<String>> SECTIONS = Map.of(
            "brew", Set.of("stopAt", "commands"),
            "validation", Set.of("mutation", "live"),
            "children", Set.of("depth", "parallel", "deadline"),
            "uploads", Set.of("keep"));

    public Settings {
        models = Map.copyOf(models);
        live = List.copyOf(live);
    }

    /** One branch per artifact, or one for all: a matter of style. */
    public enum Branches implements Named {
        PER_ARTIFACT("per-artifact"), SINGLE("single");
        private final String value;
        Branches(String value) { this.value = value; }
        @Override public String value() { return value; }
    }

    /** Where {@code brew} stops for the change process. */
    public enum StopAt implements Named {
        CRITERION, MILESTONE, PHASE;
        @Override public String value() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Whether to keep an upload's original, or only a reference to it. */
    public enum UploadsKeep implements Named {
        REFERENCE, ORIGINAL;
        @Override public String value() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Which commands {@code brew} may run by itself, beyond build and test. */
    public record BrewCommands(Mode mode, List<String> allowed) {
        public static final BrewCommands ASK = new BrewCommands(Mode.ASK, List.of());
        public static final BrewCommands NONE = new BrewCommands(Mode.NONE, List.of());
        public enum Mode { ASK, NONE, ALLOWED }
        public BrewCommands { allowed = List.copyOf(allowed); }
    }

    private interface Named { String value(); }

    /** The spec's defaults. */
    public static Settings defaults() {
        return new Settings(Branches.PER_ARTIFACT, StopAt.CRITERION, BrewCommands.ASK, Map.of(), true, List.of(), 1, 4,
                Duration.ofSeconds(60), UploadsKeep.REFERENCE);
    }

    /** The project's settings: its {@code cafeai-loop.yaml}, over the defaults. */
    public static Settings read(Path projectRoot) {
        Path file = projectRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) return defaults();
        JsonNode root;
        try {
            root = YAML.readTree(file.toFile());
        } catch (JacksonException e) {
            throw refused("isn't valid YAML: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) return defaults();
        if (!root.isObject()) throw refused("should be a set of dials (" + String.join(", ", DIALS) + ")");
        return parse(root);
    }

    /** The model a command's agent uses, {@code provider:model}: its own, else {@code models.default}; none if neither is set. */
    public Optional<String> model(String command) {
        return Optional.ofNullable(models.getOrDefault(command, models.get("default")));
    }

    private static Settings parse(JsonNode root) {
        Settings d = defaults();
        var known = new ArrayList<>(List.of("branches", "models"));
        known.addAll(SECTIONS.keySet());
        root.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) throw refused("has no dial '" + name + "'; the dials are " + String.join(", ", DIALS));
        });
        SECTIONS.forEach((section, keys) -> {
            JsonNode node = root.get(section);
            if (node == null || node.isNull()) return;
            if (!node.isObject()) throw refused(section + " should hold " + String.join(", ", keys.stream().sorted().toList()));
            node.fieldNames().forEachRemaining(key -> {
                if (!keys.contains(key)) {
                    throw refused("has no dial '" + section + "." + key + "'; " + section + " has "
                            + String.join(", ", keys.stream().sorted().map(k -> section + "." + k).toList()));
                }
            });
        });
        return new Settings(
                named(root.get("branches"), "branches", Branches.values(), d.branches()),
                named(at(root, "brew", "stopAt"), "brew.stopAt", StopAt.values(), d.stopAt()),
                brewCommands(at(root, "brew", "commands"), d.brewCommands()),
                models(root.get("models")),
                mutation(at(root, "validation", "mutation"), d.mutation()),
                live(at(root, "validation", "live")),
                count(at(root, "children", "depth"), "children.depth", 0, d.childrenDepth()),
                count(at(root, "children", "parallel"), "children.parallel", 1, d.childrenParallel()),
                deadline(at(root, "children", "deadline"), d.childrenDeadline()),
                named(at(root, "uploads", "keep"), "uploads.keep", UploadsKeep.values(), d.uploadsKeep()));
    }

    private static JsonNode at(JsonNode root, String section, String key) {
        JsonNode node = root.get(section);
        return node == null ? null : node.get(key);
    }

    private static <E extends Enum<E> & Named> E named(JsonNode node, String dial, E[] values, E fallback) {
        if (node == null || node.isNull()) return fallback;
        String text = node.asText();
        return Arrays.stream(values).filter(v -> v.value().equals(text)).findFirst().orElseThrow(() ->
                refused(dial + " is '" + text + "'; it takes " + String.join(", ", Arrays.stream(values).map(Named::value).toList())));
    }

    private static BrewCommands brewCommands(JsonNode node, BrewCommands fallback) {
        if (node == null || node.isNull()) return fallback;
        String takes = "; it takes ask, none, or a list of commands";
        if (node.isTextual() && node.asText().equals("ask")) return BrewCommands.ASK;
        if (node.isTextual() && node.asText().equals("none")) return BrewCommands.NONE;
        if (!node.isArray()) throw refused("brew.commands is '" + node.asText() + "'" + takes);
        if (node.isEmpty()) throw refused("brew.commands is an empty list; say none instead");
        var allowed = new ArrayList<String>();
        for (JsonNode command : node) {
            if (!command.isTextual() || command.asText().isBlank()) throw refused("brew.commands holds a blank or non-text entry" + takes);
            allowed.add(command.asText());
        }
        return new BrewCommands(BrewCommands.Mode.ALLOWED, allowed);
    }

    private static Map<String, String> models(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        var keys = new ArrayList<String>();
        keys.add("default");
        keys.addAll(COMMANDS);
        if (!node.isObject()) throw refused("models should map " + String.join(", ", keys) + " to provider:model");
        var models = new LinkedHashMap<String, String>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String dial = "models." + e.getKey();
            if (!keys.contains(e.getKey())) {
                throw refused("has no dial '" + dial + "'; models can name " + String.join(", ", keys));
            }
            String problem = e.getValue().isTextual() ? Models.problem(e.getValue().asText()) : "it should be provider:model";
            if (problem != null) throw refused(dial + ": " + problem);
            models.put(e.getKey(), e.getValue().asText());
        }
        return models;
    }

    private static boolean mutation(JsonNode node, boolean fallback) {
        if (node == null || node.isNull()) return fallback;
        if (node.isBoolean()) return node.asBoolean();
        String text = node.asText();
        if (text.equals("on")) return true;
        if (text.equals("off")) return false;
        throw refused("validation.mutation is '" + text + "'; it takes on or off");
    }

    private static List<String> live(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw refused("validation.live should be a list of environments");
        var live = new ArrayList<String>();
        for (JsonNode env : node) {
            if (!env.isTextual() || env.asText().isBlank()) throw refused("validation.live should be a list of environments");
            live.add(env.asText());
        }
        return live;
    }

    private static int count(JsonNode node, String dial, int least, int fallback) {
        if (node == null || node.isNull()) return fallback;
        if (!node.canConvertToInt() || !node.isIntegralNumber() || node.asInt() < least) {
            throw refused(dial + " is '" + node.asText() + "'; it takes a whole number, " + least + " or more");
        }
        return node.asInt();
    }

    private static Duration deadline(JsonNode node, Duration fallback) {
        if (node == null || node.isNull()) return fallback;
        Matcher m = DEADLINE.matcher(node.asText().strip());
        if (!m.matches() || Long.parseLong(m.group(1)) <= 0) {
            throw refused("children.deadline is '" + node.asText() + "'; it takes a time, such as 60s or 2m");
        }
        long n = Long.parseLong(m.group(1));
        return "m".equals(m.group(2)) ? Duration.ofMinutes(n) : Duration.ofSeconds(n);
    }

    private static IllegalArgumentException refused(String what) {
        return new IllegalArgumentException(FILE + " " + what);
    }
}
