package io.cafeai.core.internal;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Ollama;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal factory that converts a CafeAI {@link AiProvider} into a Langchain4j
 * {@link ChatLanguageModel}.
 *
 * <p><strong>Internal — never referenced by application code.</strong>
 * This is the bridge between CafeAI's provider-agnostic API and Langchain4j's
 * concrete model implementations. All Langchain4j types are confined to this class.
 *
 * <p>Models are cached per {@link AiProvider} identity after first creation —
 * Langchain4j model objects are thread-safe and expensive to construct.
 */
final class LangchainBridge {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    // Cache keyed by provider identity (name + modelId) — models are thread-safe
    private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();

    private LangchainBridge() {}

    static final LangchainBridge INSTANCE = new LangchainBridge();

    /**
     * Returns a {@link ChatLanguageModel} for the given provider, creating and
     * caching it on first access.
     *
     * <p>If the provider implements {@link ChatLanguageModelAccess}, its model
     * is used directly — this is the test seam for mock providers.
     *
     * @throws IllegalArgumentException if the provider type is not supported
     */
    ChatLanguageModel modelFor(AiProvider provider) {
        // Test seam: providers that directly supply a ChatLanguageModel
        if (provider instanceof ChatLanguageModelAccess access) {
            return access.toLangchainModel();
        }
        String cacheKey = provider.name() + ":" + provider.modelId();
        return modelCache.computeIfAbsent(cacheKey, k -> createModel(provider));
    }

    private ChatLanguageModel createModel(AiProvider provider) {
        return switch (provider.type()) {
            case OPENAI -> OpenAiChatModel.builder()
                .apiKey(resolveApiKey("OPENAI_API_KEY", provider))
                .modelName(provider.modelId())
                .timeout(DEFAULT_TIMEOUT)
                .logRequests(false)
                .logResponses(false)
                .build();

            case ANTHROPIC -> AnthropicChatModel.builder()
                .apiKey(resolveApiKey("ANTHROPIC_API_KEY", provider))
                .modelName(provider.modelId())
                .timeout(DEFAULT_TIMEOUT)
                .logRequestsAndResponses(false)
                .build();

            case OLLAMA -> {
                String baseUrl = provider instanceof OllamaProviderAccess opa
                    ? opa.baseUrl()
                    : "http://localhost:11434";
                yield OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(provider.modelId())
                    .timeout(DEFAULT_TIMEOUT)
                    .build();
            }

            default -> throw new IllegalArgumentException(
                "Unsupported provider type: " + provider.type() +
                ". Supported: OPENAI, ANTHROPIC, OLLAMA. " +
                "For other providers, implement AiProvider and wire Langchain4j manually.");
        };
    }

    /**
     * Reads the API key from environment variables.
     * Throws a clear, actionable error if the key is absent.
     */
    private String resolveApiKey(String envVar, AiProvider provider) {
        String key = System.getenv(envVar);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "Missing API key for " + provider.name() + " provider. " +
                "Set the " + envVar + " environment variable:\n\n" +
                "  export " + envVar + "=your-key-here\n\n" +
                "Or use a local model with no API key:\n" +
                "  app.ai(Ollama.llama3())  // runs on your machine, no key needed");
        }
        return key;
    }

    /**
     * Internal interface for Ollama providers that carry a base URL.
     * Accessed via pattern matching in {@link #createModel(AiProvider)}.
     */
    interface OllamaProviderAccess {
        String baseUrl();
    }

    /**
     * Test seam interface. Any {@link AiProvider} that also implements this
     * interface will have its model used directly, bypassing environment variable
     * lookups and real API connections. Used by mock providers in tests.
     */
    interface ChatLanguageModelAccess {
        ChatLanguageModel toLangchainModel();
    }
}
