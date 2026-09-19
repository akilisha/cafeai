package io.cafeai.sentinel;

import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.config.AppConfig;
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
 * the tracker runs it off the informer thread on incident open and again when a
 * new failure <em>family</em> appears (see {@link TriageRules#family} — a crash
 * loop churning {@code Error} → {@code CrashLoopBackOff} → {@code PodFailed} is
 * one investigation, not three), and folds the structured {@link Investigation}
 * back in. Investigation gives up on an incident after
 * {@link #MAX_INVESTIGATION_FAILURES} consecutive failures until a new family
 * appears. Evidence lines and investigation results pass through a
 * {@link Redactor} (on unless {@link SentinelConfig#redact(boolean)} is off);
 * investigations are gated by {@link SentinelConfig#tokenBudget(TokenBudget)}
 * and a deferred one is retried on the next sweep. {@code UPDATED} events are
 * rate-limited by {@link SentinelConfig#updateDebounce(Duration)}.
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

    /** Most pieces of evidence kept on one incident. */
    public static final ConfigKey<Integer> EVIDENCE_MAX = ConfigKey.of(
        "cafeai.sentinel.evidence.max", Integer.class, 20,
        "Most pieces of evidence (pod observations) kept on one incident.");

    /** Investigations that may run at the same time. */
    public static final ConfigKey<Integer> INVESTIGATION_WORKERS = ConfigKey.of(
        "cafeai.sentinel.investigation.workers", Integer.class, 2,
        "How many incident investigations may run at once.");

    /** How often the tracker checks whether an incident has gone quiet and can be resolved. */
    public static final ConfigKey<Duration> SWEEP_INTERVAL = ConfigKey.of(
        "cafeai.sentinel.sweep.interval", Duration.class, Duration.ofSeconds(30),
        "How often open incidents are checked for having gone quiet.");

    /** Rough cost of one agentic investigation (system + brief + tool round-trips + output). */
    static final long ESTIMATED_TOKENS_PER_INVESTIGATION = 20_000L;

    /** What one investigation is assumed to cost when it is counted against the token budget. */
    public static final ConfigKey<Long> INVESTIGATION_TOKENS = ConfigKey.of(
        "cafeai.sentinel.investigation.tokens", Long.class, ESTIMATED_TOKENS_PER_INVESTIGATION,
        "Tokens one incident investigation is assumed to use, counted against the token budget per minute.");

    private static final long TOKEN_WINDOW_MILLIS = 60_000L;

    /** Consecutive failures after which an incident's auto-investigation gives up (until a new failure family). */
    static final int MAX_INVESTIGATION_FAILURES = 3;

    /** Consecutive failures after which an incident's automatic investigation gives up. */
    public static final ConfigKey<Integer> INVESTIGATION_FAILURES = ConfigKey.of(
        "cafeai.sentinel.investigation.failures", Integer.class, MAX_INVESTIGATION_FAILURES,
        "Consecutive failed investigations after which an incident is left alone until a new kind of failure appears.");

    private final int maxEvidence;
    private final int investigationWorkers;
    private final Duration sweepInterval;
    private final long investigationTokens;
    private final int maxInvestigationFailures;

    private final TriageRules triage;
    private final boolean investigateOnStartup;
    private final Duration resolveAfter;
    private final Duration updateDebounce;
    private final Redactor redactor;
    private final TokenBudget tokenBudget;
    private final Clock clock;

    /** workload key -> current incident. Guarded by {@code this}. */
    private final Map<WorkloadRef, Incident> incidents = new HashMap<>();
    /** incident ids with an investigation in flight. Guarded by {@code this}. */
    private final Set<String> investigating = new HashSet<>();
    /** incident id -> consecutive investigation failures. Guarded by {@code this}. */
    private final Map<String, Integer> investigationFailures = new HashMap<>();
    /** incident id -> clock millis of its last emitted event, for the UPDATED debounce. Guarded by {@code this}. */
    private final Map<String, Long> lastEmitMillis = new HashMap<>();
    /** incident ids whose latest folded state has not yet been emitted. Guarded by {@code this}. */
    private final Set<String> pendingUpdate = new HashSet<>();

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
        this(config, triage, clock, AppConfig.load());
    }

    IncidentTracker(SentinelConfig config, TriageRules triage, Clock clock, AppConfig settings) {
        Objects.requireNonNull(config, "config");
        this.triage = Objects.requireNonNull(triage, "triage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.investigateOnStartup = config.isInvestigateOnStartup();
        this.resolveAfter = config.resolveAfter();
        this.updateDebounce = config.updateDebounce();
        this.redactor = Redactor.of(config.isRedact());
        this.tokenBudget = config.tokenBudget();
        this.maxEvidence              = settings.positive(EVIDENCE_MAX);
        this.investigationWorkers     = settings.positive(INVESTIGATION_WORKERS);
        this.sweepInterval            = settings.positiveDuration(SWEEP_INTERVAL);
        this.investigationTokens      = settings.positiveLong(INVESTIGATION_TOKENS);
        this.maxInvestigationFailures = settings.positive(INVESTIGATION_FAILURES);
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
        long period = Math.max(1, sweepInterval.toSeconds());
        sweeper.scheduleAtFixedRate(this::sweep, period, period, TimeUnit.SECONDS);

        if (!updateDebounce.isZero()) {
            long flushMs = Math.max(500L, updateDebounce.toMillis());
            sweeper.scheduleAtFixedRate(this::flushPendingUpdatesLocked, flushMs, flushMs, TimeUnit.MILLISECONDS);
        }

        if (investigator != null) {
            investigations = Executors.newFixedThreadPool(
                    investigationWorkers, daemonFactory("sentinel-investigator"));
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
            if (current.introducesNewReason(result)) {
                investigationFailures.remove(current.id()); // a new failure family — worth another attempt
            }
            Incident updated = current.fold(now, result, pod.name(), evidence, maxEvidence);
            incidents.put(key, updated);
            emitUpdate(updated);
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
                || investigationFailures.getOrDefault(incident.id(), 0) >= maxInvestigationFailures
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
        if (windowTokens + investigationTokens > tokenBudget.tokensPerMinute()) {
            return false;
        }
        windowTokens += investigationTokens;
        return true;
    }

    private void runInvestigation(WorkloadRef key, String id, Set<String> covered, Incident snapshot) {
        Investigation result;
        try {
            result = investigator.investigate(snapshot);
        } catch (RuntimeException ex) {
            synchronized (this) {
                investigating.remove(id);
                int failures = investigationFailures.merge(id, 1, Integer::sum);
                if (failures >= maxInvestigationFailures) {
                    log.warn("giving up investigating {} for {} after {} failures — last: {}",
                            id, key, failures, ex.toString());
                } else {
                    log.warn("investigation {} for {} failed ({}/{}): {}",
                            id, key, failures, maxInvestigationFailures, ex.toString());
                }
            }
            return;
        }
        synchronized (this) {
            investigating.remove(id);
            investigationFailures.remove(id);
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
                investigating.remove(incident.id());
                investigationFailures.remove(incident.id());
                lastEmitMillis.remove(incident.id());
                return true;
            }
            return false;
        });
        flushPendingUpdates();
        // pick up any investigations that were deferred by the token budget
        for (WorkloadRef key : new ArrayList<>(incidents.keySet())) {
            maybeInvestigate(key);
        }
    }

    private void emit(IncidentEvent.Type type, Incident incident) {
        lastEmitMillis.put(incident.id(), clock.millis());
        pendingUpdate.remove(incident.id());
        try {
            onIncident.accept(new IncidentEvent(type, incident));
        } catch (RuntimeException ex) {
            log.warn("incident handler threw for {} {}", type, incident.id(), ex);
        }
    }

    /** Emit an UPDATED now if the debounce window has elapsed, else mark it pending for the next flush. */
    private void emitUpdate(Incident incident) {
        Long last = lastEmitMillis.get(incident.id());
        if (last == null || clock.millis() - last >= updateDebounce.toMillis()) {
            emit(IncidentEvent.Type.UPDATED, incident);
        } else {
            pendingUpdate.add(incident.id());
        }
    }

    synchronized void flushPendingUpdatesLocked() {
        flushPendingUpdates();
    }

    /** Emit the current state of any incident whose folded updates are still pending past the debounce window. */
    private void flushPendingUpdates() {
        if (pendingUpdate.isEmpty()) {
            return;
        }
        long now = clock.millis();
        for (String id : new ArrayList<>(pendingUpdate)) {
            Incident incident = incidents.values().stream()
                    .filter(i -> i.id().equals(id)).findFirst().orElse(null);
            Long last = lastEmitMillis.get(id);
            if (incident == null) {
                pendingUpdate.remove(id);
            } else if (last == null || now - last >= updateDebounce.toMillis()) {
                emit(IncidentEvent.Type.UPDATED, incident);
            }
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
