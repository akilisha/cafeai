package io.cafeai.aiservices.adapter;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.cafeai.core.rag.EmbeddingProvider;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.Retriever;
import io.cafeai.core.rag.VectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts CafeAI's RAG pipeline to a LangChain4j {@link ContentRetriever} so an
 * agent's {@code AiServices} builder can be wired with
 * {@code .contentRetriever(...)}.
 *
 * <p>Calls the same {@link Retriever}/{@link VectorStore}/{@link EmbeddingProvider}
 * registered on the app directly — the same path {@code app.prompt()} uses, so
 * an agent and a plain prompt see the same knowledge base.
 */
public final class CafeAiContentRetriever implements ContentRetriever {

    private final Retriever retriever;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingModel;

    public CafeAiContentRetriever(Retriever retriever, VectorStore vectorStore, EmbeddingProvider embeddingModel) {
        this.retriever      = retriever;
        this.vectorStore    = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<RagDocument> docs = retriever.retrieve(query.text(), embeddingModel, vectorStore);
        List<Content> out = new ArrayList<>(docs.size());
        for (RagDocument doc : docs) {
            out.add(Content.from(doc.toString()));
        }
        return out;
    }
}
