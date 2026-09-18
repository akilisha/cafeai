package io.cafeai.core.ai;

import java.time.Duration;

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
     * Sampling temperature, or {@code null} to leave it to the model's default.
     * Set with {@link #withTemperature(double)}.
     */
    default Double temperature() { return null; }

    /**
     * Cap on generated tokens, or {@code null} to leave it to the model's default.
     * Set with {@link #withMaxTokens(int)}.
     */
    default Integer maxTokens() { return null; }

    /**
     * How long a single chat call may wait for the model, or {@code null} to use
     * the {@code cafeai.chat.timeout} setting (60 seconds by default). Set with
     * {@link #withTimeout(Duration)}.
     */
    default Duration timeout() { return null; }

    /**
     * A copy of this provider that samples at the given temperature. Providers are
     * immutable, so the original is unchanged:
     *
     * <pre>{@code
     *   app.ai(Anthropic.of("claude-sonnet-4-5").withTemperature(0));   // deterministic classifier
     * }</pre>
     *
     * @throws UnsupportedOperationException if this provider does not support it
     *         (the default — the built-in providers all override it)
     */
    default AiProvider withTemperature(double temperature) {
        throw new UnsupportedOperationException(
            "Provider '" + name() + "' does not support withTemperature");
    }

    /**
     * A copy of this provider that caps generation at {@code maxTokens} tokens.
     * For a reasoning model the cap includes its thinking, so a low value can
     * leave nothing for the answer.
     *
     * @throws UnsupportedOperationException if this provider does not support it
     *         (the default — the built-in providers all override it)
     */
    default AiProvider withMaxTokens(int maxTokens) {
        throw new UnsupportedOperationException(
            "Provider '" + name() + "' does not support withMaxTokens");
    }

    /**
     * A copy of this provider that waits up to {@code timeout} for the model. This
     * is per model because it should be: a fast classifier and a reasoning model
     * that thinks for minutes before its first token do not share a sensible limit.
     * Overrides {@code cafeai.chat.timeout} for this provider only.
     *
     * <pre>{@code
     *   app.ai(Nvidia.of("moonshotai/kimi-k3").withTimeout(Duration.ofMinutes(10)));
     * }</pre>
     *
     * <p>For a streamed call the limit covers the wait for the response to start,
     * not the time between tokens once it has.
     *
     * @throws UnsupportedOperationException if this provider does not support it
     *         (the default; also {@code Jlama}, which runs in-process with no
     *         network call to time out)
     */
    default AiProvider withTimeout(Duration timeout) {
        throw new UnsupportedOperationException(
            "Provider '" + name() + "' does not support withTimeout");
    }

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
        CUSTOM
    }
}
