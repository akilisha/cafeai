# ADR-004: FFM API for Both Native Bindings and Off-Heap Memory

**Status:** Accepted  
**Date:** March 2026

## Context

CafeAI has two requirements that independently justify the Java FFM API:
1. Native ML library bindings (ONNX, llama.cpp) without JNI ceremony
2. Off-heap, SSD-backed conversation memory via `MemorySegment`

## Decision

Use the **Java FFM API** (`java.lang.foreign`) as the single native/off-heap primitive
across both use cases. Do not introduce separate abstractions or separate libraries for each.

## Rationale

The FFM API unifies what previously required two separate approaches (JNI for native, NIO
`MappedByteBuffer` for memory-mapped files) under one coherent API surface. Using it for
both purposes in CafeAI has several benefits:

1. **Skill coherence.** A developer who learns FFM for native ML bindings already knows
   the API when they encounter it in the memory tier. The learning transfers.

2. **API surface reduction.** One API to understand deeply rather than two APIs to
   understand shallowly.

3. **Demonstration value.** CafeAI becomes a compelling showcase of FFM's breadth.
   Most FFM examples demo one use case. CafeAI demos two load-bearing use cases in
   a single production application.

4. **`MemorySegment` is superior to `MappedByteBuffer`.** The modern FFM `MemorySegment`
   API handles off-heap memory with explicit lifetime management, better safety guarantees,
   and direct integration with the `MemoryLayout` API for structured data.

## FFM Usage Map

| Use Case | FFM Capability | Module |
|---|---|---|
| ONNX local embedding | `Linker`, `FunctionDescriptor`, `SymbolLookup` | `cafeai-rag` |
| llama.cpp local inference | `Linker`, `FunctionDescriptor`, `SymbolLookup` | `cafeai-tools` |
| Session context storage | `MemorySegment`, `MemoryLayout`, `Arena` | `cafeai-memory` |

## Consequences

- Java 21+ is a hard requirement (FFM is stable from JDK 21).
- `--enable-preview` flag required for compilation in early 21.x versions.
- Off-heap memory requires explicit Arena lifecycle management — this must be handled
  carefully in the `cafeai-memory` module to avoid memory leaks.
- This is a conference talk in itself: *"Two Problems, One API: Java FFM in Production."*
