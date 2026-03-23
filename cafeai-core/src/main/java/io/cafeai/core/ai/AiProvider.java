package io.cafeai.core.ai;

/**
 * Abstraction over an LLM provider and model.
 *
 * <p>CafeAI is provider-agnostic. Register a provider once at bootstrap
 * via {@code app.ai(provider)} and swap it without changing application logic.
 *
 * <pre>{@code
 *   app.ai(OpenAI.gpt4o());
 *   app.ai(Anthropic.claude35Sonnet());
 *   app.ai(Ollama.llama3());   // local — no data leaves your infra
 * }</pre>
 */
public interface AiProvider {

    /** Human-readable provider name. Example: {@code "openai"} */
    String name();

    /** Model identifier. Example: {@code "gpt-4o"} */
    String modelId();

    /** The provider type. */
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
