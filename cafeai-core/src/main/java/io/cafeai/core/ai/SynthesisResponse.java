package io.cafeai.core.ai;

/**
 * The result of a text-to-speech synthesis call.
 *
 * <p>Obtained by calling {@link SynthesisRequest#call()}.
 *
 * <pre>{@code
 *   SynthesisResponse response = app.synthesise("Hello, welcome.").call();
 *
 *   byte[] audio      = response.audioBytes();   // the synthesised audio
 *   String format     = response.format();       // "mp3", "wav", etc.
 *   String model      = response.modelId();      // which model synthesised
 *   int    characters = response.characters();   // input text length
 * }</pre>
 *
 * <h2>Differences from {@link AudioResponse}</h2>
 * <ul>
 *   <li>{@link AudioResponse} carries the TEXT output from an audio input
 *       (transcription, analysis)</li>
 *   <li>{@link SynthesisResponse} carries the AUDIO BYTES output from a text
 *       input (speech synthesis)</li>
 * </ul>
 */
public final class SynthesisResponse {

    private final byte[] audioBytes;
    private final String format;
    private final String modelId;
    private final int    characters;
    private final long   latencyMs;

    private SynthesisResponse(Builder b) {
        this.audioBytes  = b.audioBytes;
        this.format      = b.format;
        this.modelId     = b.modelId;
        this.characters  = b.characters;
        this.latencyMs   = b.latencyMs;
    }

    /**
     * The synthesised audio as raw bytes.
     * Write to a file or stream to a player.
     *
     * <pre>{@code
     *   Files.write(Path.of("output.mp3"), response.audioBytes());
     * }</pre>
     */
    public byte[] audioBytes()  { return audioBytes; }

    /**
     * The audio format — matches the format requested from the provider.
     * Typical values: {@code "mp3"}, {@code "wav"}, {@code "opus"}, {@code "flac"}.
     */
    public String format()      { return format; }

    /** The model ID that performed the synthesis. */
    public String modelId()     { return modelId; }

    /** Number of characters in the input text. */
    public int    characters()  { return characters; }

    /** Synthesis latency in milliseconds. */
    public long   latencyMs()   { return latencyMs; }

    /** Whether audio bytes are present and non-empty. */
    public boolean hasAudio()   { return audioBytes != null && audioBytes.length > 0; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private byte[] audioBytes  = new byte[0];
        private String format      = "mp3";
        private String modelId     = "unknown";
        private int    characters  = 0;
        private long   latencyMs   = 0;

        public Builder audioBytes(byte[] b)  { this.audioBytes  = b; return this; }
        public Builder format(String f)      { this.format      = f; return this; }
        public Builder modelId(String m)     { this.modelId     = m; return this; }
        public Builder characters(int c)     { this.characters  = c; return this; }
        public Builder latencyMs(long l)     { this.latencyMs   = l; return this; }

        public SynthesisResponse build()     { return new SynthesisResponse(this); }
    }
}
