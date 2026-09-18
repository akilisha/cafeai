# ADR-003: Tiered Memory Architecture

**Status:** Accepted — Amended September 2026  
**Date:** March 2026  
**Amended:** September 2026 (rungs 3 and 5 were never built and are removed — see "Amendment" below)

## Context

LLM-powered applications require conversation context memory. The naive default is to reach
immediately for Redis. CafeAI challenges this assumption.

## Decision

CafeAI implements a **tiered memory hierarchy** with six rungs, each a deliberate choice
driven by actual scale and complexity requirements — not assumed infrastructure defaults.

```
Rung 1 → In-JVM HashMap         (prototype, zero deps)
Rung 2 → Java FFM MemorySegment (SSD-backed, production single-node)
Rung 3 → Chronicle Map          (off-heap, high-throughput single-node)
Rung 4 → Redis via Lettuce      (distributed, multi-instance)
Rung 5 → Memcached              (distributed, simpler alternative)
Rung 6 → Hybrid (warm + cold)   (tiered promotion/demotion)
```

## Rationale

### The Core Insight

Most applications do not need Redis for conversation context. An SSD in a modern server has
random read latencies of 50–100 microseconds. Redis over a local network adds 200–500
microseconds of overhead. For single-node deployments, the SSD tier is *faster*.

### Why FFM MemorySegment for Rung 2

The Java FFM API's `MemorySegment` provides:
- **Off-heap storage** — zero GC pressure regardless of context size
- **Memory-mapped files** — the OS page cache manages hot/warm data automatically
- **Crash recovery** — memory-mapped files survive JVM restarts. Context is durable by default.
- **API coherence** — CafeAI already uses the FFM API for native ML bindings. Same skillset.

### Why Chronicle Map for Rung 3

Chronicle Map is specifically designed for off-heap, persisted key-value storage at high
throughput. It is the right tool for this exact problem and avoids reinventing serialization
and file management on top of raw `MemorySegment`.

### Redis as Escape Valve, Not Default

Redis is excellent. But it introduces:
- Network latency
- Infrastructure dependency
- Operational overhead (cluster, auth, persistence config)
- Cost

These are reasonable tradeoffs when you genuinely need distributed state. They are
unreasonable defaults for single-node deployments. CafeAI's architecture makes the
tradeoff explicit and deliberate.

## Amendment — September 2026: rungs 3 and 5 were never built

The ladder above names six rungs. Two of them never existed:

- **Rung 3, Chronicle Map.** `MemoryStrategy.chronicle()` was a stub that threw
  `UnsupportedOperationException("not yet implemented")`, while `cafeai-memory`
  shipped the Chronicle Map jar (an early-access build) on every consumer's runtime
  classpath "for a future impl" — and nothing imported it. The README advertised
  `app.memory(MemoryStrategy.chronicle())` as a working option.
- **Rung 5, Memcached.** There was no code at all — only a dependency-version pin
  that nothing used, and README lines describing it as a distributed tier.

**Decision:** remove both. Delete `MemoryStrategy.chronicle()`, the Chronicle Map
dependency and its version pin, the unused Memcached pin, and the docs that
advertised either. The decision this ADR exists to record is unchanged: start on
the heap, use SSD-backed FFM for single-node production, and treat Redis as the
escape valve. What is actually built is rung 1 (`inMemory()`), rung 2 (`mapped()`),
rung 4 (`redis(...)`) and the hybrid tier (`hybrid()`). Rung numbers elsewhere
are historical and were left as they were, so the ladder skips 3.

If a single-node off-heap tier beyond FFM is ever needed, it should be proposed
afresh — with an implementation — rather than reserved as a stub.

## Consequences

- The `MemoryStrategy` factory API is the primary interface for this decision.
- Teams adopting CafeAI start at Rung 1 and graduate when the problem demands it.
- This architecture produces a compelling blog post: *"Context Memory Without the Cloud Tax."*
- Serialization format for off-heap storage must be chosen (FlatBuffers, Chronicle Map native,
  or custom `MemoryLayout`) — see ADR-004.
