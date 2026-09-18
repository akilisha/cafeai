package io.cafeai.core.ai;

import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;

/**
 * Factory for Ollama local model providers.
 *
 * <p>Ollama runs models entirely on-prem — no data leaves your infrastructure.
 * Model names are whatever you have {@code ollama pull}ed, so CafeAI takes them
 * as strings rather than shipping named constants.
 *
 * <pre>{@code
 *   app.ai(Ollama.of("llama3.3"));
 *   app.ai(Ollama.vision("llava"));                          // a multimodal model
 *   app.ai(Ollama.at("http://gpu-server:11434").model("mistral"));
 * }</pre>
 */
public final class Ollama {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private Ollama() {}

    /** An Ollama provider for {@code modelId} on {@code localhost:11434}. */
    public static AiProvider of(String modelId) {
        return new OllamaProvider(modelId, DEFAULT_BASE_URL, null, null, null);
    }

    /**
     * A multimodal Ollama provider on {@code localhost:11434} — declares vision
     * support so {@code app.vision(...)} accepts it. Use for {@code llava} and
     * other image-capable models.
     */
    public static AiProvider vision(String modelId) {
        return new OllamaVisionProvider(modelId, DEFAULT_BASE_URL, null, null, null);
    }

    /** A builder targeting a remote Ollama instance. */
    public static OllamaBuilder at(String baseUrl) {
        return new OllamaBuilder(baseUrl);
    }

    public record OllamaBuilder(String baseUrl) {
        public AiProvider model(String modelId) {
            return new OllamaProvider(modelId, baseUrl, null, null, null);
        }

        /** A multimodal model on this remote instance. */
        public AiProvider visionModel(String modelId) {
            return new OllamaVisionProvider(modelId, baseUrl, null, null, null);
        }
    }

    /**
     * Implements {@link LangchainBridge.OllamaProviderAccess} so the bridge
     * can read the base URL via pattern matching without exposing it on
     * the public {@link AiProvider} interface.
     */
    private record OllamaProvider(String modelId, String baseUrl,
                                  Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.OllamaProviderAccess {
        @Override public AiProvider withTemperature(double t) { return new OllamaProvider(modelId, baseUrl, t, maxTokens, timeout); }
        @Override public AiProvider withMaxTokens(int n)      { return new OllamaProvider(modelId, baseUrl, temperature, n, timeout); }
        @Override public AiProvider withTimeout(Duration d)   { return new OllamaProvider(modelId, baseUrl, temperature, maxTokens, d); }
        @Override public String name()       { return "ollama"; }
        @Override public ProviderType type() { return ProviderType.OLLAMA; }
    }

    /** Vision-capable Ollama provider (llava and similar multimodal models). */
    private record OllamaVisionProvider(String modelId, String baseUrl,
                                        Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.OllamaProviderAccess {
        @Override public AiProvider withTemperature(double t) { return new OllamaVisionProvider(modelId, baseUrl, t, maxTokens, timeout); }
        @Override public AiProvider withMaxTokens(int n)      { return new OllamaVisionProvider(modelId, baseUrl, temperature, n, timeout); }
        @Override public AiProvider withTimeout(Duration d)   { return new OllamaVisionProvider(modelId, baseUrl, temperature, maxTokens, d); }
        @Override public String       name()          { return "ollama"; }
        @Override public ProviderType type()          { return ProviderType.OLLAMA; }
        @Override public boolean      supportsVision() { return true; }
    }
}
