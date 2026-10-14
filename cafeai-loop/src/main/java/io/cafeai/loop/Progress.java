package io.cafeai.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the plan stands, read from the artifacts alone (ROADMAP-04, 1.1; DESIGN_SPEC.md, The
 * artifacts: no hidden state): every criterion of the loop's roadmaps, in roadmap order, done or
 * not. A criterion is done when its milestones say it is met or waived, or the roadmap marks it
 * cancelled; a phase is done when its status says so, and held back while it is blocked, escalated
 * or deferred. A roadmap in an older format, with no phases the loop can read, isn't the loop's.
 */
public record Progress(Optional<Next> next, List<String> heldBack, int met, int total) {

    /** The criterion to work on next, with what its phase says about it. */
    public record Next(String roadmap, int phase, String phaseName, String criterion, String text, String objective,
                       String testing) {}

    private static final Pattern ROADMAP_FILE = Pattern.compile("ROADMAP-(\\d+)\\.md");
    private static final Pattern PHASE_NAME = Pattern.compile("## Phase \\d+\\s*[—–-]\\s*(.+)");
    private static final Set<String> DONE_PHASE = Set.of("completed", "cancelled", "out-of-scope");
    private static final Set<String> HELD_PHASE = Set.of("blocked", "escalated", "deferred");
    private static final Set<String> DONE_CRITERION = Set.of("met", "waived");

    /** Where the plan in {@code docs/roadmap/} stands ({@link PlanCheck#snapshot}). */
    public static Progress of(Map<String, String> roadmapDir) {
        var roadmaps = new TreeMap<Integer, String>();
        roadmapDir.keySet().forEach(name -> {
            Matcher m = ROADMAP_FILE.matcher(name);
            if (m.matches()) roadmaps.put(Integer.parseInt(m.group(1)), m.group(1));
        });
        Next next = null;
        var held = new ArrayList<String>();
        int met = 0, total = 0;
        for (String number : roadmaps.values()) {
            String roadmap = roadmapDir.get("ROADMAP-" + number + ".md");
            Map<String, String> states = states(roadmapDir.getOrDefault("MILESTONE-" + number + ".md", ""));
            Matcher phase = PlanCheck.PHASE.matcher(roadmap);
            var starts = new ArrayList<int[]>();
            while (phase.find()) starts.add(new int[] {phase.start(), phase.end(), Integer.parseInt(phase.group(1))});
            for (int[] s : starts) {
                Matcher end = PlanCheck.SECTION.matcher(roadmap);
                String body = roadmap.substring(s[1], end.find(s[1]) ? end.start() : roadmap.length());
                String status = status(body);
                for (var c : PlanCheck.criteria(body).entrySet()) {
                    total++;
                    boolean done = DONE_PHASE.contains(status) || c.getValue().contains("*(cancelled)*")
                            || DONE_CRITERION.contains(states.getOrDefault(c.getKey(), "open"));
                    if (done) {
                        met++;
                    } else if (next == null && !HELD_PHASE.contains(status)) {
                        next = new Next("ROADMAP-" + number, s[2], name(roadmap.substring(s[0], s[1])), c.getKey(),
                                c.getValue(), field(body, "Objective"), field(body, "Testing criteria"));
                    }
                }
                if (HELD_PHASE.contains(status)) held.add("ROADMAP-" + number + ", phase " + s[2] + ": " + status);
            }
        }
        return new Progress(Optional.ofNullable(next), List.copyOf(held), met, total);
    }

    /** Each criterion's state in the milestones: the first word after its number ({@code met}, {@code waived}, {@code open}). */
    private static Map<String, String> states(String milestone) {
        var states = new TreeMap<String, String>();
        Matcher m = PlanCheck.CRITERION.matcher(milestone);
        while (m.find()) {
            String rest = m.group(3).strip();
            String word = rest.isEmpty() ? "open" : rest.split("[\\s:,.;]", 2)[0].toLowerCase(Locale.ROOT);
            states.putIfAbsent(m.group(1) + "." + m.group(2), word);
        }
        return states;
    }

    private static String status(String body) {
        Matcher m = PlanCheck.STATUS.matcher(body);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "defined";
    }

    private static String name(String heading) {
        Matcher m = PHASE_NAME.matcher(heading.strip());
        return m.matches() ? m.group(1).strip() : heading.strip();
    }

    /** A phase's bold field ({@code **Objective:** ...}), its wrapped lines joined; empty when it has none. */
    private static String field(String body, String label) {
        Matcher m = Pattern.compile("(?m)^\\*\\*" + Pattern.quote(label) + ":?\\*\\*:?[ \\t]*").matcher(body);
        if (!m.find()) return "";
        var text = new StringBuilder();
        for (String line : body.substring(m.end()).split("\\R")) {
            if (line.isBlank() || (!text.isEmpty() && line.startsWith("**"))) break;
            text.append(text.isEmpty() ? "" : " ").append(line.strip());
        }
        return text.toString();
    }
}
