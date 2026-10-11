package io.cafeai.core.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.internal.SavedKeys;

import java.time.Duration;

/**
 * Factory for Amazon's Nova models through the Amazon Nova API
 * (<a href="https://nova.amazon.com/dev">nova.amazon.com/dev</a>): an API key, no AWS account.
 *
 * <pre>{@code
 *   app.ai(Nova.of("nova-2-lite-v1"));
 * }</pre>
 *
 * <p>Reads the key from {@code $NOVA_API_KEY}, else the one {@code cafeai login nova} saved.
 * Create a key in the Amazon Nova developer console.
 * The Nova API is a free tier with rate limits; Amazon points companies to Nova on Amazon
 * Bedrock instead.
 *
 * <p><strong>Not one of {@code cafeai-core}'s four built-in provider types.</strong> The Nova
 * API is OpenAI-compatible, so this points LangChain4j's OpenAI client at
 * {@code https://api.nova.amazon.com/v1} through the {@link LangchainBridge.ChatModelAccess}
 * seam, like {@link Nvidia}.
 */
public final class Nova {

    private static final String BASE_URL = "https://api.nova.amazon.com/v1";

    private Nova() {}

    /** A Nova provider for the given model id (e.g. {@code "nova-2-lite-v1"}). */
    public static AiProvider of(String modelId) {
        return new NovaProvider(modelId, null, null, null);
    }

    private record NovaProvider(String modelId, Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {

        @Override public AiProvider withTemperature(double t) { return new NovaProvider(modelId, t, maxTokens, timeout); }
        @Override public AiProvider withMaxTokens(int n)      { return new NovaProvider(modelId, temperature, n, timeout); }
        @Override public AiProvider withTimeout(Duration d)   { return new NovaProvider(modelId, temperature, maxTokens, d); }

        @Override public String name() { return "nova"; }

        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        // Nova Lite and Pro take images, Micro doesn't; let the API reject it.
        @Override public boolean supportsVision() { return true; }

        @Override
        public ChatModel toChatModel() {
            var builder = OpenAiChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .timeout(callTimeout());
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null)   builder.maxTokens(maxTokens);
            return builder.build();
        }

        @Override
        public StreamingChatModel toStreamingChatModel() {
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .timeout(callTimeout());
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null)   builder.maxTokens(maxTokens);
            return builder.build();
        }

        private Duration callTimeout() {
            return timeout != null ? timeout : AppConfig.load().get(LangchainBridge.CHAT_TIMEOUT);
        }

        private static String apiKey() {
            return SavedKeys.require(KeyVendor.NOVA, "nova", "");
        }
    }
}
