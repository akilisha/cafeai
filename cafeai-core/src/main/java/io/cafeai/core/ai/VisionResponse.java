package io.cafeai.core.ai;

/**
 * The result of a single multimodal LLM vision call.
 *
 * <p>Obtained by calling {@link VisionRequest#call()}.
 *
 * <pre>{@code
 *   VisionResponse response = app.vision(
 *       "Is this an invoice?", pdfBytes, "application/pdf").call();
 *
 *   String answer  = response.text();          // the model's reply
 *   String model   = response.modelId();       // which model answered
 *   int    tokens  = response.totalTokens();   // total tokens consumed
 * }</pre>
 *
 * <h2>Differences from {@link PromptResponse}</h2>
 * <ul>
 *   <li>{@link #ragDocuments()} is always empty — RAG is not applicable to vision calls</li>
 *   <li>{@link #fromCache()} is always {@code false} — vision semantic cache not yet implemented</li>
 *   <li>Token counts include both the text prompt and the binary content encoding</li>
 * </ul>
 */
public final class VisionResponse {

    private final String text;
    private final int    promptTokens;
    private final int    outputTokens;
    private final String modelId;

    private VisionResponse(Builder b) {
        this.text         = b.text;
        this.promptTokens = b.promptTokens;
        this.outputTokens = b.outputTokens;
        this.modelId      = b.modelId;
    }

    /** The model's text response. */
    public String text()        { return text; }

    /** Number of tokens in the prompt sent to the model (text + encoded content). */
    public int promptTokens()   { return promptTokens; }

    /** Number of tokens in the model's response. */
    public int outputTokens()   { return outputTokens; }

    /** Total tokens consumed (prompt + output). */
    public int totalTokens()    { return promptTokens + outputTokens; }

    /** The model ID that generated this response. */
    public String modelId()     { return modelId; }

    /**
     * Always {@code false} — vision semantic cache is not yet implemented.
     * Reserved for future use.
     */
    public boolean fromCache()  { return false; }

    /**
     * Always empty — RAG retrieval is not applicable to vision calls.
     * Binary content cannot be embedded and compared to text embeddings.
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

        public VisionResponse build()         { return new VisionResponse(this); }
    }
}
