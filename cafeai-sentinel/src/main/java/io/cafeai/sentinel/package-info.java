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
 * <p><strong>Status:</strong> ROADMAP-18 Phase 3. {@link io.cafeai.sentinel.ClusterWatch}
 * watches one namespace and emits a correlated {@link io.cafeai.sentinel.watch.PodState};
 * {@link io.cafeai.sentinel.IncidentTracker} triages each snapshot with
 * {@link io.cafeai.sentinel.triage.TriageRules} (rules only) and coalesces
 * signals into {@link io.cafeai.sentinel.incident.Incident}s keyed on the top
 * controller; when an {@link io.cafeai.sentinel.Investigator} is registered it
 * runs {@link io.cafeai.sentinel.investigate.ClusterInvestigator} against the
 * live cluster via the read-only {@link io.cafeai.sentinel.investigate.KubeTools}
 * and folds a structured {@link io.cafeai.sentinel.investigate.Investigation}
 * into the incident. The pluggable {@code IncidentSink} is Phase 5. See
 * {@code docs/roadmap/ROADMAP-18-sentinel.md}.
 */
package io.cafeai.sentinel;
