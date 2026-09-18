package io.cafeai.core.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;

/**
 * Factory for models on NVIDIA's hosted API catalog
 * (<a href="https://build.nvidia.com">build.nvidia.com</a>).
 *
 * <pre>{@code
 *   app.ai(Nvidia.of("moonshotai/kimi-k3"));
 *   app.ai(Nvidia.of("meta/llama-3.3-70b-instruct"));
 * }</pre>
 *
 * <p>Reads the key from {@code $NVIDIA_API_KEY}. Model ids are the catalog's
 * {@code vendor/model} names — they change, so CafeAI ships no constants for them.
 *
 * <p><strong>Not one of {@code cafeai-core}'s four built-in provider types.</strong>
 * NVIDIA's endpoint is OpenAI-compatible, so this points LangChain4j's OpenAI
 * client at {@code https://integrate.api.nvidia.com/v1} and reaches it through
 * the same {@link LangchainBridge.ChatModelAccess} escape hatch {@link Gemini}
 * uses — no new dependency, and nothing in the bridge's switch changed.
 *
 * <p><strong>Timeout:</strong> five minutes unless you set
 * {@link NvidiaProvider#withTimeout}, and not {@code cafeai.chat.timeout}. A hosted
 * model can take well over the 60-second default to start responding — the
 * reasoning model in {@code NvidiaVisionExample} took about two minutes.
 *
 * <p><strong>Reasoning:</strong> a reasoning model streams its thinking in a
 * separate {@code reasoning_content} channel, which can run for minutes before
 * the first answer token. Receive it with {@code .onThinking(...)} on
 * {@link PromptRequest#onThinking} / {@link VisionRequest#onThinking}; streaming
 * the answer alone shows nothing during that wait. Tune how long it thinks with
 * {@link NvidiaProvider#withReasoningEffort}.
 */
public final class Nvidia {

    private static final String   BASE_URL = "https://integrate.api.nvidia.com/v1";
    private static final Duration TIMEOUT  = Duration.ofMinutes(5);

    private Nvidia() {}

    /** An NVIDIA provider for the given catalog model id (e.g. {@code "moonshotai/kimi-k3"}). */
    public static NvidiaProvider of(String modelId) {
        return new NvidiaProvider(modelId, null, null, null, null);
    }

    /**
     * An NVIDIA catalog model. Immutable; each {@code with...} method returns a
     * copy, so it is registered like any other provider:
     *
     * <pre>{@code
     *   app.ai(Nvidia.of("moonshotai/kimi-k3")
     *       .withReasoningEffort("max")
     *       .withTemperature(1.0)
     *       .withMaxTokens(16384));
     * }</pre>
     *
     * <p>Every knob is optional; {@code null} leaves it to the model's default.
     * {@code temperature} and {@code maxTokens} are the framework-wide
     * {@link AiProvider} settings; {@code reasoningEffort} is specific to this provider.
     *
     * @param reasoningEffort how hard a reasoning model thinks before answering
     *        (the catalog's values, e.g. {@code "low"}, {@code "high"}, {@code "max"})
     * @param temperature sampling temperature
     * @param maxTokens cap on generated tokens. For a reasoning model this
     *        includes the thinking, so a low cap can leave nothing for the answer.
     */
    public record NvidiaProvider(String modelId, String reasoningEffort,
                                 Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.ChatModelAccess,
                       LangchainBridge.StreamingChatModelAccess {

        /** A copy of this provider that requests the given reasoning effort. */
        public NvidiaProvider withReasoningEffort(String effort) {
            return new NvidiaProvider(modelId, effort, temperature, maxTokens, timeout);
        }

        // Covariant overrides, so .withTemperature(...) can chain into .withReasoningEffort(...).
        @Override public NvidiaProvider withTemperature(double t) {
            return new NvidiaProvider(modelId, reasoningEffort, t, maxTokens, timeout);
        }

        @Override public NvidiaProvider withMaxTokens(int n) {
            return new NvidiaProvider(modelId, reasoningEffort, temperature, n, timeout);
        }

        @Override public NvidiaProvider withTimeout(Duration d) {
            return new NvidiaProvider(modelId, reasoningEffort, temperature, maxTokens, d);
        }

        @Override public String name() { return "nvidia"; }

        // CUSTOM, not a dedicated NVIDIA value — same reasoning as Gemini: this
        // provider never reaches the bridge's switch on ProviderType.
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        // The catalog mixes text-only and multimodal models, and CafeAI does not
        // track per-model capabilities; let the API reject the text-only ones.
        @Override public boolean supportsVision() { return true; }

        @Override
        public ChatModel toChatModel() {
            var builder = OpenAiChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .timeout(timeout != null ? timeout : TIMEOUT);
            if (reasoningEffort != null) builder.reasoningEffort(reasoningEffort);
            if (temperature != null)     builder.temperature(temperature);
            if (maxTokens != null)       builder.maxCompletionTokens(maxTokens);
            return builder.build();
        }

        @Override
        public StreamingChatModel toStreamingChatModel() {
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    // Surface reasoning_content via onThinking(...); a no-op
                    // for models that don't emit it.
                    .returnThinking(true)
                    .timeout(timeout != null ? timeout : TIMEOUT);
            if (reasoningEffort != null) builder.reasoningEffort(reasoningEffort);
            if (temperature != null)     builder.temperature(temperature);
            if (maxTokens != null)       builder.maxCompletionTokens(maxTokens);
            return builder.build();
        }

        private static String apiKey() {
            String key = System.getenv("NVIDIA_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                    "Missing API key for nvidia provider. "
                    + "Set the NVIDIA_API_KEY environment variable:\n\n"
                    + "  export NVIDIA_API_KEY=your-key-here\n\n"
                    + "Get one at https://build.nvidia.com");
            }
            return key;
        }
    }
}
