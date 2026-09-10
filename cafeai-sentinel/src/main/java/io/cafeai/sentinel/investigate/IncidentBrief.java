package io.cafeai.sentinel.investigate;

import io.cafeai.sentinel.incident.Incident;

import java.time.Duration;

/**
 * Renders an {@link Incident} into the plain-text brief handed to
 * {@link ClusterInvestigator#investigate} as the user message. Triage output
 * only — the facts sentinel already has; the agent gathers the rest from the
 * cluster.
 */
public final class IncidentBrief {

    private IncidentBrief() {
    }

    public static String of(Incident incident) {
        StringBuilder sb = new StringBuilder();
        sb.append("Incident ").append(incident.id())
                .append(" — severity ").append(incident.severity()).append('\n');
        sb.append("Namespace: ").append(incident.namespace()).append('\n');
        sb.append("Workload: ").append(incident.workload())
                .append("  (investigate only this workload and its pods)\n");
        sb.append("Triage reasons: ").append(String.join(", ", incident.reasons())).append('\n');
        sb.append("Affected pods: ")
                .append(incident.affectedPods().isEmpty() ? "(none currently)"
                        : String.join(", ", incident.affectedPods()))
                .append('\n');
        sb.append("Signals: ").append(incident.signalCount())
                .append(" over ").append(humanDuration(
                        Duration.between(incident.firstSeen(), incident.lastSeen())))
                .append('\n');

        if (!incident.evidence().isEmpty()) {
            sb.append("Observed so far:\n");
            for (String line : incident.evidence()) {
                sb.append("  - ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String humanDuration(Duration d) {
        if (d.isNegative() || d.isZero()) {
            return "moments";
        }
        long s = d.toSeconds();
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return d.toMinutes() + "m";
        }
        return d.toHours() + "h" + (d.toMinutesPart()) + "m";
    }
}
