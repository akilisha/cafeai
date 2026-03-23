# ADR-003: Tiered Memory Architecture

**Status:** Accepted  
**Date:** March 2026

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

## Consequences

- The `MemoryStrategy` factory API is the primary interface for this decision.
- Teams adopting CafeAI start at Rung 1 and graduate when the problem demands it.
- This architecture produces a compelling blog post: *"Context Memory Without the Cloud Tax."*
- Serialization format for off-heap storage must be chosen (FlatBuffers, Chronicle Map native,
  or custom `MemoryLayout`) — see ADR-004.
