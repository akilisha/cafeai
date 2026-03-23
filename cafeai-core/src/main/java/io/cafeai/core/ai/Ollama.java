package io.cafeai.core.ai;

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

    private record OllamaProvider(String modelId, String baseUrl) implements AiProvider {
        @Override public String name()       { return "ollama"; }
        @Override public ProviderType type() { return ProviderType.OLLAMA; }
    }
}
