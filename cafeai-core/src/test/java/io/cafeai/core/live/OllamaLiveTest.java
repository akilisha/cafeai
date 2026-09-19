package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Ollama;
import org.junit.jupiter.api.DisplayName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ollama on this machine (or wherever {@code OLLAMA_LIVE_URL} points). Needs no key, only a running
 * server with the model pulled: {@code ollama pull llama3.2}.
 *
 * <ul>
 *   <li>{@code OLLAMA_LIVE_URL} — default {@code http://localhost:11434}.</li>
 *   <li>{@code OLLAMA_LIVE_MODEL} — default {@code llama3.2}.</li>
 *   <li>{@code OLLAMA_LIVE_VISION_MODEL} — enables the vision test (for example {@code llava}).</li>
 * </ul>
 */
@DisplayName("Ollama — live")
class OllamaLiveTest extends ProviderLiveSuite {

    private final String url   = env("OLLAMA_LIVE_URL", "http://localhost:11434");
    private final String model = env("OLLAMA_LIVE_MODEL", "llama3.2");

    @Override String label() { return "Ollama"; }

    @Override
    String skipReason() {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var response = client.send(HttpRequest.newBuilder(URI.create(url + "/api/tags"))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return url + " answered " + response.statusCode();
            String body = response.body();
            if (!body.contains("\"" + model + "\"") && !body.contains("\"" + model + ":")) {
                return "model '" + model + "' is not pulled (run: ollama pull " + model + ")";
            }
            return null;
        } catch (Exception e) {
            return "nothing is listening at " + url + " (" + e.getClass().getSimpleName() + ")";
        }
    }

    @Override AiProvider provider() { return Ollama.at(url).model(model); }

    @Override
    AiProvider visionProvider() {
        String vision = System.getenv("OLLAMA_LIVE_VISION_MODEL");
        return vision == null || vision.isBlank() ? null : Ollama.at(url).visionModel(vision);
    }
}
