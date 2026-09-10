package io.cafeai.sentinel;

import io.cafeai.core.ai.TokenBudget;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for a sentinel pipeline.
 *
 * <p>Phases 1–4 expose how to reach the cluster, the namespace to watch, whether
 * to act on failures that already exist at startup, how long an incident stays
 * open after its last error, whether to redact secrets from cluster text, and a
 * token budget for investigations. Later phases add {@code .system(...)},
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
    private Duration updateDebounce = Duration.ofSeconds(3);
    private boolean redact = true;
    private TokenBudget tokenBudget = TokenBudget.unlimited();

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

    /**
     * Rate-limit {@code UPDATED} incident events to at most one per this window
     * per incident — a flapping crash loop can otherwise produce an update every
     * second. {@code OPENED} / {@code INVESTIGATED} / {@code RESOLVED} are always
     * immediate; a trailing {@code UPDATED} with the final state is flushed on the
     * next sweep. {@link Duration#ZERO} disables it. Defaults to 3 seconds.
     */
    public SentinelConfig updateDebounce(Duration updateDebounce) {
        this.updateDebounce = Objects.requireNonNull(updateDebounce, "updateDebounce");
        return this;
    }

    /**
     * Redact credentials and PII from cluster text (container logs, pod env
     * values, event messages) before it reaches the LLM prompt, the incident, or
     * a log line. On by default — turning it off is only sensible for a private
     * cluster with no sensitive workloads. See
     * {@link io.cafeai.sentinel.investigate.Redactor}.
     */
    public SentinelConfig redact(boolean redact) {
        this.redact = redact;
        return this;
    }

    /**
     * A ceiling on tokens spent investigating, as a rolling one-minute budget.
     * When the estimated cost of the next investigation would exceed it, the
     * {@link IncidentTracker} defers that investigation and retries it on the
     * next sweep. Defaults to {@link TokenBudget#unlimited()}.
     */
    public SentinelConfig tokenBudget(TokenBudget tokenBudget) {
        this.tokenBudget = Objects.requireNonNull(tokenBudget, "tokenBudget");
        return this;
    }

    public ClusterConnection connection() {
        return connection;
    }

    public Duration resolveAfter() {
        return resolveAfter;
    }

    public Duration updateDebounce() {
        return updateDebounce;
    }

    public boolean isRedact() {
        return redact;
    }

    public TokenBudget tokenBudget() {
        return tokenBudget;
    }

    public String namespace() {
        return namespace;
    }

    public boolean isInvestigateOnStartup() {
        return investigateOnStartup;
    }
}
