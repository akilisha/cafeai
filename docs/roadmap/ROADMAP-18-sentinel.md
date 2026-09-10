# ROADMAP-18 — cafeai-sentinel (AI cluster incident pipeline)

> A published module that watches a Kubernetes / OpenShift cluster, uses the
> cafeai-core AI primitives to triage pod events and investigate failures against
> the live cluster, and emits a **structured incident** to a pluggable sink.
> Its runnable companion is the capstone **cluster-sentinel**.
>
> **Status (2026-09):** 🟡 Draft — discovery. Nothing has crystallized. This doc
> is the thinking artifact; the module/capstone boundary and the pipeline shape
> are expected to shift as the walking skeleton (Phase 1) meets a real cluster.

---

## Why this exists

CafeAI demos so far are request/response webapps. `sentinel` is the honest test
of the **agentic** path: the "investigate the failure" step is genuine multi-step,
tool-using reasoning against a live system — not a wrapped single prompt. In one
artifact it exercises `cafeai-agents` + `@Tool`, `WsSession.streamTokens` (the
sink), `cafeai-guardrails` (PII in logs), `cafeai-observability`, `TokenBudget`,
and the long-running `app.helidon()` process.

It is a **pipeline, not a product**. It ends at "structured incident published."
No dashboard, no incident store, no remediation, no alert-rule engine.

---

## The split: module vs capstone

| | `cafeai-sentinel` (published) | `capstones/cluster-sentinel` (not published) |
|---|---|---|
| `ClusterWatch` — fabric8 informer watch, pod-state + event correlation | ✅ | |
| `KubeTools` — read-only `@Tool` bundle for cluster interrogation | ✅ | |
| Incident model + coalescing by owner reference | ✅ | |
| Two-tier triage → investigation orchestration | ✅ | |
| `IncidentSink` SPI + built-in sinks (log, SSE, webhook) | ✅ | |
| `SentinelConfig` fluent surface (`.system` / `.investigationPrompt` / `.guard` / `.model` / `.debounce` / `.sink`) | ✅ | |
| `main()`, wiring, the actual prompts | | ✅ |
| RBAC manifests (read-only Role + binding) | | ✅ |
| Demo scenarios (broken manifests under `demo/`) | | ✅ |
| Dashboard-facing HTTP/SSE routes | | ✅ |

The generic "event source → LLM interpret → sink" abstraction is **deliberately
not** the module boundary — that pattern is Reactive Streams / Camel / Kafka
Streams, and a thin wrapper over `Flow.Publisher` + `app.prompt(...)` adds
nothing. The module is drawn at the **Kubernetes** boundary; the one generic seam
is `IncidentSink`. If a second concrete source ever appears, extract the
commonality then, from two real cases.

---

## Architecture — two tiers

```
                 ┌─────────────── cafeai-sentinel ───────────────┐
 Pod informer ─▶ │  TRIAGE (cheap, per event)                    │
 Event informer  │    rules + optional small model               │
                 │    → benign | notable | error                 │
                 │            │                                   │
                 │            ▼  coalesce by ownerRef, debounce   │
                 │    ┌───────────────┐                           │
                 │    │   Incident    │  (open / updated)         │
                 │    └───────┬───────┘                           │
                 │            ▼                                   │
                 │  INVESTIGATION (agentic, per incident)         │
                 │    KubeTools: getPod, getPodLogs(previous),    │
                 │      listEvents, describeDeployment,           │
                 │      getReplicaSetHistory, getNodeConditions,  │
                 │      getResourceQuota  — all READ-ONLY         │
                 │            ▼                                   │
                 │    guardrails (PII redaction) + TokenBudget    │
                 │            ▼                                   │
                 │    structured incident (SchemaHintBuilder)     │
                 └────────────┬──────────────────────────────────┘
                              ▼
                       IncidentSink  ── SSE stream / webhook POST / custom
```

Triage is a stateless classifier; investigation is stateful (accumulate → debounce
→ run an agent when a threshold trips). Keeping them separate controls cost — one
bad deploy fans out dozens of `CrashLoopBackOff` events across replicas and must
produce **one** investigation.

### Incident schema (draft)

```
{ id, severity, namespace, workload, kind,
  firstSeen, lastSeen, eventCount,
  summary, likelyCause, likelyCauseCategory,
  evidence[]        // log excerpts, pod yaml fragments, events
  suggestedActions[],
  relatedObjects[] }
```

