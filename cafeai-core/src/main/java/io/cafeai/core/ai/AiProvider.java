package io.cafeai.core.ai;

/**
 * Abstraction over an LLM provider and model.
 *
 * <p>CafeAI is provider-agnostic. Swap models without changing
 * your application logic. The provider is registered once at
 * bootstrap and injected throughout the pipeline.
 *
 * <pre>{@code
 *   app.ai(OpenAI.gpt4o());
 *   app.ai(Anthropic.claude35Sonnet());
 *   app.ai(Ollama.llama3());           // local, on-prem, no data leaves your infra
 *   app.ai(Ollama.mistral());
 * }</pre>
 */
public interface AiProvider {

    String name();

    String modelId();

    ProviderType type();

    enum ProviderType {
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        AZURE_OPENAI,
        GOOGLE_VERTEX,
        CUSTOM
    }
}
