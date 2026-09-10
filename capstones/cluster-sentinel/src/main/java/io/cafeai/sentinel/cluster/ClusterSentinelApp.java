package io.cafeai.sentinel.cluster;

import io.cafeai.sentinel.ClusterConnection;
import io.cafeai.sentinel.ClusterWatch;
import io.cafeai.sentinel.SentinelConfig;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * Capstone entry point — a runnable {@link io.cafeai.sentinel} pipeline pointed
 * at a Kubernetes / OpenShift cluster.
 *
 * <p><strong>Phase 1 (ROADMAP-18):</strong> no AI. It starts a {@link ClusterWatch}
 * on one namespace and logs the correlated pod state on every change — the
 * walking skeleton that proves the watch and the owner-resolution / event
 * correlation work against a real cluster (minikube).
 *
 * <p>Namespace comes from {@code $SENTINEL_NAMESPACE}, then the first CLI arg,
 * then {@code default}.
 *
 * <p>Cluster connection is the ambient kubeconfig (or in-cluster config when
 * running as a pod) unless {@code $SENTINEL_API_SERVER} + {@code $SENTINEL_TOKEN}
 * are set, in which case it connects by bearer token to that API server —
 * {@code $SENTINEL_CA_CERT_FILE} supplies the CA, or {@code $SENTINEL_INSECURE=true}
 * skips TLS verification (dev clusters only).
 */
public final class ClusterSentinelApp {

    private static final Logger log = LoggerFactory.getLogger(ClusterSentinelApp.class);

    private ClusterSentinelApp() {
    }

    public static void main(String[] args) {
        String namespace = System.getenv().getOrDefault("SENTINEL_NAMESPACE",
                args.length > 0 ? args[0] : "default");

        SentinelConfig config = SentinelConfig.create()
                .namespace(namespace)
                .connection(connectionFromEnv());
        ClusterWatch watch = new ClusterWatch(config).onPodState(ClusterSentinelApp::logPodState);

        Runtime.getRuntime().addShutdownHook(new Thread(watch::close, "sentinel-shutdown"));

        watch.start();
        log.info("cluster-sentinel Phase 1 — watching '{}'. Ctrl-C to stop.", namespace);

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ClusterConnection connectionFromEnv() {
        String apiServer = System.getenv("SENTINEL_API_SERVER");
        String token = System.getenv("SENTINEL_TOKEN");
        if (apiServer == null || token == null) {
            return ClusterConnection.ambient();
        }
        ClusterConnection conn = ClusterConnection.token(apiServer, token);
        String caCertFile = System.getenv("SENTINEL_CA_CERT_FILE");
        if (caCertFile != null) {
            conn.caCertFile(caCertFile);
        }
        if (Boolean.parseBoolean(System.getenv("SENTINEL_INSECURE"))) {
            conn.trustCerts(true);
        }
        log.info("connecting by token to {}", apiServer);
        return conn;
    }

    private static void logPodState(PodState state) {
        String workload = state.workload().toString();

        if (state.deleted()) {
            log.info("{} :: pod {} deleted", workload, state.name());
            return;
        }

        String trouble = state.containers().stream()
                .filter(ContainerState::troubled)
                .map(ClusterSentinelApp::describe)
                .collect(Collectors.joining(", "));

        if (trouble.isEmpty()) {
            log.debug("{} :: pod {} phase={} — healthy", workload, state.name(), state.phase());
            return;
        }

        log.warn("{}{} :: pod {} phase={} :: {}",
                state.preExisting() ? "[pre-existing] " : "",
                workload, state.name(), state.phase(), trouble);

        for (PodEvent event : state.recentEvents()) {
            if (event.warning()) {
                log.warn("    {} {} x{} — {}", event.type(), event.reason(), event.count(), event.message());
            }
        }
    }

    private static String describe(ContainerState c) {
        StringBuilder sb = new StringBuilder(c.name()).append('(');
        sb.append(c.reason() != null ? c.reason() : c.state());
        if (c.lastTerminationReason() != null) {
            sb.append(" last=").append(c.lastTerminationReason());
        }
        if (c.exitCode() != null) {
            sb.append(" exit=").append(c.exitCode());
        }
        sb.append(" restarts=").append(c.restartCount()).append(')');
        return sb.toString();
    }
}
