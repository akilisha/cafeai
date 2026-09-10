package io.cafeai.sentinel.cluster;

/**
 * Capstone entry point — a runnable {@link io.cafeai.sentinel} pipeline pointed
 * at a Kubernetes / OpenShift cluster.
 *
 * <p><strong>Status:</strong> ROADMAP-18 skeleton (Phase 0). The pipeline is not
 * implemented yet — Phase 1 wires a fabric8 informer watch on minikube.
 * See {@code docs/roadmap/ROADMAP-18-sentinel.md}.
 */
public final class ClusterSentinelApp {

    private ClusterSentinelApp() {
    }

    public static void main(String[] args) {
        System.out.println("""
            cluster-sentinel — ROADMAP-18 skeleton.
            The pipeline is not implemented yet (Phase 1: fabric8 informer watch on minikube).
            See docs/roadmap/ROADMAP-18-sentinel.md.""");
    }
}
