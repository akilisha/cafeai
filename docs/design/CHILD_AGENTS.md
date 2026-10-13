# Child agents: delegation with structured lifetimes

Status: design, not yet planned (no roadmap yet). Decided 2026-10-12 while designing
`cafeai-loop`, which will use it; this document is the framework's side of that decision.

## 1. The problem

An agent with several independent things to find out does them one after another, and every
intermediate step lands in its own context: a hundred lines of pod logs, a module's code, a list
of recent commits. The context fills with material the agent only needed long enough to draw a
conclusion from.

A **child agent** works on one of those things on its own, in a context of its own, and returns
only its **conclusion**, with pointers to its evidence. The parent's context holds conclusions,
not the work behind them. Several children can work at once.

## 2. Who needs it

- **`cafeai-sentinel`:** its `cluster-investigator` (an `app.agent(...)` with `KubeTools`)
  investigates an incident by reading pod logs, events and rollouts. Each is an independent line
  of inquiry: one child each, the investigator drawing the conclusion.
- **`cafeai-loop`:** `init` reads a codebase one child per package; `run` investigates a failure
  with children reading logs, recent commits and Sentinel's view (`cafeai-loop/DESIGN_SPEC.md`,
  Child agents).
- **Any CafeAI app** whose agent fans out: research across sources, checks against several
  systems.

## 3. Where it lives

Next to `app.agent(...)`, so every agent can delegate:

- **The API in `cafeai-core`,** beside `AgentConfig` (`io.cafeai.core.agents`).
- **The implementation in `cafeai-aiservices`,** where `app.agent(...)` agents are built.
- **`cafeai-agentic` may use it** (its parallel and supervisor workflows could run on the same
  executor), but doesn't own it: its LangChain4j module is experimental upstream, and the
  published core shouldn't depend on it.

`cafeai-loop` holds only its own policy (which commands spawn children, their tools, its limits,
how the developer sees them), not the mechanism.

## 4. What it guarantees

- **A child can't outlive its parent.** The parent opens a scope; leaving it waits for the
  children, or cancels them. Nothing keeps running in the background.
- **Cancellation and deadlines propagate.** Cancelling the parent cancels its children; a scope
  has a deadline, and children still running when it passes are cancelled.
- **The parent decides what a failure means:** stop the rest at the first failure, or collect
  every result, failures included.
- **Children run in their parent's request scope** (`RequestScope`): as the same caller
  (`Identity.current()`), under the same conversation and audit records, and their model usage is
  credited to the parent's route. Delegation never changes who is acting, and cost lands where the
  work was asked for.
- **A child returns a conclusion and pointers to evidence,** not its transcript; its memory is
  its own unless the parent shares some explicitly.
- **Children are observable:** each start, finish, failure and cancellation is published, with its
  parent, for tracing (`cafeai-observability`) and for anything showing the work to a person.

## 5. Shape (a sketch, to be designed)

```java
try (var children = app.children(Duration.ofSeconds(60))) {          // the scope, with a deadline
    var logs   = children.start("logs",   LogReader.class,  a -> a.read(pod));
    var events = children.start("events", EventReader.class, a -> a.summarise(namespace));
    children.join();                                                  // waits; cancels on deadline
    brief.add(logs.conclusion()).add(events.conclusion());
}                                                                     // no child outlives this
```

The names and types are placeholders; the guarantees in §4 are the design.

## 6. Built on final Java APIs, moving to `StructuredTaskScope`

Java's structured concurrency (`StructuredTaskScope`) is the natural fit, but it is still a
preview API: a sixth preview in JDK 26 (JEP 525), a seventh in JDK 27 (JEP 533); finalisation has
been proposed for JDK 28, not confirmed. CafeAI targets Java 23, where it is an older preview of a
different shape, and preview features tie compiled code to one exact JDK.

So it is built now on final APIs: virtual threads and an `ExecutorService` closed by
try-with-resources (the parent waits; no child outlives it), with cancellation and deadlines
handled explicitly, carried by `RequestScope.carrying(...)`. All of it sits behind the small API
of §5, so moving to `StructuredTaskScope` when it is final is a change in one place, and every app
gets it.

## 7. Open questions

- The default failure policy: stop at the first failure, or collect all?
- Whether children may nest (the framework allows it; `cafeai-loop` limits it to one level by
  default).
- How a child's evidence pointers are typed: free text, or a small structure (a file and line, a
  log query, a commit).
