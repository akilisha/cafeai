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

## Consequences

- New RAG-adjacent code should default to the `MemoryStrategy` shape:
  contract (and any genuinely zero-dependency implementation) in
  `cafeai-core`, heavier implementations behind a provider SPI.
- A collision with a LangChain4j type name, a hardcoded "current" model id,
  or a value guessed from a string pattern instead of measured/required
  explicitly are all worth treating as bugs on sight, not style
  preferences.
