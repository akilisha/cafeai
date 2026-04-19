package io.cafeai.core.ai;

import java.util.Map;

/**
 * A fluent builder for a single LLM prompt call.
 *
 * <p>Obtained via {@code app.prompt(message)} or {@code app.prompt(templateName, vars)}.
 * Executes when {@link #call()} is invoked.
 *
 * <pre>{@code
 *   // Simple prompt
 *   PromptResponse response = app.prompt("What is the capital of France?").call();
 *
 *   // Template prompt with variables
 *   PromptResponse response = app.prompt("classify",
 *       Map.of("categories", "billing, shipping", "message", userInput)).call();
 *
 *   // Session-aware (includes conversation history)
 *   PromptResponse response = app.prompt("Continue our conversation")
 *       .session(req.header("X-Session-Id"))
 *       .call();
 *
 *   // System prompt override for this call
 *   PromptResponse response = app.prompt("Translate to French: " + text)
 *       .system("You are a professional French translator.")
 *       .call();
 * }</pre>
 */
public final class PromptRequest {

    private final String message;
    private String sessionId;
    private String providerName;
    private String systemOverride;
    private io.cafeai.core.routing.Request httpRequest;
    private Class<?> returningType;
    private String schemaHint;
    private final PromptExecutor executor;

    /** Package-private -- constructed by CafeAIApp.prompt() */
    public PromptRequest(String message, PromptExecutor executor) {
        this.message  = message;
        this.executor = executor;
    }

    /**
     * Associates this prompt with a session for conversation memory.
     * The session's prior messages are prepended to the LLM context.
     * The response is automatically stored back into the session.
     */
    public PromptRequest session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Routes this call to a specific named provider registered via
     * {@code app.ai(name, provider)}. Falls back to the default
     * provider if not specified.
     *
     * <pre>{@code
     *   app.prompt(...).provider("tutor").call();
     * }</pre>
     */
    public PromptRequest provider(String providerName) {
        this.providerName = providerName;
        return this;
    }

    /**
     * Overrides the application-level system prompt for this call only.
     * Does not affect {@code app.system()} for other requests.
     */
    public PromptRequest system(String systemPrompt) {
        this.systemOverride = systemPrompt;
        return this;
    }

    /**
     * Associates this prompt call with the current HTTP request.
     *
     * <p>When set, {@code CafeAIApp} stores the LLM response text in
     * {@code Attributes.LLM_RESPONSE_TEXT} on this request after the call.
     * This enables POST_LLM guardrails in the HTTP middleware chain to
     * inspect the response after {@code next.run()} returns.
     *
     * <p>Called automatically by CafeAI's route handlers when a prompt is
     * executed within an HTTP request context.
     */
    public PromptRequest request(io.cafeai.core.routing.Request httpRequest) {
        this.httpRequest = httpRequest;
        return this;
    }

    /**
     * Declares the expected return type for structured output.
     *
     * <p>When set, {@link #call(Class)} appends a JSON schema hint to the
     * prompt and deserialises the response to the target type automatically.
     * The schema hint is generated from the class fields via reflection.
     *
     * <pre>{@code
     *   SentimentResult result = app.prompt(prompt)
     *       .returning(SentimentResult.class)
     *       .call(SentimentResult.class);
     * }</pre>
     *
     * @param type the target class — must be a Java record or POJO with public fields
     */
    public <T> PromptRequest returning(Class<T> type) {
        this.returningType = type;
        return this;
    }

    /** Executes the prompt synchronously and returns the response. */
    public PromptResponse call() {
        return executor.execute(this);
    }

    /**
     * Executes the prompt and deserialises the response to the target type.
     *
     * <p>Appends a JSON schema instruction to the prompt before calling the LLM,
     * then strips markdown fences and deserialises the response via Jackson.
     *
     * <pre>{@code
     *   SentimentResult result = app.prompt(sentimentPrompt)
     *       .returning(SentimentResult.class)
     *       .call(SentimentResult.class);
     * }</pre>
     *
     * @param type the target class — must match the type passed to {@link #returning(Class)}
     * @throws ResponseDeserializer.StructuredOutputException if deserialisation fails
     */
    public <T> T call(Class<T> type) {
        // Inject schema hint into the message before executing
        this.returningType = type;
        String hint        = SchemaHintBuilder.build(type);
        this.schemaHint    = SchemaHintBuilder.instruction(type, hint);
        PromptResponse response = executor.execute(this);
        return ResponseDeserializer.deserialise(response.text(), type);
    }

    /** Package-private accessors for the executor */
    public String message()        { return message; }
    public String sessionId()      { return sessionId; }
    public String providerName()    { return providerName; }
    public String systemOverride() { return systemOverride; }
    public io.cafeai.core.routing.Request httpRequest() { return httpRequest; }
    public Class<?> returningType()  { return returningType; }
    public String schemaHint()       { return schemaHint; }

    /**
     * Internal executor interface -- implemented by CafeAIApp.
     * Decouples PromptRequest from CafeAIApp to avoid circular deps.
     */
    @FunctionalInterface
    public interface PromptExecutor {
        PromptResponse execute(PromptRequest request);
    }
}
