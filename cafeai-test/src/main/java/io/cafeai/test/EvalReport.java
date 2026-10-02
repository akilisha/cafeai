package io.cafeai.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of {@link Evals#run()}: every case, every check, and what the answer
 * was. {@link #toString()} is a readable report, so a failing assertion shows why.
 *
 * <p>The assertions throw a plain {@link AssertionError}, which every test framework
 * reports as a failure.
 */
public final class EvalReport {

    /** One check on one answer. {@code detail} says why it failed (or the judge's reason). */
    public record CheckResult(String check, boolean passed, String detail) { }

    /**
     * One case: the question, the answer (empty if there was none), the guardrail that
     * blocked it or the error it raised, if any, its checks, and how long it took.
     */
    public record Result(String question, String answer, String blockedBy, String error,
                         List<CheckResult> checks, Duration took) {
        public Result {
            checks = List.copyOf(checks);
        }

        /** Every check passed. */
        public boolean passed() {
            return checks.stream().allMatch(CheckResult::passed);
        }
    }

    private final List<Result> results;

    EvalReport(List<Result> results) {
        this.results = List.copyOf(results);
    }

    public List<Result> results() { return results; }

    public List<Result> failures() {
        return results.stream().filter(r -> !r.passed()).toList();
    }

    public int passedCount() { return results.size() - failures().size(); }

    /** The share of cases that passed, from 0 to 1. */
    public double passRate() {
        return results.isEmpty() ? 0 : (double) passedCount() / results.size();
    }

    /** Fails, with this report as the message, unless every case passed. */
    public EvalReport assertAllPassed() {
        if (!failures().isEmpty()) throw new AssertionError(this);
        return this;
    }

    /** Fails, with this report as the message, unless at least {@code min} (0–1) of the cases passed. */
    public EvalReport assertPassRate(double min) {
        if (passRate() < min) {
            throw new AssertionError(String.format("pass rate %.0f%% is below %.0f%%%n%s", passRate() * 100, min * 100, this));
        }
        return this;
    }

    /**
     * What changed against an earlier run of the same suite — say, the same cases on the
     * previous model. Cases are matched by question.
     */
    public Comparison compare(EvalReport before) {
        Map<String, Result> earlier = new LinkedHashMap<>();
        for (Result r : before.results) earlier.put(r.question(), r);
        List<Result> regressed = new ArrayList<>();
        List<Result> fixed = new ArrayList<>();
        List<Result> added = new ArrayList<>();
        for (Result now : results) {
            Result then = earlier.get(now.question());
            if (then == null) added.add(now);
            else if (then.passed() && !now.passed()) regressed.add(now);
            else if (!then.passed() && now.passed()) fixed.add(now);
        }
        return new Comparison(before, this, regressed, fixed, added);
    }

    /** {@link #compare}'s answer: cases that now fail, now pass, or are new. */
    public record Comparison(EvalReport before, EvalReport after,
                             List<Result> regressed, List<Result> fixed, List<Result> added) {
        public Comparison {
            regressed = List.copyOf(regressed);
            fixed = List.copyOf(fixed);
            added = List.copyOf(added);
        }

        /** Fails, listing them, if any case that passed before fails now. */
        public Comparison assertNoRegressions() {
            if (!regressed.isEmpty()) throw new AssertionError(this);
            return this;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(String.format("Evals: %d/%d passed before, %d/%d now%n",
                    before.passedCount(), before.results.size(), after.passedCount(), after.results.size()));
            section(out, "Now failing", regressed, true);
            section(out, "Now passing", fixed, false);
            section(out, "New cases", added, true);
            if (regressed.isEmpty() && fixed.isEmpty() && added.isEmpty()) out.append("  no change\n");
            return out.toString();
        }

        private static void section(StringBuilder out, String title, List<Result> rs, boolean details) {
            if (rs.isEmpty()) return;
            out.append("  ").append(title).append(":\n");
            for (Result r : rs) {
                out.append("    ").append(r.passed() ? "PASS  " : "FAIL  ").append(r.question()).append('\n');
                if (details && !r.passed()) appendFailures(out, r, "          ");
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(String.format("Evals: %d/%d passed (%.0f%%)%n",
                passedCount(), results.size(), passRate() * 100));
        for (Result r : results) {
            out.append(r.passed() ? "  PASS  " : "  FAIL  ").append(r.question())
               .append("  (").append(r.took().toMillis()).append(" ms)\n");
            if (!r.passed()) appendFailures(out, r, "        ");
        }
        return out.toString();
    }

    private static void appendFailures(StringBuilder out, Result r, String indent) {
        for (CheckResult c : r.checks()) {
            if (c.passed()) continue;
            out.append(indent).append("- ").append(c.check());
            if (!c.detail().isEmpty()) out.append(": ").append(c.detail());
            out.append('\n');
        }
    }
}
