# cafeai-sentinel — When the LLM Watches Your Cluster

*Post 13 of 14 in the CafeAI series*

---

Every previous application in this series has the same shape: a question comes
in over HTTP, CafeAI's pipeline produces an answer, the request ends.
`cafeai-sentinel` doesn't have a request. It watches a Kubernetes/OpenShift
namespace continuously and decides, on its own, when something is broken
enough to investigate. No prior post in this series covers that shape, and
nothing new was added to `cafeai-core` to build it — it's `app.agent()`,
`@Tool`, guardrails, and a sink, the same primitives posts 2 through 8 already
covered, pointed at a domain the framework was never designed for.

## Two tiers, on purpose

A pod failing is cheap and constant; deciding it's worth an LLM's attention is
not. `cafeai-sentinel` splits the pipeline into two tiers for exactly that
reason. `ClusterWatch` runs a fabric8 `SharedInformer` over both Pod objects
and Events — deliberately both, not just Events, because `OOMKilled` is never
an Event. It lives only in `pod.status.containerStatuses[].lastState.terminated`
(exit code 137). A pipeline that watched Events alone would be structurally
blind to the single most common way a container dies.

`TriageRules` classifies every snapshot with a lookup table — container state
first, then pod phase, then Event reason — into `benign`, `notable`, or
`error`. No model call. `CrashLoopBackOff` / `ImagePullBackOff` /
`CreateContainerConfigError`, `OOMKilled`, a non-zero non-`SIGTERM` exit, and
Warning events like `FailedScheduling` or `FailedMount` all resolve to
`error` from a table lookup. A routine `kubectl delete` — graceful `SIGTERM`,
exit 143 — resolves to nothing at all. This matters more than it sounds: an
unattended pipeline that put a model in the hot path for every pod transition
would be slow, expensive, and would flag routine rollouts as incidents.

Only a confirmed `error`, coalesced by the pod's resolved top controller (so
three crashing replicas of one Deployment become one incident, not three),
triggers the second tier: `ClusterInvestigator`, an ordinary `app.agent(...)`
bound to `KubeTools` — seven read-only methods (`getPod`, `getPodLogs`
with a `previous`-container flag, `listEvents`, `describeDeployment`,
`getReplicaSetHistory`, `getNodeConditions`, `getResourceQuota`), each
catching its own failure and returning a string instead of throwing, because
a dead tool call should inform the agent, not abort the investigation. Before
anything from those calls reaches a prompt, an incident, or a log line, it
passes through `Redactor` — a nine-pattern secret scrubber (bearer/basic auth
headers, URL-embedded credentials, `password=`/`token=`-style assignments,
AWS keys, JWTs, PEM private-key blocks) chained into `cafeai-guardrails`'
`PiiGuardRail` for emails, phone numbers, SSNs, and IPv4 addresses. That last
one catches internal pod and node IPs too, which is an accepted false
positive for the compliance guarantee it buys.

## The bugs the first live run actually found

`cafeai-sentinel` had 40-some unit tests, all green, running against
fabric8's `KubernetesMockServer`, before it ever touched a real cluster. The
first run against live minikube — four demo scenarios (bad image,
scale-to-zero, OOM, missing config), a real Claude Sonnet 4.5 doing the
investigating — got 3 of 4 scenarios right immediately, correctly
categorizing `[IMAGE/HIGH]` for the bad image, `[RESOURCES/HIGH]` for the
OOM, and `[APPLICATION/HIGH]` for the crashloop (having correctly read the
*previous* container's logs to find the actual panic, not just the empty
logs of the container currently restarting). It also found three real bugs
that no mock server had ever been in a position to find:

**Incidents that never resolved.** `ClusterWatch` evicted its owner-resolution
cache *before* resolving a deleted pod — but by the time a pod is deleted,
its ReplicaSet is usually gone too, so a Deployment-owned pod mis-resolved to
its own bare, now-vanished `ReplicaSet/…`. The "this pod is gone" signal
never reached the incident, which was keyed on `Deployment/…`. The incident
sat OPEN forever, and the sweeper quietly retried a doomed investigation
every 30 seconds. The fix was one reordering: resolve from the cache first,
evict it after.

**Investigations that never gave up.** A bad API key or a dead model looped
on every sweep, forever, burning a call each time for no possible different
outcome. `IncidentTracker.MAX_INVESTIGATION_FAILURES` (3) now stops retrying
after three consecutive failures on the same incident, until a genuinely new
failure family shows up.

**One crash loop, four investigations.** A real crash loop doesn't sit still
— it walks `Error` → `BackOff` → `CrashLoopBackOff` → `PodFailed`, and the
tracker was treating each reason as new, re-running the agent every time.
`TriageRules.family` now groups related reasons (crash-loop reasons together,
OOM separately, image-pull separately, and so on), and the re-investigation
trigger compares by family instead of by exact reason string. One crash loop,
one investigation.

