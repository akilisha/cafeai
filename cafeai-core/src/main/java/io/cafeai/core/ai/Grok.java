package io.cafeai.core.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;

/**
 * Factory for xAI's Grok models (<a href="https://docs.x.ai">docs.x.ai</a>).
 *
 * <pre>{@code
 *   app.ai(Grok.of("grok-4.7"));
 *   app.ai(Grok.of("grok-4.7").withReasoningEffort("high"));
 * }</pre>
 *
 * <p>Reads the key from {@code $XAI_API_KEY} — get one at
 * <a href="https://console.x.ai">console.x.ai</a>. Model ids change, so CafeAI ships no
 * constants for them.
 *
 * <p><strong>Not one of {@code cafeai-core}'s four built-in provider types.</strong> xAI's API is
 * OpenAI-compatible, so this points LangChain4j's OpenAI client at {@code https://api.x.ai/v1}
 * through the {@link LangchainBridge.ChatModelAccess} seam, like {@link Nvidia}.
 */
public final class Grok {

    private static final String BASE_URL = "https://api.x.ai/v1";

    private Grok() {}

    /** A Grok provider for the given model id (e.g. {@code "grok-4.7"}). */
    public static GrokProvider of(String modelId) {
        return new GrokProvider(modelId, null, null, null, null);
    }

    /**
     * A Grok model. Immutable; each {@code with...} method returns a copy. Every setting is
     * optional; {@code null} leaves it to the model's default.
     *
     * @param reasoningEffort how hard a reasoning model thinks before answering (e.g.
     *        {@code "low"}, {@code "high"}); not every Grok model accepts it
     * @param temperature sampling temperature
     * @param maxTokens cap on generated tokens, sent as {@code max_tokens}
     */
    public record GrokProvider(String modelId, String reasoningEffort,
                               Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.ChatModelAccess,
                       LangchainBridge.StreamingChatModelAccess {

        /** A copy of this provider that requests the given reasoning effort. */
        public GrokProvider withReasoningEffort(String effort) {
            return new GrokProvider(modelId, effort, temperature, maxTokens, timeout);
        }

        @Override public GrokProvider withTemperature(double t) {
            return new GrokProvider(modelId, reasoningEffort, t, maxTokens, timeout);
        }

        @Override public GrokProvider withMaxTokens(int n) {
            return new GrokProvider(modelId, reasoningEffort, temperature, n, timeout);
        }

        @Override public GrokProvider withTimeout(Duration d) {
            return new GrokProvider(modelId, reasoningEffort, temperature, maxTokens, d);
        }

        @Override public String name() { return "grok"; }

        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        // Current Grok models take images; let the API reject one that doesn't.
        @Override public boolean supportsVision() { return true; }

        @Override
        public ChatModel toChatModel() {
            var builder = OpenAiChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .timeout(callTimeout());
            if (reasoningEffort != null) builder.reasoningEffort(reasoningEffort);
            if (temperature != null)     builder.temperature(temperature);
            if (maxTokens != null)       builder.maxTokens(maxTokens);
            return builder.build();
        }

        @Override
        public StreamingChatModel toStreamingChatModel() {
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .returnThinking(true)
                    .timeout(callTimeout());
            if (reasoningEffort != null) builder.reasoningEffort(reasoningEffort);
            if (temperature != null)     builder.temperature(temperature);
            if (maxTokens != null)       builder.maxTokens(maxTokens);
            return builder.build();
        }

        private Duration callTimeout() {
            return timeout != null ? timeout : AppConfig.load().get(LangchainBridge.CHAT_TIMEOUT);
        }

        private static String apiKey() {
            String key = System.getenv("XAI_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                    "Missing API key for grok provider. "
                    + "Set the XAI_API_KEY environment variable:\n\n"
                    + "  export XAI_API_KEY=your-key-here\n\n"
                    + "Get one at https://console.x.ai");
            }
            return key;
        }
    }
}