Tests assert on the **structured** fields (severity, `likelyCauseCategory`,
`relatedObjects`). The prose (`summary`, `likelyCause`) is non-deterministic and
is not asserted verbatim.

---

## Kubernetes client — fabric8

`io.fabric8:openshift-client` (superset of `kubernetes-client`). `OpenShiftClient
extends KubernetesClient`, so the OpenShift target is nearly free; its
`SharedInformer` API is the right primitive for correlated pod-state + resync
(raw event watch alone is a noisy, TTL'd firehose). `KubernetesMockServer` gives
fixture-driven unit tests without a cluster.

`KubernetesClient` is expected to leak through `ClusterWatch` / `KubeTools`, so
the fabric8 deps will be `api`.

---

## Portability contract — minikube ⇄ OpenShift

The reusability claim **is** "runs identically on both," so publishing is gated on
running the demo scenarios green against a real OpenShift dev/staging cluster
(Phase 6), not just minikube.

Known differences to design around:

- OpenShift layers **SCCs** over RBAC — the ServiceAccount needs the read Role
  explicitly bound; document the manifest.
- projects vs namespaces (mostly cosmetic), internal registry pull behavior.
- enterprise clusters usually have `LimitRange` / `ResourceQuota` pre-set —
  helps the "memory too small" scenario.
- cluster event policy may filter or rate-limit Events.

---

## Demo scenarios (capstone `demo/`)

Real local-cluster testing on minikube, then the same on OpenShift:

| Scenario | Trigger | Expected incident |
|---|---|---|
| Bad image | set image to a non-existent tag | `ImagePullBackOff`, workload not progressing, points at the image change |
| Scale to zero | `kubectl scale --replicas=0` | **notable, not error** — endpoints removed, service unreachable; must not cry wolf on an intentional op |
| Memory too small | tight `limits.memory` + fake load | exit 137 / `OOMKilled`, correlates the limit with the restart loop, suggests raising the limit or rolling back |
| Broken probe / missing ConfigMap | bad readiness probe or absent `ConfigMap` ref | pod never Ready / `CreateContainerConfigError`, names the missing dependency |

The scale-to-zero case is why triage must distinguish `error` from `notable state
change` and let config decide which get investigated vs merely published.

---

## Phases

| # | Phase | Gate |
|---|---|---|
| 0 | Skeleton — modules in the build, this doc | compiles |
| 1 | Walking skeleton — fabric8 informer watch on minikube → pod-state model → log sink. **No AI.** | prints correlated pod state on a live minikube |
| 2 | Triage tier — classify event/state-change; coalesce by ownerRef into `Incident` | one incident per broken deploy, not per event |
| 3 | Investigation tier — `KubeTools` read-only bundle + agentic investigation → structured incident | all 4 scenarios produce a coherent incident on minikube |
| 4 | Guardrails + budget — PII redaction on log excerpts, `TokenBudget` | secrets in logs never reach the prompt or the incident |
| 5 | Sinks — `IncidentSink` SPI, SSE + webhook sinks; capstone HTTP routes | a browser `EventSource` receives incidents |
| 6 | OpenShift validation — same 4 scenarios on a real dev/staging cluster | identical structured incidents |
| 7 | Publish `cafeai-sentinel` at 0.3.0 | on Maven Central |

---

## Open questions

- **Startup history** — on first connect, resync lists every currently-failing
  pod. Report pre-existing failures, or only transitions after start?
- **Incident identity** — key on the top controller in the ownerRef chain
  (Pod → ReplicaSet → Deployment)? What about bare pods, Jobs, StatefulSets,
  DaemonSets?
- **Triage model** — rules-only, or a small LLM? Does cafeai-core expose a
  "cheap model" slot distinct from the main provider?
- **Investigation trigger** — first error of a kind, or a threshold (N events in
  M minutes)?
- **Re-investigation** — when new evidence arrives on an open incident, update it
  or run a fresh investigation?
- **Sink delivery** — fire-and-forget SSE, or buffered / at-least-once for
  webhooks?
- **Scope** — single namespace, a namespace list, or whole-cluster? Drives the
  RBAC surface.
- **Relationship to `cafeai-observability`** — sentinel produces spans; should it
  also *consume* cluster telemetry as investigation evidence?

---

## Non-goals

Dashboard · incident history / persistence · auto-remediation · alert-rule engine
· multi-cluster · PagerDuty / Slack integrations (those are user-written
`IncidentSink` implementations, not module code).
