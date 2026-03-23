# ADR-001: Helidon SE as the HTTP Runtime

**Status:** Accepted  
**Date:** March 2026

## Context

CafeAI needs an HTTP server foundation. The obvious Java choices are Spring Boot, Quarkus,
Micronaut, Vert.x, and Helidon. The choice must align with CafeAI's core philosophy:
understand the plumbing, compose explicitly, no magic.

## Decision

Use **Helidon SE**.

## Rationale

- **Explicit over implicit.** Helidon SE makes no decisions on your behalf. Every configuration
  is intentional. This forces understanding — which is the entire point of CafeAI.
- **Java 21+ first-class citizen.** The Helidon team adopted virtual threads, structured
  concurrency, and the reactive model as core primitives — not adapters bolted on after the fact.
- **Routing API is Express-adjacent.** Of all Java frameworks, Helidon SE's routing DSL is
  closest to Express.js in feel. CafeAI's Express-parity abstraction layer sits naturally on top.
- **OpenTelemetry built in.** Helidon SE ships with first-class OTel support — critical for
  CafeAI's observability story.
- **No annotation magic.** Helidon SE does not rely on reflection-heavy annotation processing.
  Everything is wired programmatically. Debuggable. Understandable.

## Rejected Alternatives

| Framework | Reason Rejected |
|---|---|
| Spring Boot | Abstracts too aggressively. Contradicts CafeAI's philosophy. |
| Quarkus | Annotation-driven. Build-time magic contradicts explainability goals. |
| Micronaut | Similar to Quarkus — compile-time DI obscures the plumbing. |
| Vert.x | Excellent but callback/reactive model creates friction for the Express-parity API. |

## Consequences

- CafeAI is opinionated about its runtime. This is intentional.
- Developers who want Spring Boot should use Spring AI. CafeAI is not for them.
