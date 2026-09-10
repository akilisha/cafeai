package io.cafeai.sentinel.triage;

import java.util.Set;

/**
 * What {@link TriageRules} concluded about one {@link io.cafeai.sentinel.watch.PodState}.
 *
 * @param verdict the most severe signal found
 * @param reasons every distinct reason that contributed (e.g.
 *                {@code CrashLoopBackOff}, {@code OOMKilled}, {@code FailedScheduling}) —
 *                folded into the incident's reason set
 * @param detail  a short human line for logs / evidence
 */
public record TriageResult(Verdict verdict, Set<String> reasons, String detail) {

    public TriageResult {
        reasons = Set.copyOf(reasons);
    }

    public static TriageResult benign(String detail) {
        return new TriageResult(Verdict.BENIGN, Set.of(), detail);
    }

    /** True when this verdict opens or updates an incident. */
    public boolean actionable() {
        return verdict != Verdict.BENIGN;
    }
}
