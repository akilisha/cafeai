package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.rag.Chroma;
import io.cafeai.rag.EmbeddingModel;
import io.cafeai.rag.Retriever;
import io.cafeai.rag.Source;
import io.cafeai.rag.VectorStore;

import java.util.Map;

/**
 * ChromaVectorExample — persistent vector store via Chroma.
 *
 * <p>Demonstrates {@link Chroma} as a drop-in replacement for
 * {@link VectorStore#inMemory()}. Documents are ingested once and
 * persist in Chroma across application restarts. The knowledge base
 * does not need to be re-embedded on every startup.
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li>Documents survive application restarts (unlike {@code inMemory()})</li>
 *   <li>The knowledge base is externally managed and queryable</li>
 *   <li>Swapping vector backends is one line of code</li>
 *   <li>RAG retrieval quality is identical — the pipeline is unchanged</li>
 * </ol>
 *
 * <h2>Prerequisites</h2>
 * Start Chroma before running. Pin to 0.5.x (LangChain4j requirement):
 * <pre>
 *   docker run -d -p 8000:8000 chromadb/chroma:0.5.23
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :cafeai-examples:run
 * </pre>
 *
 * <h2>Proving document persistence</h2>
 * <pre>
 *   # First run — documents are ingested into Chroma
 *   ./gradlew :cafeai-examples:run
 *   # INFO  ChromaVectorExample -- Ingested 3 documents into Chroma
 *
 *   # Ask a question
 *   curl -X POST http://localhost:8080/ask \
 *        -H "Content-Type: application/json" \
 *        -d '{"question": "What is CafeAI?"}'
 *   # {"answer": "...", "sources": 2}
 *
 *   # Stop (Ctrl+C) and restart
 *   # Second run — documents are already in Chroma, ingestion skipped
 *
 *   # Ask again — RAG still works, no re-embedding needed
 *   curl -X POST http://localhost:8080/ask \
 *        -H "Content-Type: application/json" \
 *        -d '{"question": "What is CafeAI?"}'
 * </pre>
 *
 * <h2>Inspecting the collection</h2>
 * <pre>
 *   # List collections
 *   curl http://localhost:8000/api/v1/collections
 *
 *   # The collection ID from above
 *   curl http://localhost:8000/api/v1/collections/{id}/count
 * </pre>
 *
 * <h2>The one-line swap</h2>
 * <pre>
 *   // Before — documents lost on restart
 *   app.vectordb(VectorStore.inMemory());
 *
 *   // After — documents persist in Chroma
 *   app.vectordb(VectorStore.chroma("http://localhost:8000", "cafeai-example"));
 * </pre>
 *
 * <h2>When to use Chroma vs PgVector</h2>
 * <pre>
 *   Chroma:   local development, small teams, Docker-friendly, no SQL dependency
 *   PgVector: enterprise production, existing PostgreSQL infrastructure,
 *             ACID guarantees, SQL-queryable metadata
 * </pre>
 */
