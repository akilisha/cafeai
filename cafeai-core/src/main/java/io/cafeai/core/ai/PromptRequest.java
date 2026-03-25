package io.cafeai.core.ai;

import java.util.Map;

/**
 * A fluent builder for a single LLM prompt call.
 *
 * <p>Obtained via {@code app.prompt(message)} or {@code app.prompt(templateName, vars)}.
 * Executes when {@link #call()} or {@link #stream()} is invoked.
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
    private String systemOverride;
    private final PromptExecutor executor;

    /** Package-private — constructed by CafeAIApp.prompt() */
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
     * Overrides the application-level system prompt for this call only.
     * Does not affect {@code app.system()} for other requests.
     */
    public PromptRequest system(String systemPrompt) {
        this.systemOverride = systemPrompt;
        return this;
    }

    /** Executes the prompt synchronously and returns the response. */
    public PromptResponse call() {
        return executor.execute(this);
    }

    /** Package-private accessors for the executor */
    public String message()        { return message; }
    public String sessionId()      { return sessionId; }
    public String systemOverride() { return systemOverride; }

    /**
     * Internal executor interface — implemented by CafeAIApp.
     * Decouples PromptRequest from CafeAIApp to avoid circular deps.
     */
    @FunctionalInterface
    public interface PromptExecutor {
        PromptResponse execute(PromptRequest request);
    }
}
