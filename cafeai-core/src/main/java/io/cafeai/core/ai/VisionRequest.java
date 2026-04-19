package io.cafeai.core.ai;

import java.util.function.Consumer;

/**
 * A fluent builder for a single multimodal LLM call with binary content.
 *
 * <p>Obtained via {@code app.vision(prompt, content, mimeType)}.
 * Executes when {@link #call()} is invoked.
 *
 * <pre>{@code
 *   // Classify a PDF
 *   VisionResponse response = app.vision(
 *       "Is this document an invoice? Reply YES or NO.",
 *       pdfBytes, "application/pdf").call();
 *
 *   // Describe an image with session memory
 *   VisionResponse response = app.vision(
 *       "What type of damage is visible in this photo?",
 *       imageBytes, "image/jpeg")
 *       .session(req.header("X-Session-Id"))
 *       .call();
 *
 *   // System prompt override for this call
 *   VisionResponse response = app.vision(
 *       "Extract all line items as JSON.", pdfBytes, "application/pdf")
 *       .system("You are an invoice data extraction assistant.")
 *       .call();
 *
 *   // Structured output
 *   InvoiceData invoice = app.vision(
 *       "Extract invoice fields.", pdfBytes, "application/pdf")
 *       .returning(InvoiceData.class)
 *       .call(InvoiceData.class);
 * }</pre>
 *
 * <h2>Pipeline differences vs {@code app.prompt()}</h2>
 * <ul>
 *   <li>RAG retrieval is skipped — binary content cannot be embedded</li>
 *   <li>PRE_LLM and POST_LLM guardrails apply to the text prompt and response</li>
 *   <li>Session memory stores the text prompt and response (not the binary content)</li>
 *   <li>The registered provider must support multimodal input</li>
 * </ul>
 *
 * @see VisionResponse
 */
public final class VisionRequest {

    private final String prompt;
    private final byte[] content;
    private final String mimeType;
    private String sessionId;
    private String providerName;
    private String systemOverride;
    private Class<?> returningType;
    private String schemaHint;
    private io.cafeai.core.routing.Request httpRequest;
    private final VisionExecutor executor;
    private VisionStreamExecutor streamExecutor;

    /** Package-private — constructed by CafeAIApp.vision() */
    public VisionRequest(String prompt, byte[] content, String mimeType,
                         VisionExecutor executor) {
        if (prompt == null || prompt.isBlank())
            throw new IllegalArgumentException("Vision prompt must not be null or blank");
        if (content == null || content.length == 0)
            throw new IllegalArgumentException("Vision content must not be null or empty");
        if (mimeType == null || mimeType.isBlank())
            throw new IllegalArgumentException("Vision mimeType must not be null or blank");

        this.prompt   = prompt;
        this.content  = content;
        this.mimeType = mimeType;
        this.executor = executor;
    }

    /**
     * Associates this vision call with a session for conversation memory.
     * The session's prior text messages are prepended to the LLM context.
     * The text prompt and response are stored back into the session after the call.
     * The binary content is not stored in session memory.
     */
    public VisionRequest session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Routes this call to a specific named provider registered via
     * {@code app.ai(name, provider)}. Falls back to the default
     * provider if not specified.
     *
     * <pre>{@code
     *   app.vision(...).provider("tutor").call();
     * }</pre>
     */
    public VisionRequest provider(String providerName) {
        this.providerName = providerName;
        return this;
    }

    /**
     * Overrides the application-level system prompt for this call only.
     * Does not affect {@code app.system()} for other requests.
     */
    public VisionRequest system(String systemPrompt) {
        this.systemOverride = systemPrompt;
        return this;
    }

    /**
     * Associates this vision call with the current HTTP request.
     * Enables POST_LLM guardrails in the HTTP middleware chain.
     */
    public VisionRequest request(io.cafeai.core.routing.Request httpRequest) {
        this.httpRequest = httpRequest;
        return this;
    }

    /**
     * Declares the expected return type for structured output.
     *
     * <pre>{@code
     *   InvoiceData invoice = app.vision(prompt, pdfBytes, "application/pdf")
     *       .returning(InvoiceData.class)
     *       .call(InvoiceData.class);
     * }</pre>
     */
    public <T> VisionRequest returning(Class<T> type) {
        this.returningType = type;
        return this;
    }

    /** Executes the vision call synchronously and returns the response. */
    public VisionResponse call() {
        return executor.execute(this);
    }

    /**
     * Executes the vision call and streams tokens to the consumer as they arrive.
     *
     * <p>Use this when you want to display tokens progressively — for example,
     * streaming a classification description to the user while it is generated.
     *
     * <p>Note: streaming is not compatible with {@link #returning(Class)} structured
     * output — you need the complete JSON before parsing. Use {@link #call()} for
     * structured output.
     *
     * <pre>{@code
     *   app.vision("Classify this document.", pdf, "application/pdf")
     *       .stream(chunk -> System.out.print(chunk));
     * }</pre>
     *
     * @param onChunk called for each token chunk as it arrives
     * @throws IllegalStateException if no stream executor is registered
     */
    public void stream(Consumer<String> onChunk) {
        if (streamExecutor == null)
            throw new IllegalStateException(
                "Streaming vision is not available. " +
                "Ensure CafeAI is initialised via CafeAI.create().");
        if (onChunk == null)
            throw new IllegalArgumentException("onChunk consumer must not be null");
        streamExecutor.execute(this, onChunk);
    }

    /**
     * Executes the vision call and deserialises the response to the target type.
     *
     * @throws ResponseDeserializer.StructuredOutputException if deserialisation fails
     */
    public <T> T call(Class<T> type) {
        this.returningType = type;
        String hint     = SchemaHintBuilder.build(type);
        this.schemaHint = SchemaHintBuilder.instruction(type, hint);
        VisionResponse response = executor.execute(this);
        return ResponseDeserializer.deserialise(response.text(), type);
    }

    /** Package-private: allows CafeAIApp to inject the stream executor */
    public VisionRequest withStreamExecutor(VisionStreamExecutor se) {
        this.streamExecutor = se;
        return this;
    }

    /** Package-private accessors for the executor */
    public String  prompt()         { return prompt; }
    public byte[]  content()        { return content; }
    public String  mimeType()       { return mimeType; }
    public String  sessionId()      { return sessionId; }
    public String  providerName()   { return providerName; }
    public String  systemOverride() { return systemOverride; }
    public Class<?> returningType() { return returningType; }
    public String  schemaHint()     { return schemaHint; }
    public io.cafeai.core.routing.Request httpRequest() { return httpRequest; }

    /**
     * Internal executor interface — implemented by CafeAIApp.
     * Decouples VisionRequest from CafeAIApp to avoid circular deps.
     */
    @FunctionalInterface
    public interface VisionExecutor {
        VisionResponse execute(VisionRequest request);
    }

    /**
     * Internal executor interface for streaming vision calls.
     * Implemented by CafeAIApp.
     */
    @FunctionalInterface
    public interface VisionStreamExecutor {
        void execute(VisionRequest request, Consumer<String> onChunk);
    }

    /**
     * Thrown when {@code app.vision()} is called but the registered provider
     * does not support multimodal input.
     */
    public static final class VisionNotSupportedException extends RuntimeException {
        public VisionNotSupportedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the MIME type of the binary content is not supported
     * by the vision pipeline.
     */
    public static final class UnsupportedContentTypeException extends RuntimeException {
        public UnsupportedContentTypeException(String message) {
            super(message);
        }
    }
}
