package io.cafeai.sentinel;

import io.cafeai.core.ai.TokenBudget;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.incident.IncidentStatus;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.investigate.Redactor;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
 * <p>Triage carries severity, reasons and evidence lines. When an
 * {@link Investigator} is registered with {@link #investigator(Investigator)},
 * the tracker runs it off the informer thread on incident open (and again when a
 * new error reason appears) and folds the structured {@link Investigation} back
 * in. Evidence lines and investigation results pass through a
 * {@link Redactor} (on unless {@link SentinelConfig#redact(boolean)} is off);
 * investigations are gated by {@link SentinelConfig#tokenBudget(TokenBudget)}
 * and a deferred one is retried on the next sweep.
 *
 * <p>Lifecycle events go to the handler registered with {@link #onIncident}:
 * {@code OPENED} on the first actionable signal for a workload, {@code UPDATED}
 * as more fold in, {@code INVESTIGATED} when an investigation completes,
 * {@code RESOLVED} once every affected pod has recovered or been deleted and
 * {@link SentinelConfig#resolveAfter()} has elapsed since the last error.
 * Resolution and investigation both need the background workers — call
 * {@link #start()}.
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
    private static final int INVESTIGATION_WORKERS = 2;
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    /** Rough cost of one agentic investigation (system + brief + tool round-trips + output). */
    static final long ESTIMATED_TOKENS_PER_INVESTIGATION = 20_000L;
    private static final long TOKEN_WINDOW_MILLIS = 60_000L;

    private final TriageRules triage;
    private final boolean investigateOnStartup;
    private final Duration resolveAfter;
    private final Redactor redactor;
    private final TokenBudget tokenBudget;
    private final Clock clock;

    /** workload key -> current incident. Guarded by {@code this}. */
    private final Map<WorkloadRef, Incident> incidents = new HashMap<>();
    /** incident ids with an investigation in flight. Guarded by {@code this}. */
    private final Set<String> investigating = new HashSet<>();

    /** Rolling token-budget window. Guarded by {@code this}. */
    private long windowTokens;
    private long windowStartMillis;

    private Consumer<IncidentEvent> onIncident = event -> { };
    private Investigator investigator;
    private ScheduledExecutorService sweeper;
    private ExecutorService investigations;

    public IncidentTracker(SentinelConfig config) {
        this(config, new TriageRules(), Clock.systemUTC());
    }

    IncidentTracker(SentinelConfig config, TriageRules triage, Clock clock) {
        Objects.requireNonNull(config, "config");
        this.triage = Objects.requireNonNull(triage, "triage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.investigateOnStartup = config.isInvestigateOnStartup();
        this.resolveAfter = config.resolveAfter();
        this.redactor = Redactor.of(config.isRedact());
        this.tokenBudget = config.tokenBudget();
        this.windowStartMillis = clock.millis();
    }

    /** Registers the incident lifecycle handler. Not thread-safe with a running watch. */
    public IncidentTracker onIncident(Consumer<IncidentEvent> handler) {
        this.onIncident = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Registers the agentic investigation. When set, {@link #start()} spins up a
     * small worker pool that runs it on incident open and on a new error reason.
     * Unset (the default) leaves the tracker triage-only.
     */
    public IncidentTracker investigator(Investigator investigator) {
        this.investigator = investigator;
        return this;
    }

    /** Starts the background sweeper and, if an investigator is set, the investigation workers. */
    public IncidentTracker start() {
        if (sweeper != null) {
            return this;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(daemonFactory("sentinel-incident-sweeper"));
        long period = SWEEP_INTERVAL.toSeconds();
        sweeper.scheduleAtFixedRate(this::sweep, period, period, TimeUnit.SECONDS);

        if (investigator != null) {
            investigations = Executors.newFixedThreadPool(
                    INVESTIGATION_WORKERS, daemonFactory("sentinel-investigator"));
        }
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
        String evidence = redactor.redact(evidenceLine(pod, result));
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
        maybeInvestigate(key);
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
        if (investigations != null) {
            investigations.shutdownNow();
            investigations = null;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void dropPod(WorkloadRef key, String podName) {
        Incident current = incidents.get(key);
        if (current != null) {
            incidents.put(key, current.withoutPod(podName));
        }
    }

    /** Submits an investigation for {@code key}'s incident if one is warranted. Call under {@code this}. */
    private void maybeInvestigate(WorkloadRef key) {
        if (investigator == null || investigations == null) {
            return;
        }
        Incident incident = incidents.get(key);
        if (incident == null
                || incident.status() != IncidentStatus.OPEN
                || investigating.contains(incident.id())
                || !incident.needsInvestigation()) {
            return;
        }
        if (!claimTokenBudget()) {
            log.info("token budget reached — deferring investigation of {} ({})",
                    incident.id(), incident.workload());
            return;
        }
        String id = incident.id();
        Set<String> covered = Set.copyOf(incident.reasons());
        Incident snapshot = incident;
        investigating.add(id);
        investigations.execute(() -> runInvestigation(key, id, covered, snapshot));
    }

    /** Reserves one investigation's estimated tokens against the rolling window, or refuses. Call under {@code this}. */
    private boolean claimTokenBudget() {
        if (tokenBudget.isUnlimited()) {
            return true;
        }
        long now = clock.millis();
        if (now - windowStartMillis >= TOKEN_WINDOW_MILLIS) {
            windowStartMillis = now;
            windowTokens = 0;
        }
        if (windowTokens + ESTIMATED_TOKENS_PER_INVESTIGATION > tokenBudget.tokensPerMinute()) {
            return false;
        }
        windowTokens += ESTIMATED_TOKENS_PER_INVESTIGATION;
        return true;
    }

    private void runInvestigation(WorkloadRef key, String id, Set<String> covered, Incident snapshot) {
        Investigation result;
        try {
            result = investigator.investigate(snapshot);
        } catch (RuntimeException ex) {
            log.warn("investigation {} for {} failed: {}", id, key, ex.toString());
            synchronized (this) {
                investigating.remove(id);
            }
            return;
        }
        synchronized (this) {
            investigating.remove(id);
            Incident current = incidents.get(key);
            if (result == null || current == null || !current.id().equals(id)) {
                return;
            }
            Incident enriched = current.withInvestigation(redactor.redact(result), covered);
            incidents.put(key, enriched);
            emit(IncidentEvent.Type.INVESTIGATED, enriched);
            maybeInvestigate(key);
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
        // pick up any investigations that were deferred by the token budget
        for (WorkloadRef key : new ArrayList<>(incidents.keySet())) {
            maybeInvestigate(key);
        }
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

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
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
