package io.cafeai.core.ai;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    private final PromptStreamExecutor streamExecutor;

    /** Constructed by CafeAIApp.prompt() -- no streaming executor. */
    public PromptRequest(String message, PromptExecutor executor) {
        this(message, executor, null);
    }

    /** Constructed by CafeAIApp.prompt() -- with a streaming executor. */
    public PromptRequest(String message, PromptExecutor executor, PromptStreamExecutor streamExecutor) {
        this.message        = message;
        this.executor       = executor;
        this.streamExecutor = streamExecutor;
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
     * Executes the prompt and streams the response token-by-token as a reactive
     * publisher. The LLM call runs on a virtual thread; tokens are emitted as
     * they arrive and the publisher completes when generation finishes.
     *
     * <p>Usually you don't call this directly — {@code res.stream(app.prompt(...))}
     * takes the {@code PromptRequest} and does it for you. Call {@code stream()}
     * when you need the raw publisher (bridging to another reactive library,
     * tests, non-HTTP consumers).
     *
     * <p>Session memory ({@link #session(String)}), the token budget, named
     * providers and observability all apply exactly as they do for {@link #call()};
     * the full assembled response is persisted once the stream completes.
     *
     * <p>Not compatible with {@link #returning(Class)} / {@link #call(Class)} —
     * structured output needs the complete JSON before it can be parsed.
     *
     * @throws IllegalStateException if CafeAI was not initialised via {@code CafeAI.create()}
     */
    public Flow.Publisher<String> stream() {
        if (streamExecutor == null) {
            throw new IllegalStateException(
                "Prompt streaming is not available. Initialise CafeAI via CafeAI.create().");
        }
        return streamExecutor.stream(this);
    }

    /**
     * Executes the prompt and invokes {@code onToken} for each token as it
     * arrives. Blocks until generation completes; propagates any provider error.
     *
     * <p>The zero-ceremony form of {@link #stream()} — no {@code Flow.Subscriber}
     * to write:
     * <pre>{@code
     *   app.prompt("Explain virtual threads").stream(System.out::print);
     * }</pre>
     *
     * <p>Session memory, token budget, and observability apply exactly as for
     * {@link #call()}.
     *
     * @throws IllegalStateException if CafeAI was not initialised via {@code CafeAI.create()}
     */
    public void stream(Consumer<String> onToken) {
        if (onToken == null) {
            throw new IllegalArgumentException("onToken consumer must not be null");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        stream().subscribe(new Flow.Subscriber<String>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String token) { onToken.accept(token); }
            @Override public void onError(Throwable t) { error.set(t); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while streaming the prompt response", e);
        }
        Throwable t = error.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error er) throw er;
        if (t != null) throw new RuntimeException(t);
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

    /**
     * Internal streaming executor interface -- implemented by CafeAIApp.
     * Returns a publisher that emits response tokens as they are generated.
     */
    @FunctionalInterface
    public interface PromptStreamExecutor {
        Flow.Publisher<String> stream(PromptRequest request);
    }
}
