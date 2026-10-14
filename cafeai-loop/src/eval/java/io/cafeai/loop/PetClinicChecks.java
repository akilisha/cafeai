package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.test.EvalReport;
import io.cafeai.test.Evals;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the evals check in what {@code init} and {@code plan} produce on PetClinic (ROADMAP-03, 5.1),
 * measured against the run by hand (ROADMAP-02): the run by hand passes every check.
 *
 * <p>Most checks are plain code over the artifacts, so they can be tested themselves without a
 * model ({@code PetClinicChecksTest}). The judged ones need a judge, a model grading against a
 * rubric, and run only when the eval has one.
 */
final class PetClinicChecks {

    private static final Pattern SECTION = Pattern.compile("(?m)^#{2,6}\\s+(?:\\d+\\.\\s*)?(.+?)\\s*$");

    private PetClinicChecks() {}

    /** The checks on {@code init}'s design spec, PetClinic being an existing codebase. */
    static EvalReport ofSpec(String spec, AiProvider judge) {
        Evals evals = Evals.of(q -> spec);
        if (judge != null) evals.judge(judge);
        evals.ask("init: the spec's sections")
                .expect("every section, As built included", s -> SpecCheck.of(s, true).missing().isEmpty());
        evals.ask("init: the packages found")
                .expect("names the owner, vet, system and model packages", PetClinicChecks::packages);
        evals.ask("init: the gaps found")
                .expect("says nobody signs in", PetClinicChecks::nobodySignsIn)
                .expect("says a visit has no time, vet or status", PetClinicChecks::visitLacks);
        evals.ask("init: what is built, not assumed")
                .expect("notices the two builds, Maven and Gradle", s -> has(s, "(?i)maven") && has(s, "(?i)gradle"))
                .expect("notices three databases (H2, MySQL, PostgreSQL)",
                        s -> has(s, "(?i)\\bH2\\b") && has(s, "(?i)mysql") && has(s, "(?i)postgre"));
        evals.ask("init: the conventions")
                .expect("records the DCO sign-off", s -> has(s, "(?i)\\bDCO\\b|sign-?off|signed-off-by"))
                .expect("records spring-javaformat", s -> has(s, "(?i)spring[- ]?java[- ]?format"))
                .expect("records feature/ branches", s -> has(s, "feature/"));
        evals.ask("init: decisions are the developer's")
                .expect("no status of migrated visits under Decisions (never decided: an assumption)",
                        s -> !has(section(s, "Decisions"), "(?i)migrat[^\\n]*(COMPLETED|status)"));
        if (judge != null) {
            evals.ask("init: judged").judgedBy("The design spec says that owners don't sign in (staff use the"
                    + " application), and that a visit has no time, no assigned vet and no status today.")
                    .judgedBy("The spec's Decisions section holds only choices a developer made or confirmed;"
                            + " anything presented as guessed or defaulted is under Assumptions instead.");
        }
        return evals.run();
    }

    /** The checks on {@code plan}'s roadmaps, milestones and index, from {@code spec}. */
    static EvalReport ofPlan(Map<String, String> before, Map<String, String> after, AiProvider judge) {
        String roadmaps = after.entrySet().stream().filter(e -> e.getKey().startsWith("ROADMAP-"))
                .map(Map.Entry::getValue).collect(Collectors.joining("\n"));
        // Each roadmap numbers its own criteria from 1.1: counted roadmap by roadmap.
        List<String> criteria = after.entrySet().stream().filter(e -> e.getKey().startsWith("ROADMAP-"))
                .flatMap(e -> PlanCheck.criteria(e.getValue()).values().stream()).toList();
        String milestones = after.entrySet().stream().filter(e -> e.getKey().startsWith("MILESTONE-"))
                .map(Map.Entry::getValue).collect(Collectors.joining("\n"));
        Evals evals = Evals.of(q -> q.contains("milestones") ? milestones : roadmaps);
        if (judge != null) evals.judge(judge);
        evals.ask("plan: the plan's format, estimates and numbers")
                .expect("passes the plan check", r -> PlanCheck.of(before, after).ok());
        evals.ask("plan: one roadmap per design goal")
                .expect("at least three roadmaps", r -> after.keySet().stream().filter(k -> k.startsWith("ROADMAP-")).count() >= 3);
        evals.ask("plan: numbered, testable criteria")
                .expect("at least 25 criteria", r -> criteria.size() >= 25)
                .expect("no criterion in vague words", r -> criteria.stream().noneMatch(PetClinicChecks::vague));
        evals.ask("plan: the spec's facts in the criteria")
                .expect("opening hours (09:00)", r -> has(r, "09:00"))
                .expect("a visit's 30 minutes", r -> has(r, "(?i)30[ -]?min"))
                .expect("notes up to 1,024 characters", r -> has(r, "1,?024"))
                .expect("three databases", r -> has(r, "(?i)mysql") && has(r, "(?i)postgre"));
        evals.ask("plan: the milestones")
                .expect("flags readings to confirm", m -> has(m, "(?i)to confirm|confirm in review"));
        if (judge != null) {
            evals.ask("plan: judged").judgedBy("Each acceptance criterion is one observable outcome that an"
                    + " automated test could check, with numbers where there is a limit.");
        }
        return evals.run();
    }

    /** Each of PetClinic's packages named as code ({@code `owner`}, {@code `owner/`}, {@code `.owner`}), however the spec lays them out. */
    static boolean packages(String spec) {
        return Stream.of("owner", "vet", "system", "model").allMatch(p -> has(spec, "`[\\w.]*\\b" + p + "/?`"));
    }

    /** The spec's paragraphs and list items, each with its wrapped lines joined: Markdown wraps where it likes. */
    static Stream<String> blocks(String text) {
        return Stream.of(text.split("\\n\\s*\\n|\\n(?=\\s*(?:[-*]|\\d+\\.)\\s)")).map(b -> b.replaceAll("\\s+", " "));
    }

    static boolean nobodySignsIn(String spec) {
        return blocks(spec).anyMatch(b -> has(b,
                "(?i)(nobody|no one|don't|do not|doesn't|does not|never|not)\\b[^.]{0,40}\\b(sign|log)s?[ -]?(in|on)\\b"
                + "|\\bno (sign-in|login|log-in|authentication|user accounts?)\\b"));
    }

    static boolean visitLacks(String spec) {
        // Said either way: what it lacks ("no time, no vet, no status"), or all it has ("only a date and a description").
        return blocks(spec).anyMatch(b -> has(b, "(?i)\\bvisit") && (has(b, "(?i)\\bonly\\b[^.]{0,20}\\bdate\\b")
                || has(b, "(?i)\\b(no|without|lacks?|nor)\\b") && has(b, "(?i)\\btime\\b") && has(b, "(?i)\\bvet\\b")
                        && has(b, "(?i)\\bstatus\\b")));
    }

    static boolean vague(String criterion) {
        return has(criterion.toLowerCase(Locale.ROOT),
                "\\b(user-friendly|intuitive|robust|seamless(ly)?|appropriate(ly)?|properly|as needed|etc\\.|and so on|fast enough|quickly)\\b");
    }

    /** The body of the spec's section {@code name}, empty if there is none. */
    static String section(String spec, String name) {
        Matcher m = SECTION.matcher(spec);
        int start = -1;
        while (m.find()) {
            if (start >= 0) return spec.substring(start, m.start());
            if (m.group(1).equalsIgnoreCase(name)) start = m.end();
        }
        return start < 0 ? "" : spec.substring(start);
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }
}
