package io.cafeai.core.ai;

/**
 * Abstraction over an LLM provider and model.
 *
 * <p>CafeAI is provider-agnostic. Register a provider once at bootstrap
 * via {@code app.ai(provider)} and swap it without changing application logic.
 *
 * <pre>{@code
 *   app.ai(OpenAI.of("gpt-4o"));
 *   app.ai(Anthropic.of("claude-sonnet-4-5"));
 *   app.ai(Ollama.of("llama3.3"));   // local -- no data leaves your infra
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
     * Returns {@code true} if this provider may accept multimodal (vision) input.
     *
     * <p>CafeAI does not track per-model capabilities (model ids change), so this
     * is a coarse hint: the {@code OpenAI.of(...)} and {@code Anthropic.of(...)}
     * providers return {@code true} — their current chat models are broadly
     * multimodal — and the provider's API rejects the rare exception.
     * {@code Ollama.vision(...)} opts in explicitly; {@code Ollama.of(...)} /
     * {@code Jlama.of(...)} return {@code false}.
     *
     * <p>Defaults to {@code false}. Override in vision-capable provider implementations.
     */
    default boolean supportsVision() { return false; }

    /**
     * Returns {@code true} if this provider supports audio input via
     * {@code app.audio()}. Only {@code OpenAI.whisper()} declares it; everything
     * else returns {@code false} (audio input needs a dedicated endpoint/model).
     *
     * <p>Defaults to {@code false}. Override in audio-capable provider implementations.
     */
    default boolean supportsAudio() { return false; }

    /**
     * Returns {@code true} if this provider supports text-to-speech synthesis
     * via {@code app.synthesise()}. Only {@code OpenAI.tts()} declares it.
     *
     * <p>Defaults to {@code false}. Override in TTS-capable provider implementations.
     */
    default boolean supportsTts() { return false; }

    enum ProviderType {
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        JLAMA,
        AZURE_OPENAI,
        GOOGLE_VERTEX,
        CUSTOM
    }
}
