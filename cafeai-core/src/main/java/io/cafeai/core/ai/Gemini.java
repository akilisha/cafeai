package io.cafeai.core.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;

/**
 * Factory for Google Gemini providers, via the Gemini Developer API (Google AI
 * Studio) — an API key, no GCP project or service account to set up.
 *
 * <pre>{@code
 *   app.ai(Gemini.of("gemini-3.6-flash"));
 *   app.ai(Gemini.of("gemini-2.5-pro"));
 * }</pre>
 *
 * <p>Reads the key from {@code $GEMINI_API_KEY} — get one at
 * <a href="https://aistudio.google.com/apikey">aistudio.google.com/apikey</a>.
 *
 * <p><strong>Not one of {@code cafeai-core}'s four built-in provider types.</strong>
 * {@link io.cafeai.core.internal.LangchainBridge} only knows how to build a
 * {@code ChatModel} for {@code OPENAI} / {@code ANTHROPIC} / {@code OLLAMA} /
 * {@code JLAMA} — this class reaches Gemini entirely through the bridge's public
 * {@link LangchainBridge.ChatModelAccess} escape hatch instead, which every
 * {@link AiProvider} is free to implement. Nothing in the bridge's switch, the
 * {@code AiProvider} interface, or {@link AiProvider.ProviderType} changed to
 * add this: one new file, one new dependency line. It's the same seam
 * {@link Ollama} and {@link Jlama} use internally to carry a base URL / model
 * cache path past the public {@code AiProvider} interface — proof the
 * abstraction is genuinely open at the edges, not a closed enum of four.
 */
public final class Gemini {

    private Gemini() {}

    /** A Gemini provider for the given model id (e.g. {@code "gemini-3.6-flash"}). */
    public static AiProvider of(String modelId) {
        return new GeminiProvider(modelId, null, null, null);
    }

    private record GeminiProvider(String modelId, Double temperature, Integer maxTokens, Duration timeout)
            implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {

        @Override public AiProvider withTemperature(double t) { return new GeminiProvider(modelId, t, maxTokens, timeout); }
        @Override public AiProvider withMaxTokens(int n)      { return new GeminiProvider(modelId, temperature, n, timeout); }
        @Override public AiProvider withTimeout(Duration d)   { return new GeminiProvider(modelId, temperature, maxTokens, d); }

        @Override public String name() { return "gemini"; }

        // CUSTOM, not a dedicated GEMINI value — there isn't one, and there
        // doesn't need to be: this provider never reaches LangchainBridge's
        // switch on ProviderType (ChatModelAccess intercepts it first), so the
        // enum value is descriptive only. CUSTOM says exactly what's true here:
        // "not one of the four types the bridge builds itself."
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        // Every current Gemini chat model is natively multimodal; let the API
        // reject the rare exception rather than CafeAI tracking a model list.
        @Override public boolean supportsVision() { return true; }

        private static String apiKey() {
            String apiKey = System.getenv("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                    "Missing API key for gemini provider. "
                    + "Set the GEMINI_API_KEY environment variable:\n\n"
                    + "  export GEMINI_API_KEY=your-key-here\n\n"
                    + "Get one at https://aistudio.google.com/apikey");
            }
            return apiKey;
        }

        @Override
        public ChatModel toChatModel() {
            var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey())
                    .modelName(modelId);
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null)   builder.maxOutputTokens(maxTokens);
            if (timeout != null)     builder.timeout(timeout);
            return builder.build();
        }

        /** What {@code .stream()} and {@code app.vision(...).stream(...)} call. */
        @Override
        public StreamingChatModel toStreamingChatModel() {
            var builder = GoogleAiGeminiStreamingChatModel.builder()
                    .apiKey(apiKey())
                    .modelName(modelId);
            if (temperature != null) builder.temperature(temperature);
            if (maxTokens != null)   builder.maxOutputTokens(maxTokens);
            if (timeout != null)     builder.timeout(timeout);
            return builder.build();
        }
    }
}
