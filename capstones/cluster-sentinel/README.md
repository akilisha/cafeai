# cluster-sentinel

Runnable companion to **`cafeai-sentinel`** — an AI cluster incident pipeline for
Kubernetes / OpenShift. See `docs/roadmap/ROADMAP-18-sentinel.md` for the design.

## Status — ROADMAP-18 Phase 4 (triage + investigation + redaction/budget)

`ClusterWatch` feeds correlated pod snapshots to an `IncidentTracker`, which
triages each one with rules (`TriageRules` — no model) and coalesces failures
into incidents keyed on the owning workload: **one incident per broken
Deployment, not one per event per replica**. On each new incident (and each new
error reason) a `ClusterInvestigator` agent — a CafeAI `app.agent(...)` with the
read-only `KubeTools` bundle — investigates the live cluster and attaches a
structured `Investigation` (cause category, likely cause, suggested actions,
related objects). All cluster text (logs, env values, event messages) is
scrubbed of credentials and PII by `Redactor` before it reaches the prompt or
the incident. The pluggable SSE/webhook sink lands in Phase 5.

Needs an LLM key: `ANTHROPIC_API_KEY` (Claude) or `OPENAI_API_KEY` (GPT-4o).
Override the model with `SENTINEL_INVESTIGATION_MODEL=<anthropic-model-id>`, and
cap spend with `SENTINEL_TOKEN_BUDGET_PER_MIN=<n>` (deferred investigations
retry on the next sweep).

## Run it against minikube

```bash
minikube start
kubectl create namespace demo

# from the repo root — uses your current kubeconfig context
# (for a remote cluster / OpenShift, see "Connect to a remote cluster" below)
SENTINEL_NAMESPACE=demo ./gradlew :capstones:cluster-sentinel:run
```

In another terminal, break something and watch the log:

```bash
# crash loop — container exits 1 forever
kubectl -n demo apply -f capstones/cluster-sentinel/demo/crashloop.yaml

# image that will never pull
kubectl -n demo apply -f capstones/cluster-sentinel/demo/bad-image.yaml

# OOM under load — 16Mi limit, allocates more
kubectl -n demo apply -f capstones/cluster-sentinel/demo/oom.yaml
```

Expected output shape:

```
17:12:04 WARN  i.c.s.cluster.ClusterSentinelApp - ● OPENED   inc-3f2a9c1d [ERROR] Deployment/oom-demo — OOMKilled, CrashLoopBackOff (pods: oom-demo-7d9f-xr2k)
17:12:04 INFO  i.c.s.cluster.ClusterSentinelApp -              oom-demo-7d9f-xr2k: worker(CrashLoopBackOff last=OOMKilled exit=137 restarts=3)
17:12:31 WARN  i.c.s.cluster.ClusterSentinelApp - ✔ INVESTIGATED inc-3f2a9c1d Deployment/oom-demo — [RESOURCES/HIGH] worker is OOMKilled: the 16Mi memory limit is far below what it allocates under load
17:12:31 INFO  i.c.s.cluster.ClusterSentinelApp -              cause: spec.template.spec.containers[0].resources.limits.memory = 16Mi; the process RSS reaches ~90Mi before the kill
17:12:31 INFO  i.c.s.cluster.ClusterSentinelApp -              → raise limits.memory to at least 128Mi, or roll back to the previous image if the footprint regressed
17:15:41 INFO  i.c.s.cluster.ClusterSentinelApp - ○ RESOLVED inc-3f2a9c1d Deployment/oom-demo — was [ERROR], 6 signals over PT3M37S
```

Two replicas of one broken Deployment → **one** `inc-…`. Run with
`-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` (or edit `logback.xml`) to also
see every raw pod snapshot.

Clean up: `kubectl delete namespace demo`.

## Connect to a remote cluster (OpenShift / enterprise)

The default is your kubeconfig current-context. To point sentinel at a cluster it
does **not** have a kubeconfig for — the common case when it runs on a bastion,
a CI runner, or a separate cluster — give it an API server URL and a token:

```bash
export SENTINEL_API_SERVER=$(oc whoami --show-server)
export SENTINEL_TOKEN=$(oc whoami -t)
export SENTINEL_CA_CERT_FILE=/etc/sentinel/ca.crt   # or SENTINEL_INSECURE=true for dev
export SENTINEL_NAMESPACE=payments

./gradlew :capstones:cluster-sentinel:run
```

In code that maps to:

```java
SentinelConfig.create()
    .namespace("payments")
    .connection(ClusterConnection
        .token(apiServerUrl, token)
        .caCertFile("/etc/sentinel/ca.crt"));
```

`ClusterConnection` also has `.context(name)` (a non-default kubeconfig context)
and `.basicAuth(url, user, pass)` (legacy endpoints only — Kubernetes ≥ 1.19
rejects static-password auth).

## RBAC (Phase 5+)

Runs today with your kubeconfig user, or the token from
`SENTINEL_TOKEN`. The read-only `Role` + `RoleBinding` for running sentinel *as a
ServiceAccount in the cluster* ships with Phase 5 under `deploy/`.