A fourth, smaller fix rode along: a flapping pod was emitting `UPDATED`
events roughly once a second. `SentinelConfig.updateDebounce` (default 3s)
rate-limits those; `OPENED`, `INVESTIGATED`, and `RESOLVED` stay immediate,
and a trailing `UPDATED` flushes on the debounce timer. All four fixes are
covered by name in the test suite —
`investigationGivesUpAfterRepeatedFailures`,
`relatedCrashReasonsAreOneInvestigation`, `updatedEventsAreDebounced` — and
all three original bugs were re-confirmed live afterward: incidents actually
resolving once the deployment was deleted, the retry cap actually logging
"giving up … after 3 failures," family grouping actually cutting one
crashloop scenario from four investigations down to one.

A real log excerpt from that corrected run, one broken Deployment start to
finish:

```
17:12:04 WARN  i.c.sentinel.sink.LogSink - ● OPENED   inc-3f2a9c1d [ERROR] Deployment/oom-demo — OOMKilled, CrashLoopBackOff (pods: oom-demo-7d9f-xr2k)
17:12:04 INFO  i.c.sentinel.sink.LogSink -              oom-demo-7d9f-xr2k: worker(CrashLoopBackOff last=OOMKilled exit=137 restarts=3)
17:12:31 WARN  i.c.sentinel.sink.LogSink - ✔ INVESTIGATED inc-3f2a9c1d Deployment/oom-demo — [RESOURCES/HIGH] worker is OOMKilled: the 16Mi memory limit is far below what it allocates under load
17:12:31 INFO  i.c.sentinel.sink.LogSink -              cause: spec.template.spec.containers[0].resources.limits.memory = 16Mi; the process RSS reaches ~90Mi before the kill
17:12:31 INFO  i.c.sentinel.sink.LogSink -              → raise limits.memory to at least 128Mi, or roll back to the previous image if the footprint regressed
17:15:41 INFO  i.c.sentinel.sink.LogSink - ○ RESOLVED inc-3f2a9c1d Deployment/oom-demo — was [ERROR], 6 signals over PT3M37S
```

## The reusability claim, and the gap it exposed

`cafeai-sentinel`'s stated design contract is that it runs identically on
Kubernetes and OpenShift — not "mostly," not "with a few tweaks." That claim
is only a claim until it's run somewhere that isn't minikube, which is
exactly why publishing the module was explicitly gated on a real OpenShift
run, not the minikube result above.

That run found a gap minikube structurally could not have found, because it
was a gap in *documentation*, not code: the design doc and the capstone
README both stated that a least-privilege `ServiceAccount` manifest had
shipped back in Phase 5. It hadn't. Nobody had needed one yet, because every
prior run used a personal kubeconfig user with broad access. On the real
cluster, that personal token turned out to lack `list`/`watch` on `events` —
which surfaced the gap — and even if it hadn't, a personal user token is the
wrong long-term credential shape for an unattended pipeline regardless.

The fix is `deploy/rbac.yaml`: a dedicated `cluster-sentinel` `ServiceAccount`
with a namespaced `Role` scoped to exactly what `ClusterWatch`/`KubeTools`
read —

```yaml
rules:
  - apiGroups: [""]
    resources: ["pods", "events", "resourcequotas", "limitranges"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list"]
```

plus one narrow `ClusterRole` for `nodes` — the single call in `KubeTools`
(`getNodeConditions`) that isn't namespaced, and the only reason sentinel
needs any cluster-scoped permission at all. Minted with `oc create token
cluster-sentinel -n <namespace> --duration=24h`, that token is also exactly
what `ClusterConnection.token(apiServerUrl, oauthToken)` was built for: a
sentinel process running *outside* the cluster it watches — a bastion host,
a CI runner, a separate management cluster reaching into a workload
cluster — which is the normal enterprise topology, not the exception.

With that in place, all four demo scenarios — including `missing-config`
(`CreateContainerConfigError` from a pod referencing a `ConfigMap` that
doesn't exist), which had never been run against any cluster before this —
came back green on real OpenShift, matching the minikube results structurally
if not byte-for-byte in prose.

## What this proves, and the honest gap in it

The reusability claim held: the actual watch/triage/investigate/redact
pipeline needed zero code changes to move from minikube to OpenShift. Every
difference was operational — RBAC, a token instead of a kubeconfig — never a
line of `cafeai-sentinel` itself. That's a real validation of the framework's
Kubernetes-abstraction boundary (fabric8's `KubernetesClient`, which
`OpenShiftClient` extends without needing its own code path).

The gap is worth stating as plainly as the win: a design document said
something had shipped, and for one full phase, nobody checked. Unit tests
against a mock server can't catch that kind of drift — they only assert on
what the code does, never on whether the document describing it is still
true. Only a real cluster, with real RBAC, run by someone other than the
person who wrote the code, found it.

## Closing

`cafeai-sentinel` shipped at 0.3.2, 58 tests green. It ends where it was
designed to end — at "structured incident published," with no dashboard, no
incident store, and no auto-remediation, because that boundary is a decision,
not a missing feature. For the full design record, including the phases,
decisions, and non-goals not covered here, see
`docs/roadmap/ROADMAP-18-sentinel.md`; to run it yourself, `capstones/cluster-sentinel`.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
