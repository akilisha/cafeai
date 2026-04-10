package io.cafeai.core.spi;

import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;

/**
 * SPI allowing {@code cafeai-observability} to instrument every LLM call
 * without a compile-time dependency from {@code cafeai-core}.
 *
 * <p>Called by {@code CafeAIApp.executePrompt()} at two points:
 * <ol>
 *   <li>{@link #beforePrompt(PromptRequest)} -- before the LLM is called;
 *       returns a context object that is passed back to {@link #afterPrompt}</li>
 *   <li>{@link #afterPrompt(Object, PromptRequest, PromptResponse, Throwable)}
 *       -- after the LLM responds (or throws)</li>
 * </ol>
 *
 * <p>The context object is opaque to {@code cafeai-core} -- it carries
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

    /**
     * Called immediately before a vision (multimodal) LLM call.
     *
     * @param request the vision request about to be executed
     * @return an opaque context object passed to {@link #afterVision}
     */
    default Object beforeVision(io.cafeai.core.ai.VisionRequest request) {
        return null;
    }

    /**
     * Called immediately after a vision LLM call responds or throws.
     *
     * @param context  the object returned by {@link #beforeVision}
     * @param request  the original vision request
     * @param response the vision response, or {@code null} if an error occurred
     * @param error    the error, or {@code null} if the call succeeded
     */
    default void afterVision(Object context, io.cafeai.core.ai.VisionRequest request,
                             io.cafeai.core.ai.VisionResponse response, Throwable error) {
    }

    /**
     * Called immediately before an audio LLM call.
     *
     * @param request the audio request about to be executed
     * @return an opaque context object passed to {@link #afterAudio}
     */
    default Object beforeAudio(io.cafeai.core.ai.AudioRequest request) {
        return null;
    }

    /**
     * Called immediately after an audio LLM call responds or throws.
     *
     * @param context  the object returned by {@link #beforeAudio}
     * @param request  the original audio request
     * @param response the audio response, or {@code null} if an error occurred
     * @param error    the error, or {@code null} if the call succeeded
     */
    default void afterAudio(Object context, io.cafeai.core.ai.AudioRequest request,
                            io.cafeai.core.ai.AudioResponse response, Throwable error) {
    }
}
