package io.cafeai.core.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.cafeai.core.rag.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code EmbeddingProvider.of(EmbeddingModel)}: any LangChain4j embedding model backs CafeAI, unwrapped. */
class LangChain4jEmbeddingBridgeTest {

    /** LangChain4j's own interface, implemented directly — no CafeAI type in sight. */
    private static final class Fake implements EmbeddingModel {
        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                .map(s -> Embedding.from(new float[]{s.text().length(), 1f, 0f}))
                .toList());
        }
        @Override public int dimension() { return 3; }
        @Override public String modelName() { return "fake-embedder"; }
    }

    @Test @DisplayName("the provider reports the LangChain4j model's vector, dimension and name")
    void bridgesEmbedDimensionAndName() {
        EmbeddingProvider provider = EmbeddingProvider.of(new Fake());

        assertThat(provider.embed("hello")).containsExactly(5f, 1f, 0f);
        assertThat(provider.dimensions()).isEqualTo(3);
        assertThat(provider.modelId()).isEqualTo("fake-embedder");
    }

    @Test @DisplayName("it can back a SemanticCache directly")
    void backsASemanticCache() {
        var cache = SemanticCache.inMemory(EmbeddingProvider.of(new Fake())).build();

        cache.store("ns", "how do i reset my password", "use the link");

        assertThat(cache.lookup("ns", "how do i reset my password")).isPresent();
    }
}