public class ChromaVectorExample {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ChromaVectorExample.class);

    // Stable collection name — documents persist under this name in Chroma
    private static final String COLLECTION = "cafeai-example";

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI Provider ───────────────────────────────────────────────────────
        app.ai(OpenAI.gpt4oMini());

        // ── System Prompt ─────────────────────────────────────────────────────
        app.system("""
            You are a helpful assistant with access to the CafeAI knowledge base.
            Answer questions using the provided context. Be concise and accurate.
            If the context doesn't contain the answer, say so.
            """);

        // ── Memory ────────────────────────────────────────────────────────────
        app.memory(MemoryStrategy.inMemory());

        // ── Vector Store — Chroma (persistent) ───────────────────────────────
        //
        // THIS IS THE ONLY LINE THAT DIFFERS FROM A STANDARD RAG SETUP.
        //
        // Replace:
        //   app.vectordb(VectorStore.inMemory());
        //
        // With:
        //   app.vectordb(VectorStore.chroma("http://localhost:8000", COLLECTION));
        //
        // The collection name is stable — documents written on the first run
        // are available on every subsequent run without re-ingestion.
        //
        app.vectordb(VectorStore.chroma("http://localhost:8000", COLLECTION));
        app.embed(EmbeddingModel.local());
        app.rag(Retriever.semantic(3));

        // ── Knowledge Base ────────────────────────────────────────────────────
        // Ingest documents on every startup.
        // CafeAI's ingestion pipeline is idempotent — chunk IDs are stable,
        // so re-ingesting the same documents updates existing entries rather
        // than creating duplicates.
        seedKnowledgeBase(app);

        // ── Middleware ────────────────────────────────────────────────────────
        app.filter(CafeAI.json());
        app.filter(Middleware.requestLogger());

        // ── Routes ────────────────────────────────────────────────────────────

        app.get("/health", (req, res, next) ->
            res.json(Map.of(
                "status",     "ok",
                "vectorStore", "chroma",
                "collection",  COLLECTION
            )));

        // RAG-powered question answering
        app.post("/ask", (req, res, next) -> {
            String question = req.body("question");
            if (question == null || question.isBlank()) {
                res.status(400).json(Map.of("error", "question field required"));
                return;
            }

            var response = app.prompt(question).call();

            res.json(Map.of(
                "question", question,
                "answer",   response.text(),
                "sources",  response.ragDocuments().size(),
                "model",    response.modelId()
            ));
        });

        // ── Start ─────────────────────────────────────────────────────────────
        app.listen(8080, () -> System.out.printf("""
            ☕ ChromaVectorExample running on http://localhost:8080

               GET  /health   → health check (shows vectorStore=chroma, collection)
               POST /ask      → RAG-powered Q&A backed by Chroma

            Vector store: Chroma at http://localhost:8000
            Collection:   %s
            Documents persist across restarts — no re-embedding on every startup.

            Try it:
              curl -X POST http://localhost:8080/ask \\
                   -H "Content-Type: application/json" \\
                   -d '{"question": "What is CafeAI?"}'

            Inspect the collection:
              curl http://localhost:8000/api/v1/collections

            Press Ctrl+C to stop.
            %n""", COLLECTION));
    }

    // ── Knowledge Base ────────────────────────────────────────────────────────

    private static void seedKnowledgeBase(CafeAI app) {
        log.info("Seeding knowledge base into Chroma collection '{}'...", COLLECTION);

        app.ingest(Source.text("""
            # CafeAI — Overview

            CafeAI is a Java framework for building AI-native applications.
            It is built on Helidon SE and LangChain4j, using an Express.js-style
            middleware pattern that Java developers already know.

            CafeAI is not an invention of anything new. It is a deliberate
            re-orientation of familiar, battle-tested patterns and paradigms —
            Java's robustness, Express's composability, LangChain's AI primitives —
            unified into a composable framework for the AI age.

            The differentiator in one sentence: Spring AI is for convenience.
            CafeAI is for conviction.
            """, "cafeai/overview"));

        app.ingest(Source.text("""
            # CafeAI — Tiered Memory Architecture

            CafeAI's memory model mirrors the hardware memory hierarchy:

              inMemory()   JVM heap — development and testing, zero deps
              mapped()     SSD-backed FFM MemorySegment — single-node production
              redis()      Lettuce + Redis — distributed, multi-instance
              hybrid()     Warm SSD + cold Redis — best of both

            The key insight: most applications do not need Redis. The SSD-backed
            FFM tier handles production single-node deployments with zero network
            overhead, zero cloud cost, and crash recovery for free.

            Redis is the escape valve — reached for when you genuinely need state
            shared across multiple application instances.
            """, "cafeai/memory"));

        app.ingest(Source.text("""
            # CafeAI — Vector Store Options

            CafeAI supports pluggable vector stores for RAG pipelines:

              VectorStore.inMemory()         Development — documents lost on restart
              VectorStore.chroma(url)        Chroma — local, lightweight, persistent
              VectorStore.chroma(url, name)  Chroma with specific collection name
              PgVector.connect(config)       PostgreSQL + pgvector — enterprise

            The one-line swap: changing from inMemory() to chroma() is the only
            code change required. The ingestion pipeline, embedding model, and
            retriever are all unchanged.

            Use Chroma for local development and small teams.
            Use PgVector for enterprise production with existing PostgreSQL infrastructure.
            """, "cafeai/vectorstores"));

        log.info("Knowledge base ready — 3 documents ingested into '{}'", COLLECTION);
    }
}
