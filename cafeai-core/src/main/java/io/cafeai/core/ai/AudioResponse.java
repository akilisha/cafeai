package io.cafeai.core.ai;

/**
 * The result of a single audio LLM call.
 *
 * <p>Obtained by calling {@link AudioRequest#call()}.
 *
 * <pre>{@code
 *   AudioResponse response = app.audio(
 *       "Transcribe this call.", audioBytes, "audio/wav").call();
 *
 *   String transcript = response.text();         // the transcript or model reply
 *   String model      = response.modelId();      // which model answered
 *   int    tokens     = response.totalTokens();  // total tokens consumed
 * }</pre>
 *
 * <h2>Differences from {@link PromptResponse}</h2>
 * <ul>
 *   <li>{@link #ragDocuments()} is always empty — RAG is not applicable to audio calls</li>
 *   <li>{@link #fromCache()} is always {@code false} — audio semantic cache not yet implemented</li>
 *   <li>Token counts reflect the audio encoding cost, which is typically higher
 *       than equivalent text prompts</li>
 * </ul>
 *
 * <h2>Differences from {@link VisionResponse}</h2>
 * <ul>
 *   <li>Structurally identical — the same builder pattern, the same fields</li>
 *   <li>The modality is different: {@code text()} may contain a verbatim transcript,
 *       a structured extraction, or a model response depending on the prompt</li>
 *   <li>{@link #audioBytes()} carries synthesised audio when the provider returns
 *       audio output (e.g. audio-to-audio models). {@code null} for standard
 *       transcription/analysis calls.</li>
 * </ul>
 *
 * <h2>Differences from {@link SynthesisResponse}</h2>
 * <ul>
 *   <li>{@link SynthesisResponse} is purpose-built for TTS — text in, audio bytes out</li>
 *   <li>{@link AudioResponse} is for audio analysis — audio in, text (or audio) out</li>
 *   <li>Use {@link #hasSpeech()} to check whether audio bytes are present</li>
 * </ul>
 */
public final class AudioResponse {

    private final String text;
    private final int    promptTokens;
    private final int    outputTokens;
    private final String modelId;
    private final byte[] audioBytes;

    private AudioResponse(Builder b) {
        this.text         = b.text;
        this.promptTokens = b.promptTokens;
        this.outputTokens = b.outputTokens;
        this.modelId      = b.modelId;
        this.audioBytes   = b.audioBytes;
    }

    /** The model's text response — transcript, summary, or extraction result. */
    public String text()        { return text; }

    /** Number of tokens in the prompt (text instruction + encoded audio). */
    public int promptTokens()   { return promptTokens; }

    /** Number of tokens in the model's response. */
    public int outputTokens()   { return outputTokens; }

    /** Total tokens consumed (prompt + output). */
    public int totalTokens()    { return promptTokens + outputTokens; }

    /** The model ID that generated this response. */
    public String modelId()     { return modelId; }

    /**
     * The synthesised audio bytes, if the provider returned audio output.
     *
     * <p>Present when using audio-to-audio models (e.g. {@code gpt-4o-audio-preview}
     * with audio output requested). {@code null} for standard transcription and
     * analysis calls via Whisper.
     *
     * <p>Use {@link #hasSpeech()} to check before calling this method.
     */
    public byte[] audioBytes()  { return audioBytes; }

    /**
     * Returns {@code true} if this response contains synthesised audio bytes.
     * Use before calling {@link #audioBytes()} to avoid null checks.
     *
     * <pre>{@code
     *   if (response.hasSpeech()) {
     *       Files.write(Path.of("output.wav"), response.audioBytes());
     *   }
     * }</pre>
     */
    public boolean hasSpeech()  { return audioBytes != null && audioBytes.length > 0; }

    /**
     * Always {@code false} — audio semantic cache is not yet implemented.
     * Reserved for future use.
     */
    public boolean fromCache()  { return false; }

    /**
     * Always empty — RAG retrieval is not applicable to audio calls.
     * Audio bytes cannot be embedded and compared to text embeddings.
     */
    public java.util.List<Object> ragDocuments() { return java.util.List.of(); }

    /** Shorthand — delegates to {@link #text()}. */
    @Override
    public String toString() { return text; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String text         = "";
        private int    promptTokens = 0;
        private int    outputTokens = 0;
        private String modelId      = "unknown";
        private byte[] audioBytes   = null;

        public Builder text(String t)         { this.text         = t; return this; }
        public Builder promptTokens(int n)    { this.promptTokens = n; return this; }
        public Builder outputTokens(int n)    { this.outputTokens = n; return this; }
        public Builder modelId(String m)      { this.modelId      = m; return this; }
        public Builder audioBytes(byte[] b)   { this.audioBytes   = b; return this; }

        public AudioResponse build()          { return new AudioResponse(this); }
    }
}
