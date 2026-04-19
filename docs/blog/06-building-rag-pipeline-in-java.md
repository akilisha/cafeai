# Building a RAG Pipeline in Java — Ingestion, Embedding, and Retrieval

*Post 6 of 12 in the CafeAI series*

---

Language models know a lot. They do not know your documentation, your internal procedures, your vendor contracts, or anything that changed after their training cutoff. Retrieval-augmented generation (RAG) is the mechanism for giving them that knowledge at runtime — without fine-tuning, without retraining, without touching the model.

The principle is straightforward: before calling the LLM, retrieve the most relevant documents from a knowledge base and include them in the prompt. The model answers using the retrieved content rather than hallucinating from training data. The answers cite real sources, not invented ones.

CafeAI's RAG pipeline is three registrations and an ingestion call:

```java
app.vectordb(VectorStore.inMemory());  // where chunks are stored
app.embed(EmbeddingModel.local());     // how text becomes vectors
app.rag(Retriever.semantic(3));        // how vectors become context

app.ingest(Source.pdf("handbook.pdf")); // load knowledge
```

After that, every `app.prompt()` call automatically retrieves the three most relevant chunks and prepends them to the LLM context. The developer orchestrates nothing — retrieval is a pipeline layer.

---

## The Pipeline

The RAG pipeline in CafeAI has four stages that run in sequence on every prompt call:

**1. Ingestion (startup)** — Documents are loaded, split into chunks, embedded into vectors, and stored in the vector database. Ingestion runs once at startup (or on demand for dynamic knowledge bases).

**2. Query embedding** — The user's prompt is embedded into a vector using the same embedding model used for ingestion.

**3. Retrieval** — The query vector is compared against stored chunk vectors using cosine similarity. The top-K most similar chunks are returned.

**4. Context injection** — Retrieved chunks are prepended to the LLM prompt as context. The model answers using the retrieved content.

```
User: "How do I handle rate limit errors in Helios?"
           ↓
Query embedding:  [0.23, -0.87, 0.14, ...]
           ↓
Cosine similarity against:
  - helios/rate-limits chunk 1: similarity 0.94  ← retrieved
  - helios/auth chunk 3:        similarity 0.71  ← retrieved
  - helios/webhooks chunk 2:    similarity 0.43
           ↓
LLM prompt:
  [Context: "Rate limit headers include X-RateLimit-Remaining..."]
  [Context: "When the rate limit is exceeded, the API returns 429..."]
  User: "How do I handle rate limit errors in Helios?"
           ↓
Model: "When you receive a 429 response from the Helios API, 
        check the X-RateLimit-Reset header..."
```

The model answers from retrieved documentation, not from training memory. The answer is accurate because the source is authoritative.

---

## Ingestion

CafeAI supports four ingestion sources:

```java
// Inline text — good for documentation stored in code or config
app.ingest(Source.text(documentationString, "helios/api-overview"));

// PDF — digital or scanned
app.ingest(Source.pdf("vendor-contracts/acme-2024.pdf"));

// URL — fetches and ingests the page content
app.ingest(Source.url("https://docs.helios.io/api-reference"));

// Directory — ingests all supported files in the directory
app.ingest(Source.directory("src/main/resources/docs/"));
```

Each source is identified by a source ID — the string `"helios/api-overview"`, the file path, the URL. The source ID is attached to every chunk derived from that source. This enables targeted deletion: if a document is updated, `app.deleteBySource("helios/api-overview")` removes all its chunks before re-ingesting the new version.

---

## Chunking

Long documents are split into chunks before embedding. A chunk is the unit of retrieval — smaller chunks give more precise retrieval; larger chunks give more complete context.

CafeAI's default chunker uses a sliding window with overlap:

```
Document: [----chunk 1----][----chunk 2----][----chunk 3----]
                     [overlap]         [overlap]
```

The overlap ensures that sentences at chunk boundaries are not lost. A concept that starts at the end of chunk 1 and continues into chunk 2 will be present in both — whichever chunk is retrieved will contain the full context.

The chunker assigns deterministic IDs to each chunk based on the content hash. Ingesting the same document twice produces the same chunk IDs — the second ingestion upserts rather than duplicates.

---

## Embedding

The embedding model converts text into a high-dimensional vector that captures semantic meaning. Two texts with similar meaning produce similar vectors; two texts about different things produce dissimilar vectors.

CafeAI provides two embedding options:

```java
// Local ONNX model — no API call, no cost, no data leaves the machine
app.embed(EmbeddingModel.local());

// OpenAI embedding API — higher quality, network required
app.embed(EmbeddingModel.openai("text-embedding-3-small"));
```

The local ONNX model runs via Java FFM — the same API that backs the SSD session memory. A pre-trained embedding model is bundled with `cafeai-rag` and runs entirely in-process. For most documentation retrieval use cases, the local model produces retrieval quality that is indistinguishable from the OpenAI API.

