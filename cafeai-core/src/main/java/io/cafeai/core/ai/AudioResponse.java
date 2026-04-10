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
 * </ul>
 */
public final class AudioResponse {

    private final String text;
    private final int    promptTokens;
    private final int    outputTokens;
    private final String modelId;

    private AudioResponse(Builder b) {
        this.text         = b.text;
        this.promptTokens = b.promptTokens;
        this.outputTokens = b.outputTokens;
        this.modelId      = b.modelId;
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

        public Builder text(String t)         { this.text         = t; return this; }
        public Builder promptTokens(int n)    { this.promptTokens = n; return this; }
        public Builder outputTokens(int n)    { this.outputTokens = n; return this; }
        public Builder modelId(String m)      { this.modelId      = m; return this; }

        public AudioResponse build()          { return new AudioResponse(this); }
    }
}
