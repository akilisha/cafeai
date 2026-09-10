# cluster-sentinel

Runnable companion to **`cafeai-sentinel`** — an AI cluster incident pipeline for
Kubernetes / OpenShift. See `docs/roadmap/ROADMAP-18-sentinel.md` for the design.

## Status — ROADMAP-18 Phase 1 (walking skeleton, no AI)

Starts a `ClusterWatch` on one namespace and logs the correlated pod state
(container states + owner workload + recent Warning events) on every change.
This proves the watch, owner resolution (`Pod → ReplicaSet → Deployment`), and
event correlation work against a real cluster. Triage, investigation, and the
incident sink land in Phases 2–5.

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
17:12:04 WARN  i.c.s.cluster.ClusterSentinelApp - Deployment/crashloop :: pod crashloop-7d9f-xr2k phase=Running :: app(CrashLoopBackOff exit=1 restarts=4)
17:12:04 WARN  i.c.s.cluster.ClusterSentinelApp -     Warning BackOff x5 — Back-off restarting failed container app in pod crashloop-7d9f-xr2k_demo
```

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
