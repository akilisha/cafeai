# ADR-011: RAG Abstraction — Justified Complexity vs. Accidental Complexity

**Status:** Accepted
**Date:** September 2026

## Context

Someone coming to `cafeai-rag` after working through a straightforward
LangChain4j + OpenAI RAG tutorial can be genuinely thrown by what they find:
interfaces, factory methods, a provider SPI, a package split across two
modules — none of which the tutorial needed. Without an explanation, that
reads as over-engineering. It usually isn't, but sometimes it is, and a
reader has no way to tell the difference from the code alone. This ADR is
that explanation.

## The comparison

Here's the same task, side by side. A flat script for OpenAI + LangChain4j
RAG is roughly:

```java
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder().apiKey(key).modelName("text-embedding-3-small").build();
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
for (TextSegment seg : DocumentSplitters.recursive(500, 50).split(document)) {
    store.add(embeddingModel.embed(seg).content(), seg);
}
Embedding queryEmbedding = embeddingModel.embed(query).content();
String context = store.findRelevant(queryEmbedding, 5).stream()
    .map(m -> m.embedded().text()).collect(joining("\n"));
String answer = chatModel.chat("Context:\n" + context + "\n\nQuestion: " + query);
```

That's genuinely simple — because it never has to be anything else. One
embedding provider, one vector store, one retrieval strategy, used exactly
once, in one file, never reused. `cafeai-rag` is solving a different
problem: the same application code needs to work whether someone plugs in
local ONNX or OpenAI embeddings, in-memory or pgvector or Chroma, semantic
or hybrid (BM25) retrieval — and RAG results need to compose automatically
with whatever guardrails/memory/observability middleware the app already
registered for `app.prompt()`, without the caller doing anything extra. A
tutorial script never has to pay for that swappability, because it only
ever needs to work once, for one combination. A framework does, or
"framework" is just a lie about what it's for.

## Decision

Keep the provider-agnostic shape — `EmbeddingProvider`, `VectorStore`,
`Retriever`, `Source` as swappable interfaces, wired into `app.prompt()`
automatically once registered. This is the cost of the actual feature
(swap providers/stores/strategies without touching call sites), not
ceremony for its own sake.

## The other half of this ADR: not all complexity here was justified

The comparison above is a real defense, but it is not a blank check — it
explains why an *abstraction* exists, not that every line behind it is
automatically fine. An audit of this exact module (2026-09) found several
things that had drifted into genuine accidental complexity, indistinguishable
from the justified kind until someone actually checked:

- **`io.cafeai.rag.EmbeddingModel` collided with LangChain4j's own
  `EmbeddingModel` type** — its own adapter code carried a comment admitting
  the collision forced a fully-qualified-name workaround. `AiProvider`
  had already solved this exact problem for `ChatModel`; this one type
  just hadn't followed suit. Fixed by renaming to `EmbeddingProvider`.
- **A hardcoded, already-retired default embedding model**
  (`text-embedding-ada-002`) and a **dimension lookup table that guessed
  vector size from substrings in the model id** — any model that didn't
  match a known substring silently got the wrong dimensionality, with no
  error. Fixed by requiring an explicit model id (or an env var, with a
  loud failure if neither is set) and measuring dimensionality from a real
  embedding call instead of guessing.
- **The module boundary between `cafeai-core` and `cafeai-rag` was crossed
  with `Object` parameters and a runtime `requireType()` cast**, at both
  `app.vectordb()/embed()/ingest()/rag()` and the SPI beneath them —
  exactly the shape `MemoryStrategy` had already solved cleanly (contract
  and zero-dependency default in `cafeai-core`, real implementations behind
  a provider SPI). RAG just hadn't been brought in line with that pattern.
  Fixed by moving `VectorStore`/`EmbeddingProvider`/`Retriever`/`Source`
  into `io.cafeai.core.rag` and introducing `RagProvider` (see
  `docs/EXTENDING.md`).

None of those were the provider-agnostic design being wrong — they were
specific, nameable, fixable mistakes sitting inside a design that was
otherwise sound. That's the actual skill this ADR is trying to hand off:
not "trust that the abstraction is justified," but how to tell the
difference yourself, in this codebase or any other.

## Consequences

- New RAG-adjacent code should default to the `MemoryStrategy` shape:
  contract (and any genuinely zero-dependency implementation) in
  `cafeai-core`, heavier implementations behind a provider SPI.
- A collision with a LangChain4j type name, a hardcoded "current" model id,
  or a value guessed from a string pattern instead of measured/required
  explicitly are all worth treating as bugs on sight, not style
  preferences — each one was a real, live instance of exactly that in this
  module before the 2026-09 audit.
