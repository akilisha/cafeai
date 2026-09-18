package io.cafeai.sentinel.watch;

import java.time.Instant;
import java.util.List;

/**
 * A correlated snapshot of one pod: its identity, the workload it belongs to,
 * its container states, and the recent pod-scoped Events — assembled by
 * {@link io.cafeai.sentinel.ClusterWatch} on every pod add / update / delete.
 *
 * <p>This record decides nothing — it's the input, not the verdict.
 * {@link io.cafeai.sentinel.triage.TriageRules} classifies a stream of these,
 * and {@link io.cafeai.sentinel.IncidentTracker} coalesces them into incidents.
 *
 * @param namespace    the pod's namespace
 * @param name         the pod name
 * @param uid          the pod UID
 * @param workload     the resolved owning workload (see {@link WorkloadRef})
 * @param phase        pod phase — {@code Pending} | {@code Running} |
 *                     {@code Succeeded} | {@code Failed} | {@code Unknown}
 * @param deleted      true when this snapshot represents the pod's deletion
 * @param preExisting  true when the pod was already present at watch startup
 *                     (delivered during the informer's initial list, before
 *                     sync) — such pods are logged but not investigated unless
 *                     {@code investigateOnStartup} is set
 * @param containers   per-container states
 * @param recentEvents recent {@code core/v1} Events referencing this pod, oldest first
 * @param observedAt   when this snapshot was assembled
 */
public record PodState(
        String namespace,
        String name,
        String uid,
        WorkloadRef workload,
        String phase,
        boolean deleted,
        boolean preExisting,
        List<ContainerState> containers,
        List<PodEvent> recentEvents,
        Instant observedAt) {

    public PodState {
        containers = List.copyOf(containers);
        recentEvents = List.copyOf(recentEvents);
    }
}
