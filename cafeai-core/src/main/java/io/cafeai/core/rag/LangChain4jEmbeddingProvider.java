package io.cafeai.core.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.Objects;

/** Adapts any LangChain4j {@link EmbeddingModel} to CafeAI's {@link EmbeddingProvider}. */
final class LangChain4jEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel model;

    LangChain4jEmbeddingProvider(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model, "EmbeddingModel must not be null");
    }

    @Override public float[] embed(String text) { return model.embed(text).content().vector(); }
    @Override public int     dimensions()       { return model.dimension(); }
    @Override public String  modelId()          { return model.modelName(); }
}
