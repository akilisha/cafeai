package io.cafeai.sentinel.watch;

/**
 * The workload a pod belongs to — the resolved top of its owner-reference chain.
 *
 * <p>A pod owned by a {@code ReplicaSet} that is owned by a {@code Deployment}
 * resolves to {@code Deployment/<name>}. A pod owned directly by a
 * {@code StatefulSet} / {@code DaemonSet} / {@code Job} resolves to that
 * controller. A bare pod (no controller owner) resolves to {@code Pod/<name>}.
 *
 * <p>Incidents are keyed on this, so the replicas of one broken Deployment
 * coalesce into a single incident rather than one per pod.
 *
 * @param kind Kubernetes kind — {@code Deployment}, {@code StatefulSet},
 *             {@code DaemonSet}, {@code Job}, {@code ReplicaSet}, or {@code Pod}
 * @param name the object name
 */
public record WorkloadRef(String kind, String name) {

    @Override
    public String toString() {
        return kind + "/" + name;
    }
}
