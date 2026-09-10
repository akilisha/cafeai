/**
 * cafeai-sentinel — AI cluster incident pipeline.
 *
 * <p>Watches a Kubernetes / OpenShift cluster, triages pod events (cheap, per
 * event), runs an agentic investigation against the live cluster on confirmed
 * failures (read-only tools, per <em>incident</em>), and emits a structured
 * incident to a pluggable {@code IncidentSink}.
 *
 * <p>A pipeline, not a product: it ends at "incident published" — no dashboard,
 * no incident store, no remediation. The runnable companion is the
 * {@code cluster-sentinel} capstone.
 *
 * <p><strong>Status:</strong> ROADMAP-18 Phase 1 — {@link io.cafeai.sentinel.ClusterWatch}
 * watches one namespace and emits a correlated {@link io.cafeai.sentinel.watch.PodState}
 * on every pod change. No triage, no AI yet. See
 * {@code docs/roadmap/ROADMAP-18-sentinel.md}.
 */
package io.cafeai.sentinel;
