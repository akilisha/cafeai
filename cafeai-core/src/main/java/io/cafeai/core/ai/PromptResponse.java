package io.cafeai.core.ai;

/**
 * The result of a single LLM prompt call.
 *
 * <p>Obtained by calling {@link PromptRequest#call()}.
 *
 * <pre>{@code
 *   PromptResponse response = app.prompt("Classify this message: " + input).call();
 *
 *   String text     = response.text();          // the model's reply
 *   int promptToks  = response.promptTokens();  // tokens consumed by the prompt
 *   int outputToks  = response.outputTokens();  // tokens in the response
 *   int totalToks   = response.totalTokens();   // promptToks + outputToks
 *   String model    = response.modelId();       // which model answered
 *   boolean cached  = response.fromCache();     // true if semantic cache hit
 * }</pre>
 */
public final class PromptResponse {

    private final String text;
    private final int    promptTokens;
    private final int    outputTokens;
    private final String modelId;
    private final boolean fromCache;

    private PromptResponse(Builder b) {
        this.text         = b.text;
        this.promptTokens = b.promptTokens;
        this.outputTokens = b.outputTokens;
        this.modelId      = b.modelId;
        this.fromCache    = b.fromCache;
    }

    /** The model's text response. */
    public String text()         { return text; }

    /** Number of tokens in the prompt sent to the model. */
    public int promptTokens()    { return promptTokens; }

    /** Number of tokens in the model's response. */
    public int outputTokens()    { return outputTokens; }

    /** Total tokens consumed (prompt + output). */
    public int totalTokens()     { return promptTokens + outputTokens; }

    /** The model ID that generated this response. */
    public String modelId()      { return modelId; }

    /** {@code true} if this response was served from the semantic cache. */
    public boolean fromCache()   { return fromCache; }

    /** Shorthand — delegates to {@link #text()}. Makes response usable as a string. */
    @Override
    public String toString() { return text; }

    public static Builder builder()     { return new Builder(); }

    public static final class Builder {
        private String  text;
        private int     promptTokens;
        private int     outputTokens;
        private String  modelId;
        private boolean fromCache;

        public Builder text(String t)             { this.text         = t; return this; }
        public Builder promptTokens(int n)        { this.promptTokens = n; return this; }
        public Builder outputTokens(int n)        { this.outputTokens = n; return this; }
        public Builder modelId(String m)          { this.modelId      = m; return this; }
        public Builder fromCache(boolean c)       { this.fromCache    = c; return this; }

        public PromptResponse build()             { return new PromptResponse(this); }
    }
}
