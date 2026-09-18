# Building a RAG Pipeline in Java — Ingestion, Embedding, and Retrieval

*Post 6 of 12 in the CafeAI series*

---

Language models know a lot. They do not know your documentation, your internal procedures, your vendor contracts, or anything that changed after their training cutoff. Retrieval-augmented generation (RAG) is the mechanism for giving them that knowledge at runtime — without fine-tuning, without retraining, without touching the model.

The principle is straightforward: before calling the LLM, retrieve the most relevant documents from a knowledge base and include them in the prompt. The model answers using the retrieved content rather than hallucinating from training data. The answers cite real sources, not invented ones.

CafeAI's RAG pipeline is three registrations and an ingestion call:

```java
app.vectordb(VectorStore.inMemory());  // where chunks are stored
app.embed(EmbeddingProvider.local());  // how text becomes vectors
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

// PDF — text is extracted with Apache Tika
app.ingest(Source.pdf("vendor-contracts/acme-2024.pdf"));

// URL — fetches and ingests the page content
app.ingest(Source.url("https://docs.helios.io/api-reference"));

// Directory — ingests all supported files in the directory
app.ingest(Source.directory("src/main/resources/docs/"));
```

Each source is identified by a source ID — the string `"helios/api-overview"`, the file path, the URL. The source ID is attached to every chunk derived from that source, so a source can be removed as a unit: keep a reference to the store and call `store.deleteBySource("helios/api-overview")` to drop all its chunks before re-ingesting a new version.

---

## Chunking

Long documents are split into chunks before embedding. A chunk is the unit of retrieval — smaller chunks give more precise retrieval; larger chunks give more complete context.

CafeAI's chunker is a sliding window over the text: 512 characters per chunk, with 64 characters of overlap between neighbours.

```
Document: [----chunk 1----][----chunk 2----][----chunk 3----]
                     [overlap]         [overlap]
```

The overlap keeps a sentence that straddles a boundary intact in at least one chunk.

Chunk IDs are deterministic: `<sourceId>#chunk<index>` (`helios/auth#chunk0`, `helios/auth#chunk1`, ...). Ingesting the same source twice therefore overwrites its chunks rather than duplicating them. It does not remove chunks the new text no longer has: if a document gets shorter, the old tail chunks remain until you call `deleteBySource` first.

---

## Embedding

The embedding model converts text into a high-dimensional vector that captures semantic meaning. Two texts with similar meaning produce similar vectors; two texts about different things produce dissimilar vectors.

CafeAI provides two embedding options:

```java
// Local ONNX model — no API call, no cost, no data leaves the machine
app.embed(EmbeddingProvider.local());

// OpenAI embedding API — higher quality, network required
app.embed(EmbeddingProvider.openAi("text-embedding-3-small"));
```

The local model is a quantized all-MiniLM-L6-v2 in ONNX form, bundled with `cafeai-rag` and run in-process by LangChain4j (384-dimensional vectors). Nothing leaves the machine.

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
app.vectordb(VectorStore.pgVector(
    PgVectorConfig.builder()
        .host("localhost").port(5432).database("cafeai")
        .user("cafeai").password(System.getenv("PGPASSWORD"))
        .dimension(384)               // must match the registered EmbeddingProvider
        .build()));
```

The `support-desk` capstone uses `VectorStore.inMemory()` — the knowledge base is small (six documentation pages), ingested at startup, and does not need persistence. Restarting the application re-ingests in under a second.

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

Hybrid retrieval combines semantic similarity with keyword matching. It is better for queries that contain domain-specific terms, product names, or identifiers — things that may not have good semantic neighbours but should be retrieved when the exact term matches.

---

## What RAG Retrieved

Every response carries the chunks that informed it:

```java
var response = app.prompt("How do I handle rate limit errors in Helios?").call();

for (RagDocument doc : response.ragDocuments()) {
    System.out.printf("%s  score %.2f%n", doc.sourceId(), doc.score());
}
// helios/rate-limits  score 0.94
// helios/troubleshoot score 0.71
// helios/auth         score 0.43
```

`ragDocuments()` gives the source ID and similarity score of each retrieved chunk. When an answer is wrong, the developer can inspect which chunks were retrieved and diagnose whether the problem is in the retrieval (wrong chunks) or the model (wrong answer given the right chunks). These are different problems with different fixes. The console observability strategy prints how many documents were retrieved (`rag docs: 3 retrieved`), and the OpenTelemetry span records the count.

---

## Dynamic Knowledge Bases

A knowledge base can change while the application runs. Keep a reference to the store, and replace a source by deleting it and ingesting the new version:

```java
var store = VectorStore.inMemory();          // or chroma(...), pgVector(...)
app.vectordb(store);
app.ingest(Source.pdf("policies/2024-auto.pdf"));

// Later — when a policy is replaced
store.deleteBySource("policies/2024-auto.pdf");
app.ingest(Source.pdf("policies/2025-auto.pdf"));
```

`deleteBySource()` removes every chunk derived from that source, and the ingest adds the new version. The swap is not atomic: a query issued between the two steps finds no chunks for that source, though it never sees stale ones.

---

## Costs and Token Budgets

RAG has a token cost. Each retrieved chunk adds tokens to the LLM prompt — typically 200-500 tokens per chunk, multiplied by the number of chunks retrieved. Three chunks at 400 tokens each add 1,200 tokens to every prompt call.

The `invoice-processor` capstone demonstrates cost management with the token budget:

```java
app.budget(TokenBudget.perMinute(30_000));  // OpenAI free tier
```

The token budget tracker monitors actual usage across all calls. When the budget is approaching the limit, subsequent calls wait until the window resets. This prevents rate limit errors without pauses in application code.

Post 11 covers token budgets, retry policies, and production observability in full.

---

## Post 7 — Tool Use

Post 7 covers tool use — giving the AI actions to take, not just information to retrieve.

---

*CafeAI: Not an invention of anything new. A re-orientation of everything proven.*
