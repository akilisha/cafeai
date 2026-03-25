package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Out-of-process Ollama LLM connection.
 *
 * <p>Probes the Ollama {@code /api/tags} endpoint, then registers the model
 * as the application's AI provider.
 *
 * <pre>{@code
 *   app.connect(Ollama.at("http://ollama:11434").model("llama3"));
 *
 *   // Fall back to OpenAI if local Ollama isn't running
 *   app.connect(Ollama.at("http://localhost:11434").model("llama3")
 *       .onUnavailable(Fallback.use(io.cafeai.core.ai.OpenAI.gpt4oMini())));
 * }</pre>
 */
public final class Ollama implements Connection {

    private static final Logger log = LoggerFactory.getLogger(Ollama.class);

    private final String baseUrl;
    private final String modelId;

    private Ollama(String baseUrl, String modelId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.modelId = modelId;
    }

    /** Creates an Ollama connection builder targeting the given base URL. */
    public static OllamaBuilder at(String baseUrl) {
        return new OllamaBuilder(baseUrl);
    }

    @Override public String name()      { return "Ollama(" + baseUrl + "/" + modelId + ")"; }
    @Override public ServiceType type() { return ServiceType.LLM; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try {
            var client   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
            var request  = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                // Verify the requested model is actually pulled
                if (response.body().contains(modelId)) {
                    return HealthStatus.reachable(name(), latency);
                }
                return HealthStatus.degraded(name(),
                    "Ollama is running but model '" + modelId + "' is not pulled. " +
                    "Run: ollama pull " + modelId);
            }
            return HealthStatus.unreachable(name(), "HTTP " + response.statusCode());
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    @Override
    public void register(CafeAI app) {
        app.ai(io.cafeai.core.ai.Ollama.at(baseUrl).model(modelId));
        log.info("Connected: {} → registered as AI provider", name());
    }

    /** Fluent builder for an Ollama connection. */
    public static final class OllamaBuilder {
        private final String baseUrl;

        OllamaBuilder(String baseUrl) { this.baseUrl = baseUrl; }

        public Ollama model(String modelId) {
            return new Ollama(baseUrl, modelId);
        }
    }
}
