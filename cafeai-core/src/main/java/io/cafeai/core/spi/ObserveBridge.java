package io.cafeai.core.spi;

import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;

/**
 * SPI allowing {@code cafeai-observability} to instrument every LLM call
 * without a compile-time dependency from {@code cafeai-core}.
 *
 * <p>Called by {@code CafeAIApp.executePrompt()} at two points:
 * <ol>
 *   <li>{@link #beforePrompt(PromptRequest)} — before the LLM is called;
 *       returns a context object that is passed back to {@link #afterPrompt}</li>
 *   <li>{@link #afterPrompt(Object, PromptRequest, PromptResponse, Throwable)}
 *       — after the LLM responds (or throws)</li>
 * </ol>
 *
 * <p>The context object is opaque to {@code cafeai-core} — it carries
 * whatever the strategy needs to correlate before/after (e.g. a span,
 * a start timestamp, a trace ID).
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ObserveBridge}
 */
public interface ObserveBridge {

    /**
     * Sets the active observation strategy.
     * Called by {@code CafeAIApp.observe()} immediately after the bridge
     * is loaded via ServiceLoader, passing the strategy object the developer
     * registered. The bridge stores it and dispatches accordingly.
     *
     * @param strategy an {@code io.cafeai.observability.ObserveStrategy} instance
     */
    void setStrategy(Object strategy);

    /**
     * Called immediately before the LLM is invoked.
     *
     * @param request the prompt request about to be executed
     * @return an opaque context object passed to {@link #afterPrompt};
     *         may be {@code null} if no before-state is needed
     */
    Object beforePrompt(PromptRequest request);

    /**
     * Called immediately after the LLM responds or throws.
     *
     * @param context   the object returned by {@link #beforePrompt}
     * @param request   the original prompt request
     * @param response  the LLM response, or {@code null} if an error occurred
     * @param error     the error, or {@code null} if the call succeeded
     */
    void afterPrompt(Object context, PromptRequest request,
                     PromptResponse response, Throwable error);
}
