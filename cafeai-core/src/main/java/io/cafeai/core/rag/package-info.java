/**
 * RAG (retrieval-augmented generation) contracts for CafeAI.
 *
 * <p>{@link io.cafeai.core.rag.VectorStore}, {@link io.cafeai.core.rag.EmbeddingProvider},
 * {@link io.cafeai.core.rag.Retriever}, and {@link io.cafeai.core.rag.Source} are
 * provider-agnostic interfaces, wired into {@code app.prompt()} automatically once
 * registered via {@code app.vectordb()}/{@code app.embed()}/{@code app.rag()}/
 * {@code app.ingest()}. Zero-dependency defaults ({@code VectorStore.inMemory()},
 * both {@code Retriever} strategies, {@code Source.text()}) are implemented right
 * here; anything needing Apache Tika, the Postgres driver, the Chroma client, or an
 * ONNX model bundle is supplied by {@code cafeai-rag} through
 * {@link io.cafeai.core.spi.RagProvider} instead — the same shape as
 * {@link io.cafeai.core.memory.MemoryStrategy} and its
 * {@code MemoryStrategyProvider}.
 *
 * <p>If this package's shape — interfaces plus a provider SPI, instead of one
 * concrete implementation — looks like more machinery than a RAG tutorial needs,
 * that's deliberate and explained in full in
 * {@code docs/adr/ADR-011-rag-provider-abstraction.md}: a tutorial only ever needs
 * one embedding provider and one vector store, used once; an application built on
 * this package needs to swap either without touching its own code. That ADR also
 * documents the real, unrelated bugs an audit found sitting inside this
 * justification — worth reading alongside each other, not just the flattering half.
 */
package io.cafeai.core.rag;
