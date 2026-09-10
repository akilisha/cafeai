package io.cafeai.sentinel;

import java.util.Objects;

/**
 * Configuration for a sentinel pipeline.
 *
 * <p>Phase 1 exposes only what {@link ClusterWatch} needs — how to reach the
 * cluster, the namespace to watch, and whether to act on failures that already
 * exist at startup. Later phases add {@code .system(...)},
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

    public ClusterConnection connection() {
        return connection;
    }

    public String namespace() {
        return namespace;
    }

    public boolean isInvestigateOnStartup() {
        return investigateOnStartup;
    }
}
