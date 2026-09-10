package io.cafeai.sentinel.incident;

import io.cafeai.sentinel.triage.TriageResult;
import io.cafeai.sentinel.triage.Verdict;
import io.cafeai.sentinel.watch.WorkloadRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One coalesced cluster incident — keyed on a {@link WorkloadRef}, so the N
 * crashing replicas of one broken Deployment are a single incident, not N.
 *
 * <p>Immutable. {@link io.cafeai.sentinel.IncidentTracker} keeps the current
 * value per workload and replaces it as signals fold in; every
 * {@link IncidentEvent} carries a snapshot safe to hand to a sink.
 *
 * <p>Phase 2 fills the structural fields only. The investigation-era fields —
 * {@code summary}, {@code likelyCause}, {@code suggestedActions} — arrive with
 * ROADMAP-18 Phase 3.
 *
 * @param id            stable id for the life of the incident ({@code inc-<hex>})
 * @param status        {@link IncidentStatus#OPEN} until resolved
 * @param severity      the most severe {@link Verdict} observed
 * @param namespace     the workload's namespace
 * @param workload      the resolved top controller this incident is keyed on
 * @param firstSeen     first actionable signal
 * @param lastSeen      most recent signal of any kind
 * @param lastErrorAt   most recent {@link Verdict#ERROR} signal, or {@code null}
 *                      if only {@code NOTABLE} signals have been seen
 * @param signalCount   how many pod snapshots have folded in
 * @param reasons       distinct triage reasons accumulated
 * @param affectedPods  pods currently contributing; drains as they recover or
 *                      are deleted, and an empty set past the cooldown resolves
 *                      the incident
 * @param evidence      bounded, newest-last human lines for logs / the sink
 */
public record Incident(
        String id,
        IncidentStatus status,
        Verdict severity,
        String namespace,
        WorkloadRef workload,
        Instant firstSeen,
        Instant lastSeen,
        Instant lastErrorAt,
        int signalCount,
        Set<String> reasons,
        Set<String> affectedPods,
        List<String> evidence) {

    public Incident {
        reasons = Set.copyOf(reasons);
        affectedPods = Set.copyOf(affectedPods);
        evidence = List.copyOf(evidence);
    }

    /** Opens a fresh incident from the first actionable signal for a workload. */
    public static Incident open(String id, String namespace, WorkloadRef workload,
                                Instant now, TriageResult triage, String podName, String evidenceLine) {
        Instant errorAt = triage.verdict() == Verdict.ERROR ? now : null;
        return new Incident(id, IncidentStatus.OPEN, triage.verdict(), namespace, workload,
                now, now, errorAt, 1,
                new LinkedHashSet<>(triage.reasons()),
                podName == null ? Set.of() : Set.of(podName),
                evidenceLine == null ? List.of() : List.of(evidenceLine));
    }

    /** Folds another actionable signal into this incident. */
    public Incident fold(Instant now, TriageResult triage, String podName,
                         String evidenceLine, int maxEvidence) {
        Set<String> mergedReasons = new LinkedHashSet<>(reasons);
        mergedReasons.addAll(triage.reasons());

        Set<String> pods = new LinkedHashSet<>(affectedPods);
        if (podName != null) {
            pods.add(podName);
        }

        List<String> lines = new ArrayList<>(evidence);
        if (evidenceLine != null) {
            lines.add(evidenceLine);
            while (lines.size() > maxEvidence) {
                lines.remove(0);
            }
        }

        Verdict mergedSeverity = triage.verdict().atLeast(severity) ? triage.verdict() : severity;
        Instant errorAt = triage.verdict() == Verdict.ERROR ? now : lastErrorAt;

        return new Incident(id, status, mergedSeverity, namespace, workload,
                firstSeen, now, errorAt, signalCount + 1, mergedReasons, pods, lines);
    }

    /** True when {@code triage} carries a reason this incident has not seen before. */
    public boolean introducesNewReason(TriageResult triage) {
        return !reasons.containsAll(triage.reasons());
    }

    /** Drops a pod that has recovered or been deleted from the affected set. */
    public Incident withoutPod(String podName) {
        if (!affectedPods.contains(podName)) {
            return this;
        }
        Set<String> pods = new LinkedHashSet<>(affectedPods);
        pods.remove(podName);
        return new Incident(id, status, severity, namespace, workload,
                firstSeen, lastSeen, lastErrorAt, signalCount, reasons, pods, evidence);
    }

    /** Marks the incident resolved at {@code now}. */
    public Incident resolved(Instant now) {
        return new Incident(id, IncidentStatus.RESOLVED, severity, namespace, workload,
                firstSeen, now, lastErrorAt, signalCount, reasons, affectedPods, evidence);
    }
}
