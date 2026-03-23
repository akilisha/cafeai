package io.cafeai.core.ai;

/**
 * Ollama local model provider for CafeAI.
 *
 * <p>Ollama runs models entirely on-prem via llama.cpp.
 * No data leaves your infrastructure. Critical for enterprise
 * Java shops with data sovereignty requirements.
 * CafeAI's FFM API bindings can also call llama.cpp directly
 * without Ollama as the intermediary — see cafeai-tools.
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

    public static AiProvider llama3() {
        return of("llama3");
    }

    public static AiProvider mistral() {
        return of("mistral");
    }

    public static AiProvider phi3() {
        return of("phi3");
    }

    public static AiProvider gemma2() {
        return of("gemma2");
    }

    public static AiProvider of(String modelId) {
        return new OllamaProvider(modelId, DEFAULT_BASE_URL);
    }

    public static OllamaBuilder at(String baseUrl) {
        return new OllamaBuilder(baseUrl);
    }

    public record OllamaBuilder(String baseUrl) {
        public AiProvider model(String modelId) {
            return new OllamaProvider(modelId, baseUrl);
        }
    }

    private record OllamaProvider(String modelId, String baseUrl) implements AiProvider {
        @Override public String name()          { return "ollama"; }
        @Override public ProviderType type()    { return ProviderType.OLLAMA; }
    }
}
