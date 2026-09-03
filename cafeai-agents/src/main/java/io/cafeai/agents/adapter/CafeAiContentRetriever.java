package io.cafeai.agents.adapter;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.cafeai.core.spi.RagPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Adapts CafeAI's RAG pipeline to a LangChain4j {@link ContentRetriever} so an
 * agent's {@code AiServices} builder can be wired with
 * {@code .contentRetriever(...)}.
 *
 * <p>Holds the opaque {@code io.cafeai.rag.*} handles registered on the app
 * ({@code Retriever}, {@code VectorStore}, {@code EmbeddingModel}) and dispatches
 * retrieval through the {@link RagPipeline} SPI — the same path
 * {@code app.prompt()} uses — so an agent and a plain prompt see the same
 * knowledge base. Requires {@code cafeai-rag} on the classpath.
 */
public final class CafeAiContentRetriever implements ContentRetriever {

    private final Object retriever;
    private final Object vectorStore;
    private final Object embeddingModel;

    public CafeAiContentRetriever(Object retriever, Object vectorStore, Object embeddingModel) {
        this.retriever      = retriever;
        this.vectorStore    = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Content> retrieve(Query query) {
        RagPipeline pipeline = ServiceLoader.load(RagPipeline.class).findFirst().orElse(null);
        if (pipeline == null) {
            return List.of();
        }
        List<Object> docs = pipeline.retrieve(query.text(), retriever, vectorStore, embeddingModel);
        List<Content> out = new ArrayList<>(docs.size());
        for (Object doc : docs) {
            if (doc != null) {
                out.add(Content.from(doc.toString()));
            }
        }
        return out;
    }
}
