# ADR-002: Everything is Middleware

**Status:** Accepted  
**Date:** March 2026

## Context

CafeAI needs a composability model. An AI request pipeline has many cross-cutting concerns:
auth, rate limiting, PII scrubbing, guardrails, RAG, LLM calls, observability, memory writes,
streaming. These concerns need to be independently developable, testable, and deployable.

## Decision

Every concern in CafeAI is expressed as **middleware** in an ordered pipeline.
The `Middleware` interface is the universal unit of composability.

## Rationale

The Express.js middleware pattern has been battle-tested for over a decade across millions of
production applications. It survives because:

1. **Mental model simplicity.** Every developer understands `(req, res, next)`.
2. **Composability is recursive.** Middleware can wrap middleware. Chains can contain chains.
3. **Order is explicit.** The sequence of `app.use()` calls IS the architecture. It's readable.
4. **Independent testability.** Every middleware is a function. Every function is unit-testable
   in isolation with no framework setup.
5. **It maps perfectly to AI pipelines.** An AI request lifecycle — auth, scrub, retrieve,
   generate, validate, log, cache — is a sequential pipeline of concerns. This is middleware.

The key insight: **AI pipelines were always middleware problems. CafeAI just names them that.**

## Consequences

- The `Middleware` interface is the most important abstraction in the codebase.
- All CafeAI primitives (`guard`, `rag`, `observe`, `memory`) resolve to middleware at runtime.
- The pipeline is the documentation. Reading `app.use()` calls top-to-bottom describes the
  entire request lifecycle.
- Teaching CafeAI means teaching the pipeline. The pipeline is the curriculum.
