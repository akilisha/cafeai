package io.cafeai.sentinel;

import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Watches one namespace and emits a correlated {@link PodState} on every pod
 * add / update / delete.
 *
 * <p>Two informers run: one over {@code Pod} objects (the source of truth for
 * container state — {@code OOMKilled} lives only here, never in an Event) and
 * one over {@code core/v1} Events (corroborating detail — {@code BackOff},
 * {@code Unhealthy}, {@code FailedScheduling}). Recent pod-scoped events are
 * attached to each snapshot.
 *
 * <p>This class does no triage and no AI by itself — it hands every snapshot to
 * the callback given to {@link #onPodState(Consumer)} and lets the caller decide
 * what to do with it. {@link IncidentTracker} is the callback that does the
 * classifying.
 *
 * <pre>{@code
 *   var config = SentinelConfig.create().namespace("payments");
 *   try (var watch = new ClusterWatch(config)) {
 *       watch.onPodState(state -> log.info("{}", state)).start();
 *       Thread.currentThread().join();
 *   }
 * }</pre>
 */
public final class ClusterWatch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClusterWatch.class);

    /** No periodic resync — the callback stream is real changes only, never a re-send of unchanged state. */
    private static final long NO_RESYNC = 0L;
    private static final int MAX_EVENTS_PER_POD = 12;
    private static final long SYNC_TIMEOUT_MILLIS = 30_000L;

    private final KubernetesClient client;
    private final boolean ownsClient;
    private final SentinelConfig config;
    private final OwnerResolver owners;

    /** podName -> recent pod-scoped events, oldest first, bounded. */
    private final Map<String, Deque<PodEvent>> eventsByPod = new ConcurrentHashMap<>();

    private Consumer<PodState> onPodState = _ -> { };
    private SharedIndexInformer<Pod> podInformer;
    private SharedIndexInformer<Event> eventInformer;
    private volatile boolean initialSyncComplete = false;

    /** Builds its own client from {@code config.connection()} (ambient kubeconfig by default). */
    public ClusterWatch(SentinelConfig config) {
        this(clientFor(config), true, config);
    }

    /** Uses a caller-supplied client (the caller closes it). */
    public ClusterWatch(KubernetesClient client, SentinelConfig config) {
        this(client, false, config);
    }

    private ClusterWatch(KubernetesClient client, boolean ownsClient, SentinelConfig config) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.config = Objects.requireNonNull(config, "config");
        this.owners = new OwnerResolver(client, config.namespace());
    }

    /** Registers the snapshot callback. Not thread-safe with {@link #start()}. */
    public ClusterWatch onPodState(Consumer<PodState> handler) {
        this.onPodState = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * The live Kubernetes client this watch is using — share it with
     * {@link io.cafeai.sentinel.investigate.KubeTools} rather than opening a
     * second connection.
     */
    public KubernetesClient client() {
        return client;
    }

    /** Starts both informers and blocks until their initial list has synced. */
    public void start() {
        String ns = config.namespace();
        log.info("watching namespace '{}' on {}", ns, client.getMasterUrl());

        eventInformer = client.v1().events().inNamespace(ns).inform(new ResourceEventHandler<>() {
            @Override public void onAdd(Event event) {
                recordEvent(event);
            }
            @Override public void onUpdate(Event oldEvent, Event event) {
                recordEvent(event);
            }
            @Override public void onDelete(Event event, boolean deletedFinalStateUnknown) {
                // recent-events window ages out on its own
            }
        }, NO_RESYNC);

        podInformer = client.pods().inNamespace(ns).inform(new ResourceEventHandler<>() {
            @Override public void onAdd(Pod pod) {
                emit(pod, false);
            }
            @Override public void onUpdate(Pod oldPod, Pod pod) {
                emit(pod, false);
            }
            @Override public void onDelete(Pod pod, boolean deletedFinalStateUnknown) {
                emit(pod, true);
            }
        }, NO_RESYNC);

        awaitInitialSync();
        initialSyncComplete = true;
        log.info("initial sync complete for namespace '{}'", ns);
    }

    @Override
    public void close() {
        if (podInformer != null) {
            podInformer.stop();
        }
        if (eventInformer != null) {
            eventInformer.stop();
        }
        if (ownsClient) {
            client.close();
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static KubernetesClient clientFor(SentinelConfig config) {
        Objects.requireNonNull(config, "config");
        Config fabric8 = config.connection().toFabric8Config();
        KubernetesClientBuilder builder = new KubernetesClientBuilder();
        if (fabric8 != null) {
            builder.withConfig(fabric8);
        }
        return builder.build();
    }

    private void awaitInitialSync() {
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (podInformer.hasSynced() && eventInformer.hasSynced()) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("informers did not sync within {}ms — continuing", SYNC_TIMEOUT_MILLIS);
    }

    private void recordEvent(Event event) {
        ObjectReference involved = event.getInvolvedObject();
        if (involved == null || !"Pod".equals(involved.getKind()) || involved.getName() == null) {
            return;
        }
        PodEvent pe = new PodEvent(
                event.getType(),
                event.getReason(),
                event.getMessage(),
                event.getCount() == null ? 1 : event.getCount(),
                parseInstant(event.getLastTimestamp()));

        Deque<PodEvent> window = eventsByPod.computeIfAbsent(
                involved.getName(), k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(pe);
            while (window.size() > MAX_EVENTS_PER_POD) {
                window.pollFirst();
            }
        }
    }

    private void emit(Pod pod, boolean deleted) {
        if (pod.getMetadata() == null) {
            return;
        }
        try {
            var meta = pod.getMetadata();
            // Resolve from the cache FIRST — on a delete the pod's ReplicaSet /
            // Deployment may already be gone, and a fresh lookup would then
            // mis-resolve a Deployment-owned pod to its bare ReplicaSet, so the
            // "pod gone" signal would never reach the incident keyed on the
            // Deployment. Evict only after we've resolved.
            WorkloadRef workload = owners.resolve(pod);
            if (deleted) {
                owners.evict(meta.getUid());
            }
            PodStatus status = pod.getStatus();

            List<PodEvent> events;
            Deque<PodEvent> window = eventsByPod.get(meta.getName());
            if (window == null) {
                events = List.of();
            } else {
                synchronized (window) {
                    events = List.copyOf(window);
                }
            }
            if (deleted) {
                eventsByPod.remove(meta.getName());
            }

            PodState state = new PodState(
                    meta.getNamespace(),
                    meta.getName(),
                    meta.getUid(),
                    workload,
                    status != null && status.getPhase() != null ? status.getPhase() : "Unknown",
                    deleted,
                    !deleted && !initialSyncComplete,
                    containerStates(status),
                    events,
                    Instant.now());

            onPodState.accept(state);
        } catch (RuntimeException ex) {
            log.warn("failed to build pod state for {}/{}",
                    pod.getMetadata().getNamespace(), pod.getMetadata().getName(), ex);
        }
    }

    static List<ContainerState> containerStates(PodStatus status) {
        if (status == null || status.getContainerStatuses() == null) {
            return List.of();
        }
        List<ContainerState> out = new ArrayList<>();
        for (ContainerStatus cs : status.getContainerStatuses()) {
            String phase = "unknown";
            String reason = null;
            String lastTerminationReason = null;
            Integer exitCode = null;
            String message = null;

            io.fabric8.kubernetes.api.model.ContainerState s = cs.getState();
            if (s != null) {
                if (s.getRunning() != null) {
                    phase = "running";
                } else if (s.getWaiting() != null) {
                    phase = "waiting";
                    reason = s.getWaiting().getReason();
                    message = s.getWaiting().getMessage();
                } else if (s.getTerminated() != null) {
                    phase = "terminated";
                    reason = s.getTerminated().getReason();
                    exitCode = s.getTerminated().getExitCode();
                    message = s.getTerminated().getMessage();
                }
            }
            // While a container sits in CrashLoopBackOff its current reason is the
            // back-off symptom; the real cause (OOMKilled, exit 137, ...) and the
            // exit code are in the previous terminated state.
            var last = cs.getLastState() != null ? cs.getLastState().getTerminated() : null;
            if (last != null) {
                if (exitCode == null) {
                    exitCode = last.getExitCode();
                }
                if (last.getReason() != null && !last.getReason().equals(reason)) {
                    lastTerminationReason = last.getReason();
                }
            }

            out.add(new ContainerState(
                    cs.getName(),
                    Boolean.TRUE.equals(cs.getReady()),
                    cs.getRestartCount() == null ? 0 : cs.getRestartCount(),
                    phase,
                    reason,
                    lastTerminationReason,
                    exitCode,
                    message));
        }
        return out;
    }

    private static Instant parseInstant(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(rfc3339);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
