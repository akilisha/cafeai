# ROADMAP-18 — cafeai-sentinel (AI cluster incident pipeline)

> A published module that watches a Kubernetes / OpenShift cluster, uses the
> cafeai-core AI primitives to triage pod events and investigate failures against
> the live cluster, and emits a **structured incident** to a pluggable sink.
> Its runnable companion is the capstone **cluster-sentinel**.
>
> **Status (2026-09-10):** 🟢 Phases 0–5 built, unit-tested, and **run
> end-to-end against live minikube** with Claude Sonnet 4.5 — 3 of the 4 demo
> scenarios (bad-image, oom, crashloop) produced correct structured incidents;
> the run surfaced and fixed 3 bugs (see "Fixes from the 2026-09-10 minikube
> run"). On `main`, unpushed, unpublished. Remaining: run `missing-config`,
> Phase 6 (OpenShift), Phase 7 (publish at 0.3.0).

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
| `SentinelConfig` fluent surface (`.connection` / `.namespace` / `.system` / `.investigationPrompt` / `.guard` / `.investigationModel` / `.triageModel` / `.debounce` / `.sink`) | ✅ | |
| `ClusterConnection` — ambient / named context / token+URL / basic-auth, with CA + TLS knobs | ✅ | |
| `TriageRules` / `Verdict` / `TriageResult` — rules-only classifier | ✅ | |
| `IncidentTracker` + `Incident` / `IncidentEvent` — coalesce by workload, best-effort resolve, async investigation orchestration | ✅ | |
| `KubeTools` — read-only `@Tool` bundle (getPod, getPodLogs, listEvents, describeDeployment, getReplicaSetHistory, getNodeConditions, getResourceQuota) | ✅ | |
| `ClusterInvestigator` (agent interface) + `Investigation` / `CauseCategory` / `Confidence` + `IncidentBrief` + `Investigator` SAM | ✅ | |
| `Redactor` — secrets/PII scrub (bearer/basic, URL creds, `key=value` secrets, AWS keys, JWTs, PEM, + `cafeai-guardrails` PII) on every KubeTools output, evidence line and investigation result | ✅ | |
| `IncidentSink` SPI + `LogSink` / `WebhookSink` / `SsePublisher` + `IncidentJson` (stable wire shape) | ✅ | |
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

**Two watch streams, correlated — not Events alone.** The informer watches Pod
*objects* (status transitions) *and* Events. This matters: `OOMKilled` is never an
Event — it lives only in `pod.status.containerStatuses[].lastState.terminated`
(exit 137). The "memory too small" scenario is invisible without the Pod-object
watch. ("Pod events only" in the scope decision means we don't yet watch
StatefulSet / DaemonSet / Job *objects* — it does not mean Events-only.)

**Triage is rules-first.** Container state first (it holds `OOMKilled` / exit
codes that never appear as Events), then pod phase, then Events. `CrashLoopBackOff`
/ `ImagePullBackOff` / `CreateContainerConfigError` waiting reasons, `OOMKilled` /
`Error` terminations, a non-zero non-SIGTERM exit, a `Failed` phase, and Warning
events `BackOff` / `Failed` / `FailedScheduling` / `FailedMount` / `Evicted` (plus
`Unhealthy` once its count ≥ 3) → `error`. `Preempted` / `NodeNotReady` /
`TaintManagerEviction` → `notable`. Everything else → `benign`. A lookup table
gets ~90% of triage with no model call; an LLM classifier for the ambiguous
remainder is a later fallback, not a Phase-2 dependency.

`Killing` and a graceful SIGTERM (exit 143) are **not** signals — a routine
rollout or `kubectl delete` must produce nothing. Detecting an *intentional*
scale-to-zero as `notable` (service has no endpoints) needs an Endpoints watch
and lands with a later phase; Phase 2 simply does not cry wolf on it.

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
| 0 | ✅ Skeleton — modules in the build, this doc | compiles |
| 1 | ✅ Walking skeleton — fabric8 informer watch → pod-state model → log sink. **No AI.** | ✅ correlated pod state on live minikube |
| 2 | ✅ Triage tier — **rules** on container state / phase / Events; coalesce into `Incident` keyed on the resolved top controller; best-effort resolve after a cooldown | ✅ one incident per broken deploy on live minikube (crashloop 2 replicas → 1) |
| 3 | ✅ Investigation tier — `KubeTools` read-only bundle + `ClusterInvestigator` agent, run async on open / new failure family → structured `Investigation` folded into the incident | ✅ **live minikube run 2026-09-10, Claude Sonnet 4.5** — bad-image → `[IMAGE/HIGH]`, oom → `[RESOURCES/HIGH]`, crashloop → `[APPLICATION/HIGH]` (read the previous container's logs), each with correct `relatedObjects` and actionable fixes. `missing-config` manifest added; not yet run. |
| 4 | ✅ Guardrails + budget — `Redactor` on every KubeTools output / evidence line / investigation result; `SentinelConfig.redact()` + `.tokenBudget()`; deferred investigations retried on sweep | *secret shapes + PII scrub unit-tested; `redact` on by default* |
| 5 | ✅ Sinks — `IncidentSink` + `LogSink` / `WebhookSink` / `SsePublisher` + `IncidentJson`; capstone HTTP routes (`/`, `/health`, `/incidents`, `/incidents/stream`) + a live dashboard | ✅ SSE + `/incidents` served real incidents from live minikube |
| 5b | ✅ Live-run fixes — see "Fixes from the 2026-09-10 minikube run" below | ✅ all three validated live |
| 6 | OpenShift validation — same 4 scenarios on a real dev/staging cluster | identical structured incidents |
| 7 | Publish `cafeai-sentinel` at 0.3.0 | on Maven Central |

---

## Fixes from the 2026-09-10 minikube run

The first live run surfaced three problems, all now fixed and unit-tested:

1. **Incidents never resolved after their pods vanished.** `ClusterWatch.emit`
   evicted the owner-resolution cache *before* resolving a deleted pod — but the
   pod's ReplicaSet is also gone by then, so a Deployment-owned pod mis-resolved
   to its bare `ReplicaSet/…`, and the "pod gone" signal never reached the
   incident keyed on `Deployment/…`. The incident stayed OPEN forever and the
   sweeper retried a doomed investigation every 30s. Fix: resolve from cache
   first, evict after.
2. **Investigation retried forever on a permanent failure.** A bad API key / dead
   model looped every sweep. Fix: `IncidentTracker.MAX_INVESTIGATION_FAILURES`
   (3) consecutive failures → give up until a new failure family appears.
3. **Reason churn re-triggered investigation.** A crash loop walks `Error` →
   `BackOff` → `CrashLoopBackOff` → `PodFailed`; each counted as a new reason and
   re-ran the agent. Fix: `TriageRules.family` groups related reasons; the
   re-investigation trigger and `Incident.introducesNewReason` compare by family.
   (`OOMKilled` then `CrashLoopBackOff` are still two families — arguably right.)
4. **`UPDATED` was far too chatty** (~1/sec on a flapping pod). Fix:
   `SentinelConfig.updateDebounce` (default 3s) rate-limits `UPDATED` emits;
   `OPENED` / `INVESTIGATED` / `RESOLVED` stay immediate, a trailing `UPDATED`
   flushes on the debounce timer.

Out of scope but noted: `cafeai-core`'s `Anthropic.of("claude-sonnet-4-5")` returns a
retired model id (`claude-3-5-sonnet-20241022` → `ModelNotFoundException`); the
capstone works around it with `$SENTINEL_INVESTIGATION_MODEL`.

---

## Decisions (2026-09)

- **Scope — single namespace.** `SentinelConfig.namespace(String)`. Intentional
  blast-radius limit; RBAC is a namespaced `Role`, never a `ClusterRole`.
- **Connection — ambient by default, explicit token for the real case.**
  `SentinelConfig.connection(ClusterConnection)`. `ambient()` (kubeconfig
  current-context / in-cluster SA token) is right for a laptop or an in-cluster
  pod, but the enterprise deployment is a sentinel *outside* the cluster it
  watches, so `token(apiServerUrl, oauthToken)` is a first-class mode — no
  kubeconfig consulted, with `caCertFile` / `caCertData` / `trustCerts` for TLS.
  `context(name)` and `basicAuth(...)` round it out; basic-auth is kept only for
  legacy / proxied endpoints (Kubernetes ≥ 1.19 rejects static passwords).
- **Event source — Pod events only, for now.** Every object kind emits Events;
  the mechanics (watch → triage → coalesce → investigate) don't change, only the
  event shape does. StatefulSets / DaemonSets / Jobs / bare pods come on a
  need basis, not up front.
- **Incident identity — resolved top controller.** Walk the ownerRef chain
  (Pod → ReplicaSet → Deployment), cache it, key the incident on the top
  controller so N crashing replicas coalesce into one incident. Bare pod (no
  ownerRef) → key on the pod itself.
- **Triage model — none in v1.** Rules on the Event `reason` / `type` (see
  Architecture). `SentinelConfig.triageModel(AiProvider)` is a later escape hatch;
  default would be `Jlama` (a 1–3B local model is fine for *classification* — it
  is not asked to reason about the cluster).
- **Investigation model — the app's registered provider; frontier for the demo.**
  Multi-step tool-use reasoning about a live cluster needs a capable model —
  Claude / GPT-4o for the demo. `Jlama` / `Ollama` stay documented as the
  "no data leaves your infra" option with a larger local model. `SentinelConfig`
  does not reuse `ModelRouter`'s length heuristic — sentinel knows which task is
  which and picks explicitly.
- **Incident resolution — best-effort, cooldown-gated (Phase 2).** An incident
  resolves when every affected pod has recovered (a `BENIGN` snapshot) or been
  deleted **and** `SentinelConfig.resolveAfter(Duration)` (default 2 min) has
  elapsed since the last `ERROR`. A background sweeper in `IncidentTracker`
  (`.start()`) does this; without it incidents only ever open/update. A timer is
  the honest tool here — with `NO_RESYNC` there is no steady event stream to
  hang resolution off. `NOTABLE`-only incidents skip the cooldown.
- **Investigation trigger — first error of a family.** `IncidentTracker` runs the
  `Investigator` off the informer thread (a 2-worker pool) when an incident opens
  and again whenever `Incident.needsInvestigation()` — a failure *family*
  (`TriageRules.family`) not covered by the last run — is true. One investigation
  per incident id in flight at a time; a failure is logged and isolated (incident
  stays OPEN), and after `MAX_INVESTIGATION_FAILURES` (3) consecutive failures the
  incident is left alone until a new family appears. The result folds in as an
  `INVESTIGATED` event.
- **Investigation wiring — capstone owns the model + prompt.** The module ships
  the `ClusterInvestigator` interface (with a default `@SystemMessage`), the
  `KubeTools` bundle, and the `Investigation` schema. The capstone binds it as a
  CafeAI `app.agent("cluster-investigator", …).tool(kubeTools).model(…)` and hands
  the tracker `inc -> agent.investigate(IncidentBrief.of(inc))`. A non-CafeAI
  caller uses `Investigator.using(chatModel, kubeTools)`.
- **KubeTools — strictly read-only, single client.** Every method is a GET/LIST;
  each catches its own failure and returns a readable string rather than aborting
  the agent loop. It shares `ClusterWatch.client()` — no second connection. Node
  reads are the one cluster-scoped call (capstone RBAC needs a `ClusterRole` for
  `nodes`).
- **Redaction — at the boundary, on by default (Phase 4).** `Redactor` scrubs
  bearer/basic auth, `scheme://user:pass@` URL creds, `key=value` secrets (key
  contains password/token/secret/api-key/…), AWS keys, JWTs / SA tokens, PEM
  private keys, then `cafeai-guardrails` `PiiGuardRail.scrub` for email / phone /
  SSN / card / IPv4. Applied to every `KubeTools` return, every incident evidence
  line, and every free-text field of an `Investigation` before it is folded in —
  so a secret in a pod's logs never reaches the prompt, the incident, or a log.
  IPv4 redaction catches internal pod/node IPs too; accepted for the compliance
  guarantee. `SentinelConfig.redact(false)` opts out.
- **Token budget — estimate-based, deferring (Phase 4).** `SentinelConfig
  .tokenBudget(TokenBudget)` (default unlimited). Each investigation is charged a
  flat `ESTIMATED_TOKENS_PER_INVESTIGATION` (20k) against a rolling one-minute
  window before it runs; over budget, it is deferred and the sweep retries it.
  Not per-token-exact — `app.agent(...)` does not surface aggregate usage — but
  it bounds runaway spend, which is the point.
- **Re-investigation — update, don't re-run, unless a new error reason appears.**
  New evidence on an open incident bumps `lastSeen` / `eventCount` and appends to
  `evidence[]`. A genuinely *new* error reason on the same incident (was
  `CrashLoopBackOff`, now also `FailedScheduling`) re-runs the investigation.
  Incident auto-resolves after a healthy cooldown → emits a `resolved` update.
- **Sink delivery — fire-and-forget (built, Phase 5).** `IncidentSink` is a
  `Consumer<IncidentEvent>`; `IncidentSink.of(...)` fans out with per-sink
  try/catch. `publish` must not block (it runs on the emit path) — `WebhookSink`
  hands off to its own thread (2 attempts, no queue), `SsePublisher` `offer`s
  without blocking and drops for a slow client. `SsePublisher.stream()` goes
  straight to CafeAI `res.stream(...)`; no replay, a late client misses history.
  `IncidentJson` is a hand-built map, not reflection, so the wire shape is stable
  (`workload` as `Kind/name`, ISO timestamps, tracker internals omitted).
- **cafeai-observability — produce spans, do not consume telemetry.** Sentinel
  traces its own investigation as a span. Ingesting cluster metrics/traces as
  investigation evidence is an overreach — out of scope through Phase 7. Evidence
  sources stay: pod spec/status, container logs, Events, owner objects.
- **Startup history — skip pre-existing failures.** On first connect the informer
  LISTs every currently-failing pod; log those as "pre-existing — not
  investigated" and only act on transitions *after* start. `.investigateOnStartup()`
  opt-in for the "tell me what's already on fire" case. Keeps demos clean — start
  sentinel, *then* break something.

---

## Non-goals

Dashboard · incident history / persistence · auto-remediation · alert-rule engine
· multi-cluster · PagerDuty / Slack integrations (those are user-written
`IncidentSink` implementations, not module code).
