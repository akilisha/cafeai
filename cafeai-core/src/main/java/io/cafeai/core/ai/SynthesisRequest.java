package io.cafeai.core.ai;

/**
 * A fluent builder for a text-to-speech synthesis call.
 *
 * <p>Obtained via {@code app.synthesise(text)}.
 * Executes when {@link #call()} is invoked.
 *
 * <pre>{@code
 *   // Basic synthesis
 *   byte[] audio = app.synthesise("Hello, welcome to today's lesson.")
 *       .call()
 *       .audioBytes();
 *
 *   // With a named TTS provider
 *   byte[] audio = app.synthesise("Please hold while we process your request.")
 *       .provider("voice")
 *       .call()
 *       .audioBytes();
 * }</pre>
 *
 * <h2>Pipeline differences vs {@code app.audio()}</h2>
 * <ul>
 *   <li>{@code app.audio()} — binary audio IN, text OUT (transcription / analysis)</li>
 *   <li>{@code app.synthesise()} — text IN, binary audio OUT (speech synthesis)</li>
 * </ul>
 *
 * <h2>Supported providers</h2>
 * <ul>
 *   <li>{@code OpenAI.tts()} — OpenAI TTS via {@code /v1/audio/speech}</li>
 *   <li>{@code OpenAI.tts(voice, format)} — with specific voice and format</li>
 * </ul>
 *
 * @see SynthesisResponse
 */
public final class SynthesisRequest {

    private final String text;
    private String providerName;
    private final SynthesisExecutor executor;

    /** Package-private — constructed by CafeAIApp.synthesise() */
    public SynthesisRequest(String text, SynthesisExecutor executor) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Synthesis text must not be null or blank");
        this.text     = text;
        this.executor = executor;
    }

    /**
     * Routes this synthesis call to a specific named provider registered via
     * {@code app.ai(name, provider)}. If not specified, the default provider is used.
     *
     * <pre>{@code
     *   app.synthesise("Hello").provider("voice").call();
     * }</pre>
     */
    public SynthesisRequest provider(String providerName) {
        this.providerName = providerName;
        return this;
    }

    /** Executes the synthesis call synchronously and returns the response. */
    public SynthesisResponse call() {
        return executor.execute(this);
    }

    /** Package-private accessors for the executor */
    public String text()         { return text; }
    public String providerName() { return providerName; }

    /**
     * Internal executor interface — implemented by CafeAIApp.
     * Decouples SynthesisRequest from CafeAIApp to avoid circular deps.
     */
    @FunctionalInterface
    public interface SynthesisExecutor {
        SynthesisResponse execute(SynthesisRequest request);
    }

    /**
     * Thrown when {@code app.synthesise()} is called but the registered provider
     * does not support text-to-speech synthesis.
     */
    public static final class TtsNotSupportedException extends RuntimeException {
        public TtsNotSupportedException(String message) {
            super(message);
        }
    }
}
