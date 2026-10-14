package io.cafeai.loop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether a plan is in the format of {@code ARTIFACTS.md} (ROADMAP-03, 4.1), leaves estimates to the
 * team (4.3), and, planned again, keeps every number it had (4.4). It compares the roadmap directory
 * as it was before {@code plan} ran with how it is now; every problem is reported, so the agent can
 * fix them at once.
 */
public record PlanCheck(List<String> problems) {

    /** A phase's statuses (ARTIFACTS.md, Statuses). */
    static final Set<String> STATUSES = Set.of("defined", "in-progress", "blocked", "escalated", "deferred",
            "cancelled", "out-of-scope", "completed");

    private static final Pattern ROADMAP_FILE = Pattern.compile("ROADMAP-(\\d+)\\.md");
    private static final Pattern TITLE = Pattern.compile("# ROADMAP-(\\d+)\\s+[—–-]\\s+\\S.*");
    static final Pattern PHASE = Pattern.compile("(?m)^## Phase (\\d+)\\b.*$");
    static final Pattern SECTION = Pattern.compile("(?m)^## ");
    static final Pattern CRITERION = Pattern.compile("(?m)^[-*]\\s+\\*\\*(\\d+)\\.(\\d+)\\*\\*(.*)$");
    static final Pattern STATUS = Pattern.compile("(?m)^\\*\\*Status:?\\*\\*:?\\s*`?([A-Za-z-]+)`?");
    private static final Pattern ENTRY = Pattern.compile("(?m)^[-*]\\s+\\*\\*\\d{4}-\\d{2}-\\d{2}\\.?\\*\\*.*$");
    private static final Map<String, Pattern> REQUIRED = new LinkedHashMap<>();

    static {
        for (String field : List.of("Objective", "Acceptance criteria", "Testing criteria", "Validation criteria")) {
            REQUIRED.put(field, Pattern.compile("(?m)^\\*\\*" + field + ":?\\*\\*"));
        }
    }

    /** Whether there is nothing to say. */
    public boolean ok() {
        return problems.isEmpty();
    }

