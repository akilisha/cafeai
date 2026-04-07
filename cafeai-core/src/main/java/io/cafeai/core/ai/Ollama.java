package io.cafeai.core.ai;

import io.cafeai.core.internal.LangchainBridge;

/**
 * Factory for Ollama local model providers.
 *
 * <p>Ollama runs models entirely on-prem. No data leaves your infrastructure.
 * Critical for enterprise Java shops with data sovereignty requirements.
 *
 * <pre>{@code
 *   app.ai(Ollama.llama3());
 *   app.ai(Ollama.mistral());
 *   app.ai(Ollama.of("my-fine-tuned-model"));
 *   app.ai(Ollama.at("http://gpu-server:11434").model("llama3"));
 * }</pre>
 */
public final class Ollama {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private Ollama() {}

    public static AiProvider llama3()   { return of("llama3"); }

    /**
     * LLaVA — the canonical local vision model.
     * Supports image input. Pull with: {@code ollama pull llava}
     *
     * <pre>{@code
     *   app.ai(Ollama.llava());
     *   VisionResponse r = app.vision("Describe this image.", bytes, "image/jpeg").call();
     * }</pre>
     */
    public static AiProvider llava() {
        return new OllamaVisionProvider("llava", DEFAULT_BASE_URL);
    }
    public static AiProvider mistral()  { return of("mistral"); }
    public static AiProvider phi3()     { return of("phi3"); }
    public static AiProvider gemma2()   { return of("gemma2"); }

    /** Any Ollama model by its model name. Uses localhost:11434. */
    public static AiProvider of(String modelId) {
        return new OllamaProvider(modelId, DEFAULT_BASE_URL);
    }

    /** Creates a builder targeting a remote Ollama instance. */
    public static OllamaBuilder at(String baseUrl) {
        return new OllamaBuilder(baseUrl);
    }

    public record OllamaBuilder(String baseUrl) {
        public AiProvider model(String modelId) {
            return new OllamaProvider(modelId, baseUrl);
        }
    }

    /**
     * Implements {@link LangchainBridge.OllamaProviderAccess} so the bridge
     * can read the base URL via pattern matching without exposing it on
     * the public {@link AiProvider} interface.
     */
    private record OllamaProvider(String modelId, String baseUrl)
            implements AiProvider, LangchainBridge.OllamaProviderAccess {
        @Override public String name()       { return "ollama"; }
        @Override public ProviderType type() { return ProviderType.OLLAMA; }
    }

    /** Vision-capable Ollama provider (llava and similar multimodal models). */
    private record OllamaVisionProvider(String modelId, String baseUrl)
            implements AiProvider, LangchainBridge.OllamaProviderAccess {
        @Override public String       name()          { return "ollama"; }
        @Override public ProviderType type()          { return ProviderType.OLLAMA; }
        @Override public boolean      supportsVision() { return true; }
    }
}
