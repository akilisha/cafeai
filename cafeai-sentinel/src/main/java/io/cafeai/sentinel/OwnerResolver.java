package io.cafeai.sentinel;

import io.cafeai.sentinel.watch.WorkloadRef;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a pod to its owning {@link WorkloadRef} — the top of the
 * controller owner-reference chain — with one hop up from {@code ReplicaSet} to
 * {@code Deployment}. Results are cached per pod UID; the cache is evicted when
 * {@link ClusterWatch} sees the pod deleted.
 *
 * <p>The {@code ReplicaSet -> Deployment} hop is a single namespaced GET,
 * cached, so a crash-looping Deployment costs one lookup, not one per event.
 */
final class OwnerResolver {

    private final KubernetesClient client;
    private final String namespace;
    private final Map<String, WorkloadRef> byUid = new ConcurrentHashMap<>();

    OwnerResolver(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    WorkloadRef resolve(Pod pod) {
        String uid = pod.getMetadata().getUid();
        WorkloadRef cached = byUid.get(uid);
        if (cached != null) {
            return cached;
        }
        WorkloadRef resolved = compute(pod);
        byUid.put(uid, resolved);
        return resolved;
    }

    void evict(String uid) {
        if (uid != null) {
            byUid.remove(uid);
        }
    }

    private WorkloadRef compute(Pod pod) {
        OwnerReference owner = controllerOf(pod.getMetadata().getOwnerReferences());
        if (owner == null) {
            return new WorkloadRef("Pod", pod.getMetadata().getName());
        }
        if ("ReplicaSet".equals(owner.getKind())) {
            ReplicaSet rs = client.apps().replicaSets()
                    .inNamespace(namespace).withName(owner.getName()).get();
            if (rs != null) {
                OwnerReference rsOwner = controllerOf(rs.getMetadata().getOwnerReferences());
                if (rsOwner != null && "Deployment".equals(rsOwner.getKind())) {
                    return new WorkloadRef("Deployment", rsOwner.getName());
                }
            }
            return new WorkloadRef("ReplicaSet", owner.getName());
        }
        // StatefulSet, DaemonSet, Job, CronJob-owned Job, ... — the controller
        // is already the top for our purposes.
        return new WorkloadRef(owner.getKind(), owner.getName());
    }

    private static OwnerReference controllerOf(List<OwnerReference> refs) {
        if (refs == null) {
            return null;
        }
        return refs.stream()
                .filter(r -> Boolean.TRUE.equals(r.getController()))
                .findFirst()
                .orElse(null);
    }
}
