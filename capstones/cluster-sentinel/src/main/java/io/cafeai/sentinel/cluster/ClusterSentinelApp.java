package io.cafeai.sentinel.cluster;

import io.cafeai.sentinel.ClusterConnection;
import io.cafeai.sentinel.ClusterWatch;
import io.cafeai.sentinel.IncidentTracker;
import io.cafeai.sentinel.SentinelConfig;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Capstone entry point — a runnable {@link io.cafeai.sentinel} pipeline pointed
 * at a Kubernetes / OpenShift cluster.
 *
 * <p><strong>Phase 2 (ROADMAP-18):</strong> still no AI. {@link ClusterWatch}
 * feeds correlated pod snapshots to an {@link IncidentTracker}, which triages
 * them with rules and coalesces failures into incidents keyed on the owning
 * workload — one incident per broken Deployment, not one per event. Incidents
 * are logged; the agentic investigation lands in Phase 3. The raw per-pod
 * snapshot is still logged at {@code DEBUG}.
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

        IncidentTracker tracker = new IncidentTracker(config)
                .onIncident(ClusterSentinelApp::logIncident)
                .start();

        ClusterWatch watch = new ClusterWatch(config).onPodState(state -> {
            logPodState(state);
            tracker.accept(state);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            watch.close();
            tracker.close();
        }, "sentinel-shutdown"));

        watch.start();
        log.info("cluster-sentinel Phase 2 — triaging '{}'. Ctrl-C to stop.", namespace);

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

    private static void logIncident(IncidentEvent event) {
        Incident i = event.incident();
        String reasons = String.join(", ", i.reasons());
        String pods = String.join(", ", i.affectedPods());

        switch (event.type()) {
            case OPENED -> log.warn("● OPENED   {} [{}] {} — {} (pods: {})",
                    i.id(), i.severity(), i.workload(), reasons, pods);
            case UPDATED -> log.info("● updated  {} [{}] {} — {} signals; reasons: {}; pods: {}",
                    i.id(), i.severity(), i.workload(), i.signalCount(), reasons, pods);
            case RESOLVED -> log.info("○ RESOLVED {} {} — was [{}], {} signals over {}",
                    i.id(), i.workload(), i.severity(), i.signalCount(),
                    Duration.between(i.firstSeen(), i.lastSeen()));
        }
        if (event.type() != IncidentEvent.Type.RESOLVED && !i.evidence().isEmpty()) {
            log.info("             {}", i.evidence().get(i.evidence().size() - 1));
        }
    }

    private static void logPodState(PodState state) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String workload = state.workload().toString();
        if (state.deleted()) {
            log.debug("{} :: pod {} deleted", workload, state.name());
            return;
        }
        String trouble = state.containers().stream()
                .filter(ContainerState::troubled)
                .map(c -> c.name() + "(" + c.summary() + ")")
                .collect(Collectors.joining(", "));
        log.debug("{}{} :: pod {} phase={} :: {}",
                state.preExisting() ? "[pre-existing] " : "",
                workload, state.name(), state.phase(),
                trouble.isEmpty() ? "healthy" : trouble);
    }
}
