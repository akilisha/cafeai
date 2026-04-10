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
 *   app.ai(Ollama.llama3());   // local -- no data leaves your infra
 * }</pre>
 */
public interface AiProvider {

    /** Human-readable provider name. Example: {@code "openai"} */
    String name();

    /** Model identifier. Example: {@code "gpt-4o"} */
    String modelId();

    /** The provider type. */
    ProviderType type();

    /**
     * Returns {@code true} if this provider supports multimodal (vision) input.
     *
     * <p>Providers that support vision: {@code OpenAI.gpt4o()}, {@code Ollama.llava()}.
     * Providers that do not: {@code OpenAI.gpt4oMini()}, {@code Ollama.llama3()}.
     *
     * <p>Defaults to {@code false}. Override in vision-capable provider implementations.
     */
    default boolean supportsVision() { return false; }

    /**
     * Returns {@code true} if this provider supports audio input via
     * {@code app.audio()}. Providers that do: {@code OpenAI.gpt4o()},
     * {@code OpenAI.whisper()}. Providers that do not: {@code OpenAI.gpt4oMini()},
     * {@code Anthropic.claude35Sonnet()}, {@code Ollama.llama3()}.
     *
     * <p>Defaults to {@code false}. Override in audio-capable provider implementations.
     */
    default boolean supportsAudio() { return false; }

    enum ProviderType {
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        AZURE_OPENAI,
        GOOGLE_VERTEX,
        CUSTOM
    }
}
