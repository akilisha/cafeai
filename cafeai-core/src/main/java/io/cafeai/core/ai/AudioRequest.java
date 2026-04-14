package io.cafeai.core.ai;

/**
 * A fluent builder for a single audio LLM call with binary audio content.
 *
 * <p>Obtained via {@code app.audio(prompt, content, mimeType)}.
 * Executes when {@link #call()} is invoked.
 *
 * <pre>{@code
 *   // Simple transcription
 *   AudioResponse response = app.audio(
 *       "Transcribe this customer support call verbatim.",
 *       audioBytes, "audio/wav").call();
 *
 *   // Transcription with session memory
 *   AudioResponse response = app.audio(
 *       "Transcribe and summarise the key points of this call.",
 *       audioBytes, "audio/mp3")
 *       .session(req.header("X-Session-Id"))
 *       .call();
 *
 *   // System prompt override for this call
 *   AudioResponse response = app.audio(
 *       "Identify the speaker's primary concern.", audioBytes, "audio/wav")
 *       .system("You are a call quality analysis assistant.")
 *       .call();
 *
 *   // Structured output
 *   CallSummary summary = app.audio(
 *       "Extract action items and decisions from this meeting.",
 *       audioBytes, "audio/mp3")
 *       .returning(CallSummary.class)
 *       .call(CallSummary.class);
 * }</pre>
 *
 * <h2>Pipeline differences vs {@code app.prompt()}</h2>
 * <ul>
 *   <li>RAG retrieval is skipped — audio bytes cannot be embedded</li>
 *   <li>PRE_LLM and POST_LLM guardrails apply to the text prompt and transcript</li>
 *   <li>Session memory stores the text prompt and response only (not audio bytes)</li>
 *   <li>The registered provider must declare {@code supportsAudio() = true}</li>
 * </ul>
 *
 * <h2>Supported MIME types</h2>
 * <ul>
 *   <li>{@code audio/wav}, {@code audio/wave}</li>
 *   <li>{@code audio/mp3}, {@code audio/mpeg}</li>
 *   <li>{@code audio/ogg}</li>
 *   <li>{@code audio/m4a}</li>
 *   <li>{@code audio/flac}</li>
 * </ul>
 *
 * <h2>Pipeline differences vs {@code app.vision()}</h2>
 * <ul>
 *   <li>Audio transcription may be a two-step call internally:
 *       audio → text transcript → optional further prompt</li>
 *   <li>{@code stream()} is not meaningful for Whisper-style transcription
 *       (which produces complete output only) but applies to gpt-4o audio</li>
 * </ul>
 *
 * @see AudioResponse
 */
public final class AudioRequest {

    private final String prompt;
    private final byte[] content;
    private final String mimeType;
    private String sessionId;
    private String providerName;
    private String systemOverride;
    private Class<?> returningType;
    private String schemaHint;
    private io.cafeai.core.routing.Request httpRequest;
    private final AudioExecutor executor;

    /** Package-private — constructed by CafeAIApp.audio() */
    public AudioRequest(String prompt, byte[] content, String mimeType,
                        AudioExecutor executor) {
        if (prompt == null || prompt.isBlank())
            throw new IllegalArgumentException("Audio prompt must not be null or blank");
        if (content == null || content.length == 0)
            throw new IllegalArgumentException("Audio content must not be null or empty");
        if (mimeType == null || mimeType.isBlank())
            throw new IllegalArgumentException("Audio mimeType must not be null or blank");

        this.prompt   = prompt;
        this.content  = content;
        this.mimeType = mimeType;
        this.executor = executor;
    }

    /**
     * Associates this audio call with a session for conversation memory.
     * The session's prior text messages are prepended to the LLM context.
     * The text prompt and transcript are stored back into the session after the call.
     * The binary audio content is never stored in session memory.
     */
    public AudioRequest session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Routes this call to a specific named provider registered via
     * {@code app.ai(name, provider)}. Falls back to the default
     * provider if not specified.
     *
     * <pre>{@code
     *   app.audio(...).provider("tutor").call();
     * }</pre>
     */
    public AudioRequest provider(String providerName) {
        this.providerName = providerName;
        return this;
    }

    /**
     * Overrides the application-level system prompt for this call only.
     * Does not affect {@code app.system()} for other requests.
     */
    public AudioRequest system(String systemPrompt) {
        this.systemOverride = systemPrompt;
        return this;
    }

    /**
     * Associates this audio call with the current HTTP request.
     * Enables POST_LLM guardrails in the HTTP middleware chain.
     */
    public AudioRequest request(io.cafeai.core.routing.Request httpRequest) {
        this.httpRequest = httpRequest;
        return this;
    }

    /**
     * Declares the expected return type for structured output.
     *
     * <pre>{@code
     *   CallSummary summary = app.audio(prompt, audioBytes, "audio/wav")
     *       .returning(CallSummary.class)
     *       .call(CallSummary.class);
     * }</pre>
     */
    public <T> AudioRequest returning(Class<T> type) {
        this.returningType = type;
        return this;
    }

    /** Executes the audio call synchronously and returns the response. */
    public AudioResponse call() {
        return executor.execute(this);
    }

    /**
     * Executes the audio call and deserialises the response to the target type.
     *
     * @throws ResponseDeserializer.StructuredOutputException if deserialisation fails
     */
    public <T> T call(Class<T> type) {
        this.returningType = type;
        String hint     = SchemaHintBuilder.build(type);
        this.schemaHint = SchemaHintBuilder.instruction(type, hint);
        AudioResponse response = executor.execute(this);
        return ResponseDeserializer.deserialise(response.text(), type);
    }

    /** Package-private accessors for the executor */
    public String   prompt()         { return prompt; }
    public byte[]   content()        { return content; }
    public String   mimeType()       { return mimeType; }
    public String   sessionId()      { return sessionId; }
    public String   providerName()  { return providerName; }
    public String   systemOverride() { return systemOverride; }
    public Class<?> returningType()  { return returningType; }
    public String   schemaHint()     { return schemaHint; }
    public io.cafeai.core.routing.Request httpRequest() { return httpRequest; }

    /**
     * Internal executor interface — implemented by CafeAIApp.
     * Decouples AudioRequest from CafeAIApp to avoid circular deps.
     */
    @FunctionalInterface
    public interface AudioExecutor {
        AudioResponse execute(AudioRequest request);
    }

    /**
     * Thrown when {@code app.audio()} is called but the registered provider
     * does not support audio input.
     */
    public static final class AudioNotSupportedException extends RuntimeException {
        public AudioNotSupportedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the MIME type of the audio content is not supported
     * by the audio pipeline.
     */
    public static final class UnsupportedAudioFormatException extends RuntimeException {
        public UnsupportedAudioFormatException(String message) {
            super(message);
        }
    }
}
