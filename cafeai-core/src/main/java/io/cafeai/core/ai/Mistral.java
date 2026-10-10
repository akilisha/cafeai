package io.cafeai.core.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;

/**
 * Factory for Mistral AI's models (<a href="https://docs.mistral.ai">docs.mistral.ai</a>).
 *
 * <pre>{@code
 *   app.ai(Mistral.of("mistral-large-latest"));
 * }</pre>
 *
 * <p>Reads the key from {@code $MISTRAL_API_KEY} — get one at
 * <a href="https://console.mistral.ai/api-keys">console.mistral.ai</a>. Model ids change, so
 * CafeAI ships no constants for them.
 *
 * <p><strong>Not one of {@code cafeai-core}'s four built-in provider types.</strong> Built with
 * LangChain4j's own Mistral client (Mistral's API differs from OpenAI's in small ways, such as
 * {@code max_tokens}), reached through the {@link LangchainBridge.ChatModelAccess} seam, like
 * {@link Gemini}.
 */
public final class Mistral {

    private Mistral() {}

    /** A Mistral provider for the given model id (e.g. {@code "mistral-large-latest"}). */
    public static AiProvider of(String modelId) {
        return new MistralProvider(modelId, null, null, null);
    }

    private record MistralProvider(String modelId, Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {

        @Override public AiProvider withTemperature(double t) { return new MistralProvider(modelId, t, maxTokens, timeout); }
        @Override public AiProvider withMaxTokens(int n)      { return new MistralProvider(modelId, temperature, n, timeout); }
        @Override public AiProvider withTimeout(Duration d)   { return new MistralProvider(modelId, temperature, maxTokens, d); }

        @Override public String name() { return "mistral"; }

        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        // Mistral's current large and medium models take images; let the API reject one that doesn't.
        @Override public boolean supportsVision() { return true; }

        @Override
        public ChatModel toChatModel() {
            var builder = MistralAiChatModel.builder()
                    .apiKey(apiKey())
                    .modelName(modelId)
                    .timeout(callTimeout());
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null)   builder.maxTokens(maxTokens);
            return builder.build();
        }

        @Override
        public StreamingChatModel toStreamingChatModel() {
            var builder = MistralAiStreamingChatModel.builder()
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
            String key = System.getenv("MISTRAL_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                    "Missing API key for mistral provider. "
                    + "Set the MISTRAL_API_KEY environment variable:\n\n"
                    + "  export MISTRAL_API_KEY=your-key-here\n\n"
                    + "Get one at https://console.mistral.ai/api-keys");
            }
            return key;
        }
    }
}
