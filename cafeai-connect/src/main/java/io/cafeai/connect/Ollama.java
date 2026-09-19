package io.cafeai.connect;

import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.CafeAI;
import io.cafeai.core.connect.Connection;
import io.cafeai.core.connect.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *       .onUnavailable(Fallback.use(io.cafeai.core.ai.OpenAI.of("gpt-4o-mini"))));
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

    @Override public String name()      { return "Ollama(" + Urls.redact(baseUrl) + "/" + modelId + ")"; }
    @Override public ServiceType type() { return ServiceType.LLM; }

    /** How long the startup probe waits for Ollama to list its models. Connecting gets at most 3 seconds of it. */
    public static final ConfigKey<Duration> PROBE_TIMEOUT = ConfigKey.of(
        "cafeai.connect.ollama.probe.timeout", Duration.class, Duration.ofSeconds(5),
        "How long the Ollama startup probe waits for a reply before treating Ollama as unreachable.");

    @Override
    public HealthStatus probe() {
        return probe(AppConfig.load());
    }

    HealthStatus probe(AppConfig config) {
        long start = System.currentTimeMillis();
        Duration timeout = config.get(PROBE_TIMEOUT);
        Duration connect = timeout.compareTo(Duration.ofSeconds(3)) < 0 ? timeout : Duration.ofSeconds(3);
        try (var client = HttpClient.newBuilder().connectTimeout(connect).build()) {
            var request  = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(timeout)
                .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                // Verify the requested model is actually pulled
                if (isPulled(response.body())) {
                    return HealthStatus.reachable(name(), latency);
                }
                return HealthStatus.degraded(name(),
                    "Ollama is running but model '" + modelId + "' is not pulled. " +
                    "Run: ollama pull " + modelId);
            }
            return HealthStatus.unreachable(name(), "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthStatus.unreachable(name(), "interrupted");
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    private static final Pattern MODEL_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Whether {@code /api/tags} lists this exact model. An untagged id means {@code :latest}, so
     * {@code llama3} matches {@code llama3:latest} but not {@code llama3.1:8b}.
     */
    private boolean isPulled(String tagsJson) {
        Matcher m = MODEL_NAME.matcher(tagsJson);
        while (m.find()) {
            String installed = m.group(1);
            if (installed.equals(modelId) || (!modelId.contains(":") && installed.equals(modelId + ":latest"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void register(CafeAI app) {
        app.ai(io.cafeai.core.ai.Ollama.at(baseUrl).model(modelId));
        log.info("Connected: {} -> registered as AI provider", name());
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
