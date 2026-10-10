package io.cafeai.core.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Claude on Vertex AI: the host for a location, and the Messages API in Vertex's form")
class VertexRewriteTest {

    @Test @DisplayName("global, multi-region and regional locations each have their own host")
    void hosts() {
        assertThat(ProviderHttp.VertexRewrite.host("global")).isEqualTo("https://aiplatform.googleapis.com");
        assertThat(ProviderHttp.VertexRewrite.host("us")).isEqualTo("https://aiplatform.us.rep.googleapis.com");
        assertThat(ProviderHttp.VertexRewrite.host("eu")).isEqualTo("https://aiplatform.eu.rep.googleapis.com");
        assertThat(ProviderHttp.VertexRewrite.host("us-east5")).isEqualTo("https://us-east5-aiplatform.googleapis.com");
    }

    @Test @DisplayName("the model moves to the URL, anthropic_version into the body, and the version header goes")
    void rewrite() throws Exception {
        String body = "{\"model\":\"claude-opus-4-5@20251101\",\"max_tokens\":10,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://aiplatform.googleapis.com/v1/messages"))
                .header("anthropic-version", "2023-06-01").header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpRequest vertex = new ProviderHttp.VertexRewrite("my-project", "global")
                .rewrite(request, body.getBytes(StandardCharsets.UTF_8));

        assertThat(vertex.uri().toString()).isEqualTo("https://aiplatform.googleapis.com/v1/projects/my-project/locations/global"
                + "/publishers/anthropic/models/claude-opus-4-5@20251101:streamRawPredict");
        assertThat(vertex.headers().firstValue("anthropic-version")).isEmpty();
        assertThat(vertex.headers().firstValue("content-type")).hasValue("application/json");
    }
}
