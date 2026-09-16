<!-- OUTLINE DRAFT — structure only, for review. Not for publishing as-is. -->

# cafeai-sentinel — When the LLM Watches Your Cluster

*Post 13 of 14 in the CafeAI series*

---

## Opening — not another chatbot

- Narrative hook: every prior capstone is a request/response shape — a
  question comes in, CafeAI's pipeline produces an answer. `cafeai-sentinel`
  is the first application built on CafeAI that isn't that shape at all: it
  watches a Kubernetes/OpenShift cluster continuously and decides, on its
  own, when something is broken enough to investigate.
- The framing that matters for this series specifically: nothing new was
  added to CafeAI core to make this possible. It's `app.agent()` + `@Tool` +
  guardrails + a sink — the exact primitives posts 2–8 already covered,
  aimed at a completely different domain. That's the thesis of this post.

## Architecture in one pass

- One diagram, ROADMAP-18's own framing: watch (fabric8 informers on
  Pod + Events) → cheap rules-only triage (deliberately no model here) →
  coalesce into an Incident keyed on the owning workload → on a confirmed
  failure, an agentic investigation → structured `Investigation` → pluggable
  `IncidentSink` (log / webhook / SSE).
- Call out explicitly: "a pipeline, not a product" — it ends at "incident
  published," no dashboard, no auto-remediation. Worth stating why that's a
  deliberate scope line, not a missing feature.

## Why rules first, model second

- `TriageRules` sees every pod event; the LLM sees almost none of them.
  Connects back to post 11's token-budget theme — an unattended pipeline
  that put a model in the hot path for every event would be both slow and
  expensive for zero benefit, since most events are routine.
- Scale-to-zero is `notable`, not `error` — one good concrete example of the
  triage rules encoding real operational judgment, not just event-type
  matching.

## `KubeTools` — read-only tools as a trust boundary

- The richest `@Tool` bundle in the whole codebase — a natural callback to
  post 7. `getPod`, `getPodLogs(previous=true)`, `listEvents`,
  `describeDeployment`, `getReplicaSetHistory`, `getNodeConditions`,
  `getResourceQuota` — seven tools, all read-only by construction, every one
  catches its own failure and returns a string instead of throwing (“a dead
  tool call should inform the agent, not abort the investigation”).
- `Redactor` scrubbing secrets/PII from every tool result before it reaches
  the prompt or the incident — reuses `cafeai-guardrails`' `PiiGuardRail`
  directly. Ties the guardrails-as-middleware lesson (post 8) into a place
  it wasn't originally designed for: tool *output*, not just user input.

## The validation story — this is the post's spine

- minikube first: 3 of 4 demo scenarios, and — told honestly, the series'
  established voice — three real bugs the live run found and fixed
  (resolve-before-evict ordering, an investigation retry cap, reason-family
  grouping so `Error → BackOff → CrashLoopBackOff` doesn't triple-trigger).
- The real test: standing up a personal OpenShift cluster (CRC on a Proxmox
  box) specifically because the module's own reusability claim — "runs
  identically on Kubernetes and OpenShift" — is unproven on minikube alone.
- The debugging arc, kept concrete and specific (this is what makes it a
  good post, not a changelog): direct connection to the cluster failing two
  different ways (idle-drop, connect-timeout) vs. an SSH tunnel proving
  rock-solid; then a second, independent failure — the design doc *claimed*
  a least-privilege RBAC manifest shipped and it never actually existed,
  caught only because a real personal-user token got a real `Forbidden`.
- The payoff: all 4 of 4 scenarios green on real OpenShift, including
  `missing-config` — the one scenario that had never run on *any* cluster
  before this. Include one real `INVESTIGATED` log line verbatim (the
  crashloop or missing-config one) as evidence, not paraphrase.

## What this proves

- Mirror post 12's "What X Proved" retrospective structure directly (it's
  the series' established closing-argument shape). The claim to land: CafeAI
  primitives are general enough to build a class of application the
  framework was never explicitly designed for, using zero
  Kubernetes-specific concepts anywhere in `cafeai-core`.
- Honest counter-note in the same breath, matching the series' credibility
  pattern (post 12 does this too): the RBAC gap is a real example of
  documentation drifting ahead of implementation — worth naming plainly
  rather than glossing over.

## Closing

- This closes the capstone arc (four request/response apps, then this — the
  one application in the series that isn't). Close by pointing back to
  `capstones/cluster-sentinel` and `docs/roadmap/ROADMAP-18-sentinel.md` for
  anyone who wants the full design detail, rather than a "next post" link.
- One more post follows this one, and it's a deliberate tonal shift: post 14
  is not a new application, it's the framework quietly fixing something
  none of the five capstones — this one included — ever exposed:
  `cafeai-sentinel`'s own webhook retry count was one of the three
  hardcoded, undocumented constants that motivated `cafeai-config`.
- Standard closing tagline.
