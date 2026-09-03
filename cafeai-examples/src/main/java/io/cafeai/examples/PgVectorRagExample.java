package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.rag.*;

import java.util.Map;

/**
 * PgVectorRagExample — RAG over PostgreSQL + pgvector.
 *
 * <p>The same pipeline as {@link ChromaVectorExample}, one line different:
 * {@code app.vectordb(VectorStore.pgVector(...))}. Chunks are stored in a
 * {@code cafeai_chunks} table (created on first connect, with an {@code ivfflat}
 * cosine index) and survive restarts. Ingestion is idempotent — the chunk id
 * maps to a deterministic UUID primary key, so re-ingesting updates in place.
 *
 * <h2>Prerequisites</h2>
 * A Postgres with the {@code vector} extension. The {@code pgvector/pgvector}
 * image has it; CafeAI runs {@code CREATE EXTENSION IF NOT EXISTS vector} on
 * connect, so no init script is needed for a container you own. On managed
 * Postgres, an admin runs {@code CREATE EXTENSION vector} once.
 *
 * <pre>
 *   docker run -d --name cafeai-pg -p 5432:5432 \
 *     -e POSTGRES_USER=cafeai -e POSTGRES_PASSWORD=cafeai -e POSTGRES_DB=cafeai \
 *     pgvector/pgvector:pg16
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   # defaults: localhost:5432, db/user/pass all "cafeai"
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.PgVectorRagExample
 *
 *   # or override:
 *   PGVECTOR_HOST=... PGVECTOR_DB=... PGVECTOR_USER=... PGVECTOR_PASSWORD=... \
 *     ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.PgVectorRagExample
 * </pre>
 *
 * <h2>Try it</h2>
 * <pre>
 *   curl -X POST http://localhost:8080/ask \
 *        -H "Content-Type: application/json" \
 *        -d '{"question": "When should I use PgVector instead of Chroma?"}'
 *   # {"question": "...", "answer": "...", "sources": 2, "model": "gpt-4o-mini"}
 * </pre>
 *
 * <h2>Verify the rows landed</h2>
 * <pre>
 *   docker exec -it cafeai-pg psql -U cafeai -d cafeai \
 *     -c "SELECT count(*) FROM cafeai_chunks;"
 *   docker exec -it cafeai-pg psql -U cafeai -d cafeai \
 *     -c "SELECT left(text, 70) FROM cafeai_chunks LIMIT 5;"
 *
 *   # Stop (Ctrl+C), restart — count stays stable (idempotent), RAG still answers.
 * </pre>
 *
 * <h2>The one gotcha — vector dimension</h2>
 * {@code PgVectorConfig.dimension()} MUST equal the registered
 * {@code EmbeddingModel}'s size: {@code EmbeddingModel.local()} is 384,
 * {@code EmbeddingModel.openAi()} is 1536. A mismatch fails at
 * {@code CREATE TABLE} or returns nonsense from {@code search}.
 */
public class PgVectorRagExample {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(PgVectorRagExample.class);

    public static void main(String[] args) {
        var app = CafeAI.create();

        app.ai(OpenAI.gpt4oMini());
        app.system("""
            You are a helpful assistant with access to the CafeAI knowledge base.
            Answer from the provided context. Be concise. If the context does not
            contain the answer, say so.
            """);
        app.memory(MemoryStrategy.inMemory());

        // ── Vector store — PostgreSQL + pgvector ─────────────────────────────
        //
        //   Before:  app.vectordb(VectorStore.inMemory());
        //   After:   app.vectordb(VectorStore.pgVector(config));
        //
        // dimension MUST match the EmbeddingModel below (local() == 384).
        var config = PgVectorConfig.builder()
            .host(env("PGVECTOR_HOST", "localhost"))
            .port(Integer.parseInt(env("PGVECTOR_PORT", "5432")))
            .database(env("PGVECTOR_DB", "cafeai"))
            .user(env("PGVECTOR_USER", "cafeai"))
            .password(env("PGVECTOR_PASSWORD", "cafeai"))
            .dimension(384)
            .build();

        app.vectordb(VectorStore.pgVector(config));
        app.embed(EmbeddingModel.local());          // 384-dim, no API key
        app.rag(Retriever.hybrid(4)                 // dense + keyword fusion
            .denseWeight(0.7)
            .sparseWeight(0.3));

        seedKnowledgeBase(app);

        app.filter(CafeAI.json());
        app.filter(Middleware.requestLogger());

        app.get("/health", (req, res, next) ->
            res.json(Map.of(
                "status",      "ok",
                "vectorStore", "pgvector",
                "table",       config.table(),
                "jdbcUrl",     config.jdbcUrl())));

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
                "model",    response.modelId()));
        });

        app.listen(8080, () -> System.out.printf("""
            ☕ PgVectorRagExample running on http://localhost:8080

               GET  /health   → shows vectorStore=pgvector, table, jdbcUrl
               POST /ask       → RAG-powered Q&A backed by pgvector

            Store:  %s  (table: %s)
            Chunks persist across restarts; ingestion is idempotent.

            Try it:
              curl -X POST http://localhost:8080/ask \\
                   -H "Content-Type: application/json" \\
                   -d '{"question": "When should I use PgVector instead of Chroma?"}'

            Press Ctrl+C to stop.
            %n""", config.jdbcUrl(), config.table()));
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static void seedKnowledgeBase(CafeAI app) {
        log.info("Seeding knowledge base into pgvector...");

        app.ingest(Source.text("""
            # CafeAI — Overview

            CafeAI is a Java framework for building AI-native applications, built on
            Helidon SE and LangChain4j with an Express.js-style middleware pattern.
            Spring AI is for convenience; CafeAI is for conviction.
            """, "cafeai/overview"));

        app.ingest(Source.text("""
            # CafeAI — Vector Store Options

              VectorStore.inMemory()              development — chunks lost on restart
              VectorStore.chroma(url, name)       local, lightweight, restart-durable
              VectorStore.pgVector(config)        PostgreSQL + pgvector — production

            Use Chroma for local development and small teams. Use PgVector when you
            already run PostgreSQL and want ACID transactions, SQL-queryable
            metadata, and one less service to operate. Switching is one line —
            the ingestion pipeline, embedding model, and retriever are unchanged.
            """, "cafeai/vectorstores"));

        app.ingest(Source.text("""
            # CafeAI — Hybrid Retrieval

            Retriever.hybrid(k).denseWeight(x).sparseWeight(y) fuses dense semantic
            similarity with a BM25 term-frequency keyword score. It beats
            semantic-only retrieval on keyword-heavy queries — product codes,
            policy numbers, exact identifiers — while keeping semantic recall.
            The keyword score re-ranks the dense candidate pool, so it works with
            every VectorStore without a separate keyword index.
            """, "cafeai/hybrid-retrieval"));

        log.info("Knowledge base ready — 3 documents ingested into pgvector");
    }
}
