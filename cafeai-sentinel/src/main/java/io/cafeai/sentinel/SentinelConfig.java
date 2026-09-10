package io.cafeai.sentinel;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for a sentinel pipeline.
 *
 * <p>Phases 1–2 expose how to reach the cluster, the namespace to watch, whether
 * to act on failures that already exist at startup, and how long an incident
 * stays open after its last error. Later phases add {@code .system(...)},
 * {@code .investigationPrompt(...)}, {@code .investigationModel(...)},
 * {@code .triageModel(...)}, {@code .guard(...)}, {@code .debounce(...)} and
 * {@code .sink(...)}.
 *
 * <pre>{@code
 *   var config = SentinelConfig.create().namespace("payments");
 *
 *   var remote = SentinelConfig.create()
 *       .namespace("payments")
 *       .connection(ClusterConnection.token(apiServerUrl, token));
 * }</pre>
 */
public final class SentinelConfig {

    private ClusterConnection connection = ClusterConnection.ambient();
    private String namespace = "default";
    private boolean investigateOnStartup = false;
    private Duration resolveAfter = Duration.ofMinutes(2);

    private SentinelConfig() {
    }

    public static SentinelConfig create() {
        return new SentinelConfig();
    }

    /**
     * How to reach the cluster API server. Defaults to
     * {@link ClusterConnection#ambient()} — the kubeconfig current-context, or
     * the in-cluster ServiceAccount token. Use {@link ClusterConnection#token}
     * for a sentinel that runs outside the cluster it watches.
     */
    public SentinelConfig connection(ClusterConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        return this;
    }

    /**
     * The single namespace to watch. Sentinel is deliberately single-namespace —
     * an intentional blast-radius limit, and the reason its RBAC is a namespaced
     * {@code Role} rather than a {@code ClusterRole}. Defaults to {@code default}.
     */
    public SentinelConfig namespace(String namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        return this;
    }

    /**
     * Investigate pods that are already failing when the watch connects. Off by
     * default: pre-existing failures are logged as {@code [pre-existing]} and
     * only transitions after startup are acted on.
     */
    public SentinelConfig investigateOnStartup(boolean investigateOnStartup) {
        this.investigateOnStartup = investigateOnStartup;
        return this;
    }

    /**
     * How long an incident stays open after its most recent {@code ERROR} signal
     * once all its affected pods have recovered or been deleted. The
     * {@link IncidentTracker} sweeper resolves the incident after this elapses.
     * Defaults to two minutes — long enough to ride out a crash-loop back-off
     * without flapping. Requires {@link IncidentTracker#start()}.
     */
    public SentinelConfig resolveAfter(Duration resolveAfter) {
        this.resolveAfter = Objects.requireNonNull(resolveAfter, "resolveAfter");
        return this;
    }

    public ClusterConnection connection() {
        return connection;
    }

    public Duration resolveAfter() {
        return resolveAfter;
    }

    public String namespace() {
        return namespace;
    }

    public boolean isInvestigateOnStartup() {
        return investigateOnStartup;
    }
}
