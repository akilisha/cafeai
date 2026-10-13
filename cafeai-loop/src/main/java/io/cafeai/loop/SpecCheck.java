package io.cafeai.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a design spec has the sections the loop needs (ROADMAP-03, 3.4), and what it holds as
 * open questions (3.5). Headings are matched by name, whatever their number, level or case.
 */
public record SpecCheck(List<String> missing, List<String> openQuestions) {

    /** The sections every design spec has; As built only of an existing codebase. */
    public static final List<String> SECTIONS = List.of("The product", "As built", "Tech stack", "Conventions",
            "Intended changes", "Decisions", "Assumptions", "Open questions", "Risks");

    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,6}\\s+(?:\\d+\\.\\s*)?(.+?)\\s*$");
    private static final Pattern ITEM = Pattern.compile("(?m)^\\s*(?:[-*]|\\d+\\.)\\s+(.+?)\\s*$");

    /** Checks {@code spec}; {@code existingCodebase} decides whether As built is required. */
    public static SpecCheck of(String spec, boolean existingCodebase) {
        var headings = new ArrayList<String>();
        Matcher m = HEADING.matcher(spec);
        while (m.find()) headings.add(m.group(1).toLowerCase(Locale.ROOT));
        var missing = new ArrayList<String>();
        for (String section : SECTIONS) {
            if (section.equals("As built") && !existingCodebase) continue;
            if (!headings.contains(section.toLowerCase(Locale.ROOT))) missing.add(section);
        }
        return new SpecCheck(missing, openQuestions(spec));
    }

    private static List<String> openQuestions(String spec) {
        Matcher m = HEADING.matcher(spec);
        int start = -1;
        int end = spec.length();
        while (m.find()) {
            if (start >= 0) {
                end = m.start();
                break;
            }
            if (m.group(1).equalsIgnoreCase("Open questions")) start = m.end();
        }
        if (start < 0) return List.of();
        var questions = new ArrayList<String>();
        Matcher item = ITEM.matcher(spec.substring(start, end));
        while (item.find()) questions.add(item.group(1));
        return questions;
    }
}