The tradeoff: local embeddings are faster (no network round-trip), cheaper (no API cost), and private (no data sent externally). OpenAI embeddings are marginally higher quality on very specialised domains. For general technical documentation, local is the right default.

---

## Vector Stores

CafeAI supports three vector store backends:

```java
// In-memory — fast, no persistence, lost on restart
app.vectordb(VectorStore.inMemory());

// Chroma — persistent, external service, queryable via HTTP
app.vectordb(VectorStore.chroma("http://localhost:8000"));
app.vectordb(VectorStore.chroma("http://localhost:8000", "collection-name"));

// PgVector — PostgreSQL with vector extension, production-grade
app.vectordb(VectorStore.pgVector(PgVectorConfig.of("localhost", 5432, "cafeai")));
```

The `support-agent` capstone uses `VectorStore.inMemory()` — the knowledge base is small (six documentation pages), ingested at startup, and does not need persistence. Restarting the application re-ingests in under a second.

The `acme-claims` capstone uses `VectorStore.chroma()` — the insurance knowledge base is larger, shared across application instances, and needs to persist across restarts. Chroma is pinned to version 0.5.23 (LangChain4j is not yet compatible with Chroma 0.6+).

The switch between backends is one line at startup. The ingestion calls, the retrieval calls, the pipeline — identical regardless of backend.

---

## Retrieval Strategies

```java
// Semantic retrieval — cosine similarity on embeddings
app.rag(Retriever.semantic(3));   // top 3 chunks

// Hybrid retrieval — dense semantic + sparse keyword (BM25)
app.rag(Retriever.hybrid(5));     // top 5 from combined scoring
```

Semantic retrieval finds chunks that are conceptually similar to the query, even if they use different words. A query about "how to handle 429 errors" retrieves chunks about "rate limiting" even without the word "429" in the chunk.

Hybrid retrieval combines semantic similarity with keyword matching. It is better for queries that contain domain-specific terms, product names, or identifiers — things that may not have good semantic neighbours but should be retrieved when the exact term matches. The `acme-claims` capstone uses hybrid retrieval for policy lookups where the policy number is the critical identifier.

---

## What RAG Retrieved — The `support-agent` Capstone

The observability output from a `support-agent` prompt call shows exactly what RAG retrieved:

```
-- LLM Call -----------------------------------------
  model:      openai (qwen2.5 via Ollama)
  session:    dev-123
  tokens:     847 prompt + 23 completion = 870 total
  latency:    1,203ms
  rag docs:   3 retrieved
    helios/rate-limits    score: 0.94
    helios/troubleshoot   score: 0.71
    helios/auth           score: 0.43
------------------------------------------------------
```

The source ID and similarity score are visible for every retrieved chunk. When an answer is wrong, the developer can inspect which chunks were retrieved and diagnose whether the problem is in the retrieval (wrong chunks) or the model (wrong answer given the right chunks). These are different problems with different fixes.

---

## Dynamic Knowledge Bases

The `acme-claims` capstone demonstrates a pattern where the knowledge base updates without restarting the application:

```java
// At startup — ingest base knowledge
app.ingest(Source.pdf("policies/2024-auto.pdf"));
app.ingest(Source.pdf("policies/2024-home.pdf"));

// Later — when a policy is updated, replace it atomically
app.deleteBySource("policies/2024-auto.pdf");
app.ingest(Source.pdf("policies/2025-auto.pdf"));
```

`deleteBySource()` removes all chunks derived from that source. The subsequent ingest adds the new version. The update is atomic from the retrieval pipeline's perspective — a query issued between the delete and the re-ingest will find no chunks for that source (a brief degraded state), but no stale chunks will be returned.

For high-availability scenarios, the update can be performed on a shadow index and swapped atomically. That pattern is outside the scope of this post but is supported by the `pgVector` backend.

---

## Costs and Token Budgets

RAG has a token cost. Each retrieved chunk adds tokens to the LLM prompt — typically 200-500 tokens per chunk, multiplied by the number of chunks retrieved. Three chunks at 400 tokens each add 1,200 tokens to every prompt call.

The `atlas-inbox` capstone demonstrates cost management with the token budget:

```java
app.budget(TokenBudget.perMinute(30_000));  // OpenAI free tier
```

The token budget tracker monitors actual usage across all calls. When the budget is approaching the limit, subsequent calls wait until the window resets. This prevents rate limit errors without the `Thread.sleep` calls that appeared in the original atlas-inbox implementation before the budget API was added.

Post 11 covers token budgets, retry policies, and production observability in full.

---

## Post 7 — Tool Use

Post 7 covers tool use — giving the AI actions to take, not just information to retrieve. Coming in ROADMAP-17.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
