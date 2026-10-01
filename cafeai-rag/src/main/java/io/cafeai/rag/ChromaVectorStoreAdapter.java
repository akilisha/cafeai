package io.cafeai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import io.cafeai.core.rag.RagDocument;
import io.cafeai.core.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Adapts LangChain4j's {@link ChromaEmbeddingStore} to CafeAI's {@link VectorStore}
 * interface.
 *
 * <p>Package-private — obtained via {@link Chroma#local()} or
 * {@link Chroma#connect(String)}.
 *
 * <p><strong>Chroma version compatibility:</strong> LangChain4j's
 * {@code ChromaEmbeddingStore} is compatible with Chroma 0.5.x only.
 * Chroma 0.6+ changed its API and is not yet supported. Use the
 * {@code chromadb/chroma:0.5.23} Docker image (see {@link Chroma}).
 *
 * <p><strong>Collection naming:</strong> Each adapter instance owns one
 * Chroma collection. The collection is created if it does not exist.
 * Use a stable, application-specific name so documents persist across
 * restarts.
 *
 * <p><strong>Ids:</strong> each chunk is stored under its CafeAI chunk id, so
 * writing the same id again replaces the chunk instead of adding a second copy.
 * {@link #exists} and {@link #count} call Chroma's v1 REST API directly, since
 * {@code ChromaEmbeddingStore} has no lookup by id and no count.
 */
final class ChromaVectorStoreAdapter implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStoreAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChromaEmbeddingStore store;
    private final String baseUrl;
    private final String collectionName;
    // HTTP/1.1: Chroma's server loses a POST body sent with the default h2c upgrade attempt.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private volatile String collectionId;

    ChromaVectorStoreAdapter(String baseUrl, String collectionName) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = collectionName;
        this.store = ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collectionName)
                .build();
        log.info("ChromaVectorStoreAdapter: connected to {} collection='{}'",
                baseUrl, collectionName);
    }

    @Override
    public void upsert(String id, String content, float[] embedding,
                       String sourceId, int chunkIndex) {
        Metadata metadata = Metadata.from(Map.of(
                "cafeaiId",   id,
                "sourceId",   sourceId,
                "chunkIndex", String.valueOf(chunkIndex)));

        // Chroma's add ignores an id it already holds, so remove first to replace.
        store.removeAll(List.of(id));
        store.addAll(
                List.of(id),
                List.of(Embedding.from(embedding)),
                List.of(TextSegment.from(content, metadata)));
    }

    @Override
    public List<RagDocument> search(float[] queryEmbedding, int topK) {
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryEmbedding))
                .maxResults(topK)
                .minScore(0.0)
                .build();

        return store.search(request).matches().stream()
                .map(match -> {
                    var segment  = match.embedded();
                    var meta     = segment != null ? segment.metadata() : null;
                    String sourceId  = meta != null ? meta.getString("sourceId")   : collectionName;
                    String chunkStr  = meta != null ? meta.getString("chunkIndex") : "-1";
                    String content   = segment != null ? segment.text() : "";
                    return new RagDocument(content, sourceId, match.score(), parseChunkIndex(chunkStr));
                })
                .toList();
    }

    @Override
    public boolean exists(String id) {
        String body = "{\"ids\":" + toJson(List.of(id)) + ",\"include\":[]}";
        JsonNode ids = send(HttpRequest.newBuilder(collectionUri("/get"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()).path("ids");
        return ids.isArray() && !ids.isEmpty();
    }

    @Override
    public void deleteBySource(String sourceId) {
        store.removeAll(metadataKey("sourceId").isEqualTo(sourceId));
    }

    @Override
    public long count() {
        return send(HttpRequest.newBuilder(collectionUri("/count")).GET().build()).asLong();
    }

    private URI collectionUri(String suffix) {
        String id = collectionId;
        if (id == null) {
            String name = URLEncoder.encode(collectionName, StandardCharsets.UTF_8);
            id = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/collections/" + name))
                    .GET().build()).path("id").asText();
            collectionId = id;
        }
        return URI.create(baseUrl + "/api/v1/collections/" + id + suffix);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Chroma " + request.method() + " " + request.uri()
                        + " returned " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Chroma request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Chroma: " + request.uri(), e);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int parseChunkIndex(String value) {
        if (value == null) return -1;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return -1; }
    }
}