    /** The project's {@code docs/roadmap/} Markdown files, by name; none when there is no such directory. */
    public static Map<String, String> snapshot(Path project) {
        Path dir = project.resolve("docs").resolve("roadmap");
        var files = new TreeMap<String, String>();
        if (!Files.isDirectory(dir)) return files;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md")).toList()) {
                files.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir, e);
        }
        return files;
    }

    /** Checks the plan as it is ({@code after}) against how it was before planning ({@code before}). */
    public static PlanCheck of(Map<String, String> before, Map<String, String> after) {
        var problems = new ArrayList<String>();
        var roadmaps = new TreeMap<String, String>();
        after.keySet().forEach(name -> {
            Matcher m = ROADMAP_FILE.matcher(name);
            if (m.matches()) roadmaps.put(m.group(1), name);
        });
        if (roadmaps.isEmpty()) problems.add("There is no roadmap yet: docs/roadmap/ROADMAP-NN.md, one per design goal.");

        for (var r : roadmaps.entrySet()) {
            String number = r.getKey(), name = r.getValue(), text = after.get(name);
            String milestone = "MILESTONE-" + number + ".md";
            if (before.containsKey(name)) kept(name, criteria(before.get(name)), criteria(text), problems);
            // A roadmap planning left as it was, with its milestones, is the team's history: not plan's to reformat.
            if (text.equals(before.get(name)) && Objects.equals(after.get(milestone), before.get(milestone))) continue;
            tracking(number, format(number, name, text, problems), after, problems);
        }
        for (String name : before.keySet()) {
            if (ROADMAP_FILE.matcher(name).matches() && !after.containsKey(name)) {
                problems.add(name + " was deleted: a roadmap no longer wanted stays, its phases marked `cancelled`.");
            }
        }
        for (var m : after.entrySet()) {
            if (!m.getKey().startsWith("MILESTONE-")) continue;
            String earlier = before.get(m.getKey());
            entries(m.getKey(), earlier, m.getValue(), problems);
            estimates(m.getKey(), earlier, m.getValue(), problems);
        }
        return new PlanCheck(List.copyOf(problems));
    }

    /** The roadmap's format; returns its criteria, by number. */
    private static Map<String, String> format(String number, String name, String text, List<String> problems) {
        String title = text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("<!--"))
                .findFirst().orElse("");
        Matcher t = TITLE.matcher(title);
        if (!t.matches() || !t.group(1).equals(number)) {
            problems.add(name + ": its title should be '# ROADMAP-" + number + " — <its topic>', not '" + title + "'.");
        }
        Matcher phase = PHASE.matcher(text);
        var starts = new ArrayList<int[]>();
        while (phase.find()) starts.add(new int[] {phase.start(), phase.end(), Integer.parseInt(phase.group(1))});
        if (starts.isEmpty()) {
            problems.add(name + " has no phases: each is '## Phase N — <its name>'.");
            return Map.of();
        }
        var all = new LinkedHashMap<String, String>();
        for (int[] s : starts) {
            Matcher next = SECTION.matcher(text);
            int end = next.find(s[1]) ? next.start() : text.length();
            String body = text.substring(s[1], end);
            String where = name + ", phase " + s[2];
            for (var field : REQUIRED.entrySet()) {
                if (!field.getValue().matcher(body).find()) problems.add(where + ": no " + field.getKey() + ".");
            }
            Matcher status = STATUS.matcher(body);
            if (!status.find()) {
                problems.add(where + ": no Status.");
            } else if (!STATUSES.contains(status.group(1))) {
                problems.add(where + ": '" + status.group(1) + "' isn't a status; one of " + String.join(", ", STATUSES.stream().sorted().toList()) + ".");
            }
            Map<String, String> criteria = criteria(body);
            if (criteria.isEmpty()) problems.add(where + ": no acceptance criteria, numbered '- **" + s[2] + ".1** ...'.");
            for (var c : criteria.entrySet()) {
                String id = c.getKey();
                if (!id.startsWith(s[2] + ".")) {
                    problems.add(where + ": criterion " + id + " is numbered for another phase; in phase " + s[2] + " they are " + s[2] + ".N.");
                }
            }
            Matcher dup = CRITERION.matcher(body);
            var seen = new java.util.HashSet<String>();
            while (dup.find()) {
                String id = dup.group(1) + "." + dup.group(2);
                if (!seen.add(id)) problems.add(where + ": criterion " + id + " is numbered twice.");
            }
            all.putAll(criteria);
        }
        return all;
    }

    /** The milestones track each criterion by number, and only the roadmap's; the index lists them both. */
    private static void tracking(String number, Map<String, String> criteria, Map<String, String> after, List<String> problems) {
        String name = "MILESTONE-" + number + ".md";
        String milestone = after.get(name);
        if (milestone == null) {
            problems.add("ROADMAP-" + number + ".md has no milestones: " + name + ", tracking each criterion by its number.");
        } else {
            var tracked = new java.util.LinkedHashSet<String>();
            Matcher m = CRITERION.matcher(milestone);
            while (m.find()) tracked.add(m.group(1) + "." + m.group(2));
            for (String id : criteria.keySet()) {
                if (!tracked.contains(id)) problems.add(name + " doesn't track " + id + ": '- **" + id + "** open'.");
            }
            for (String id : tracked) {
                if (!criteria.containsKey(id)) problems.add(name + " tracks " + id + ", which ROADMAP-" + number + " doesn't have.");
            }
        }
        String index = after.get("README.md");
        if (index == null) {
            if (!problems.contains(NO_INDEX)) problems.add(NO_INDEX);
        } else if (index.lines().noneMatch(l -> l.contains("ROADMAP-" + number) && l.contains("MILESTONE-" + number))) {
            problems.add("README.md, the index, doesn't list ROADMAP-" + number + " with MILESTONE-" + number + ".");
        }
    }

    private static final String NO_INDEX = "There is no index, docs/roadmap/README.md: number, milestones, topic, status.";

    /** Planning again: every criterion keeps its number. */
    private static void kept(String name, Map<String, String> before, Map<String, String> after, List<String> problems) {
        for (var c : before.entrySet()) {
            String id = c.getKey(), text = c.getValue();
            String movedTo = text.isEmpty() || text.equals(after.get(id)) ? null : after.entrySet().stream()
                    .filter(a -> !a.getKey().equals(id) && a.getValue().equals(text)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (movedTo != null) {
                problems.add(name + ": criterion " + id + " was renumbered as " + movedTo + ". Numbers are kept: " + id
                        + " keeps its text, and a new criterion takes a new number.");
            } else if (!after.containsKey(id)) {
                problems.add(name + ": criterion " + id + " was removed. Criteria are never removed or renumbered:"
                        + " one dropped stays, marked cancelled ('- **" + id + "** *(cancelled)* ...').");
            }
        }
    }

    /** Entries are added, never rewritten. */
    private static void entries(String name, String before, String after, List<String> problems) {
        if (before == null) return;
        Matcher m = ENTRY.matcher(before);
        while (m.find()) {
            String entry = m.group().strip();
            if (after.lines().map(String::strip).noneMatch(entry::equals)) {
                problems.add(name + ": an entry was rewritten or removed ('" + abbreviate(entry) + "'). Entries are"
                        + " added, never rewritten: when the picture changes, a new dated entry says so.");
            }
        }
    }

    /** Estimates and timelines are the team's: plan writes none, and changes none of theirs. */
    private static void estimates(String name, String before, String after, List<String> problems) {
        Map<String, List<String>> earlier = before == null ? Map.of() : estimates(before);
        for (var row : estimates(after).entrySet()) {
            List<String> was = earlier.get(row.getKey());
            for (int i = 0; i < row.getValue().size(); i++) {
                String cell = row.getValue().get(i);
                if (blank(cell) || (was != null && i < was.size() && cell.equals(was.get(i)))) continue;
                problems.add(name + ": an estimate or timeline was written for '" + row.getKey() + "' ('" + cell
                        + "'). They are the team's: leave them '—', and the team's own as they are.");
                break;
            }
        }
    }

    /** The status table's estimate and timeline cells, by the row's first cell. */
    private static Map<String, List<String>> estimates(String text) {
        var rows = new LinkedHashMap<String, List<String>>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            List<String> header = cells(lines.get(i));
            int estimate = indexOf(header, "estimate"), timeline = indexOf(header, "timeline");
            if (estimate < 0 && timeline < 0) continue;
            for (int j = i + 1; j < lines.size() && lines.get(j).strip().startsWith("|"); j++) {
                List<String> row = cells(lines.get(j));
                if (row.isEmpty() || row.getFirst().matches("[-: ]*")) continue;
                var values = new ArrayList<String>();
                for (int col : new int[] {estimate, timeline}) values.add(col >= 0 && col < row.size() ? row.get(col) : "");
                rows.put(row.getFirst(), values);
            }
            break;
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String l = line.strip();
        if (!l.startsWith("|")) return List.of();
        if (l.endsWith("|")) l = l.substring(0, l.length() - 1);
        return Stream.of(l.substring(1).split("\\|", -1)).map(String::strip).toList();
    }

    private static int indexOf(List<String> header, String word) {
        for (int i = 0; i < header.size(); i++) if (header.get(i).toLowerCase(java.util.Locale.ROOT).startsWith(word)) return i;
        return -1;
    }

    private static boolean blank(String cell) {
        return cell.isEmpty() || cell.equals("—") || cell.equals("–") || cell.equals("-");
    }

    /** A text's criteria, by number: each one's text, its continuation lines joined, spaces normalised. */
    static Map<String, String> criteria(String text) {
        var criteria = new LinkedHashMap<String, String>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = CRITERION.matcher(lines.get(i));
            if (!m.matches()) continue;
            var b = new StringBuilder(m.group(3));
            for (int j = i + 1; j < lines.size() && !lines.get(j).isBlank() && Character.isWhitespace(lines.get(j).charAt(0)); j++) {
                b.append(' ').append(lines.get(j));
            }
            criteria.putIfAbsent(m.group(1) + "." + m.group(2), b.toString().strip().replaceAll("\\s+", " "));
        }
        return criteria;
    }

    private static String abbreviate(String s) {
        return s.length() <= 70 ? s : s.substring(0, 67) + "...";
    }
}
