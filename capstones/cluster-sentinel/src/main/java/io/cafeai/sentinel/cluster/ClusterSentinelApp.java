package io.cafeai.sentinel.cluster;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.sentinel.ClusterConnection;
import io.cafeai.sentinel.ClusterWatch;
import io.cafeai.sentinel.IncidentTracker;
import io.cafeai.sentinel.Investigator;
import io.cafeai.sentinel.SentinelConfig;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.ClusterInvestigator;
import io.cafeai.sentinel.investigate.IncidentBrief;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.investigate.KubeTools;
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
 * <p><strong>Phase 3 (ROADMAP-18):</strong> the AI is in. {@link ClusterWatch}
 * feeds correlated pod snapshots to an {@link IncidentTracker} that triages and
 * coalesces them into incidents keyed on the owning workload; on each new
 * incident (and each new error reason) a {@link ClusterInvestigator} agent —
 * a CafeAI {@code app.agent(...)} with the read-only {@link KubeTools} bundle —
 * investigates the live cluster and attaches a structured {@link Investigation}.
 *
 * <p>Namespace comes from {@code $SENTINEL_NAMESPACE}, then the first CLI arg,
 * then {@code default}.
 *
 * <p>Cluster connection is the ambient kubeconfig (or in-cluster config) unless
 * {@code $SENTINEL_API_SERVER} + {@code $SENTINEL_TOKEN} are set for a bearer-token
 * connection ({@code $SENTINEL_CA_CERT_FILE} for the CA, {@code $SENTINEL_INSECURE=true}
 * to skip TLS verification on dev clusters).
 *
 * <p>The investigation model is {@code $SENTINEL_INVESTIGATION_MODEL} (an
 * Anthropic model id), else Claude if {@code $ANTHROPIC_API_KEY} is set, else
 * GPT-4o if {@code $OPENAI_API_KEY} is set.
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

        ClusterWatch watch = new ClusterWatch(config);

        CafeAI app = CafeAI.create();
        app.agent("cluster-investigator", ClusterInvestigator.class)
                .model(investigationProvider())
                .tool(new KubeTools(watch.client(), namespace));

        Investigator investigator = incident ->
                app.agent("cluster-investigator", ClusterInvestigator.class, null)
                        .investigate(IncidentBrief.of(incident));

        IncidentTracker tracker = new IncidentTracker(config)
                .onIncident(ClusterSentinelApp::logIncident)
                .investigator(investigator)
                .start();

        watch.onPodState(state -> {
            logPodState(state);
            tracker.accept(state);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            watch.close();
            tracker.close();
        }, "sentinel-shutdown"));

        watch.start();
        log.info("cluster-sentinel Phase 3 — triaging + investigating '{}'. Ctrl-C to stop.", namespace);

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

    private static AiProvider investigationProvider() {
        String model = System.getenv("SENTINEL_INVESTIGATION_MODEL");
        if (model != null && !model.isBlank()) {
            return Anthropic.of(model.trim());
        }
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            return Anthropic.claude35Sonnet();
        }
        if (System.getenv("OPENAI_API_KEY") != null) {
            return OpenAI.gpt4o();
        }
        log.warn("no ANTHROPIC_API_KEY or OPENAI_API_KEY set — investigations will fail until one is");
        return Anthropic.claude35Sonnet();
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
            case INVESTIGATED -> {
                Investigation inv = i.investigation();
                log.warn("✔ INVESTIGATED {} {} — [{}/{}] {}",
                        i.id(), i.workload(), inv.category(), inv.confidence(), inv.summary());
                log.info("             cause: {}", inv.likelyCause());
                for (String action : inv.suggestedActions()) {
                    log.info("             → {}", action);
                }
                if (!inv.relatedObjects().isEmpty()) {
                    log.info("             objects: {}", String.join(", ", inv.relatedObjects()));
                }
            }
            case RESOLVED -> log.info("○ RESOLVED {} {} — was [{}], {} signals over {}",
                    i.id(), i.workload(), i.severity(), i.signalCount(),
                    Duration.between(i.firstSeen(), i.lastSeen()));
        }
        if ((event.type() == IncidentEvent.Type.OPENED || event.type() == IncidentEvent.Type.UPDATED)
                && !i.evidence().isEmpty()) {
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
