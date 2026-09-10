package io.cafeai.sentinel;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.triage.TriageResult;
import io.cafeai.sentinel.triage.TriageRules;
import io.cafeai.sentinel.triage.Verdict;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The triage tier: consumes the correlated {@link PodState} stream from
 * {@link ClusterWatch}, classifies each snapshot with {@link TriageRules}, and
 * coalesces actionable signals into {@link Incident}s keyed on the resolved top
 * controller — so one broken Deployment is one incident, not one per replica per
 * event.
 *
 * <p>Phase 2: no AI. An incident carries triage output only (severity, reasons,
 * evidence lines). The agentic investigation that fills in cause and suggested
 * actions is ROADMAP-18 Phase 3.
 *
 * <p>Lifecycle events go to the handler registered with {@link #onIncident}:
 * {@code OPENED} on the first actionable signal for a workload, {@code UPDATED}
 * as more fold in, {@code RESOLVED} once every affected pod has recovered or been
 * deleted and {@link SentinelConfig#resolveAfter()} has elapsed since the last
 * error. Resolution needs the background sweeper — call {@link #start()}.
 *
 * <pre>{@code
 *   var config = SentinelConfig.create().namespace("payments");
 *   try (var tracker = new IncidentTracker(config).onIncident(sink).start();
 *        var watch = new ClusterWatch(config)) {
 *       watch.onPodState(tracker).start();
 *       Thread.currentThread().join();
 *   }
 * }</pre>
 */
public final class IncidentTracker implements Consumer<PodState>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IncidentTracker.class);

    private static final int MAX_EVIDENCE = 20;
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final TriageRules triage;
    private final boolean investigateOnStartup;
    private final Duration resolveAfter;
    private final Clock clock;

    /** workload key -> current incident. Guarded by {@code this}. */
    private final Map<WorkloadRef, Incident> incidents = new HashMap<>();

    private Consumer<IncidentEvent> onIncident = event -> { };
    private ScheduledExecutorService sweeper;

    public IncidentTracker(SentinelConfig config) {
        this(config, new TriageRules(), Clock.systemUTC());
    }

    IncidentTracker(SentinelConfig config, TriageRules triage, Clock clock) {
        Objects.requireNonNull(config, "config");
        this.triage = Objects.requireNonNull(triage, "triage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.investigateOnStartup = config.isInvestigateOnStartup();
        this.resolveAfter = config.resolveAfter();
    }

    /** Registers the incident lifecycle handler. Not thread-safe with a running watch. */
    public IncidentTracker onIncident(Consumer<IncidentEvent> handler) {
        this.onIncident = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** Starts the background sweeper that resolves quiet, pod-free incidents. */
    public IncidentTracker start() {
        if (sweeper != null) {
            return this;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sentinel-incident-sweeper");
            t.setDaemon(true);
            return t;
        });
        long period = SWEEP_INTERVAL.toSeconds();
        sweeper.scheduleAtFixedRate(this::sweep, period, period, TimeUnit.SECONDS);
        return this;
    }

    @Override
    public synchronized void accept(PodState pod) {
        WorkloadRef key = pod.workload();

        if (pod.deleted()) {
            dropPod(key, pod.name());
            return;
        }

        TriageResult result = triage.assess(pod);
        if (result.verdict() == Verdict.BENIGN) {
            dropPod(key, pod.name());
            return;
        }

        if (pod.preExisting() && !investigateOnStartup) {
            log.info("[pre-existing] {} {} — {} (not tracked)", key, pod.name(), result.detail());
            return;
        }

        Instant now = clock.instant();
        String evidence = evidenceLine(pod, result);
        Incident current = incidents.get(key);

        if (current == null) {
            Incident opened = Incident.open(newId(), pod.namespace(), key, now, result, pod.name(), evidence);
            incidents.put(key, opened);
            emit(IncidentEvent.Type.OPENED, opened);
        } else {
            Incident updated = current.fold(now, result, pod.name(), evidence, MAX_EVIDENCE);
            incidents.put(key, updated);
            emit(IncidentEvent.Type.UPDATED, updated);
        }
    }

    /** Current open incidents — a snapshot, newest state. */
    public synchronized List<Incident> openIncidents() {
        return List.copyOf(incidents.values());
    }

    @Override
    public void close() {
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void dropPod(WorkloadRef key, String podName) {
        Incident current = incidents.get(key);
        if (current != null) {
            incidents.put(key, current.withoutPod(podName));
        }
    }

    synchronized void sweep() {
        Instant now = clock.instant();
        incidents.values().removeIf(incident -> {
            boolean quiet = incident.lastErrorAt() == null
                    || Duration.between(incident.lastErrorAt(), now).compareTo(resolveAfter) >= 0;
            if (incident.affectedPods().isEmpty() && quiet) {
                emit(IncidentEvent.Type.RESOLVED, incident.resolved(now));
                return true;
            }
            return false;
        });
    }

    private void emit(IncidentEvent.Type type, Incident incident) {
        try {
            onIncident.accept(new IncidentEvent(type, incident));
        } catch (RuntimeException ex) {
            log.warn("incident handler threw for {} {}", type, incident.id(), ex);
        }
    }

    private static String newId() {
        return "inc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String evidenceLine(PodState pod, TriageResult result) {
        StringBuilder sb = new StringBuilder(pod.name()).append(": ");

        String containers = pod.containers().stream()
                .filter(ContainerState::troubled)
                .map(c -> c.name() + "(" + c.summary() + ")")
                .collect(Collectors.joining(", "));
        sb.append(containers.isEmpty() ? String.join(", ", result.reasons()) : containers);

        String warnings = pod.recentEvents().stream()
                .filter(PodEvent::warning)
                .map(e -> e.reason() + " x" + e.count())
                .distinct()
                .collect(Collectors.joining(", "));
        if (!warnings.isEmpty()) {
            sb.append(" [").append(warnings).append(']');
        }
        return sb.toString();
    }
}
