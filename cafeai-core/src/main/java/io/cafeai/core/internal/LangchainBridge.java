package io.cafeai.core.internal;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import dev.langchain4j.model.jlama.JlamaStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal factory that converts a CafeAI {@link AiProvider} into a Langchain4j
 * {@link ChatModel}.
 *
 * <p><strong>Internal -- never referenced by application code.</strong>
 * Public only so that {@code io.cafeai.core.ai.Ollama} can implement
 * {@link OllamaProviderAccess} and tests can implement {@link ChatModelAccess}.
 * Both nested interfaces are load-bearing extension points -- do not remove.
 *
 * <p>Models are cached per {@link AiProvider} identity after first creation --
 * Langchain4j model objects are thread-safe and expensive to construct.
 */
public final class LangchainBridge {

    /**
     * Timeout for a single LLM chat call, any provider — was a hardcoded
     * 60-second constant with no override until this key existed. Override
     * with {@code cafeai-config} on the classpath: a system property
     * (e.g. {@code -Dcafeai.chat.timeout=120s}), an environment variable, or
     * an {@code application.properties}/{@code .yaml} entry.
     */
    public static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
        "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60),
        "Timeout for a single LLM chat call, any provider.");

    /**
     * The provider's own {@code withTimeout(...)} if it set one, otherwise the
     * {@link #CHAT_TIMEOUT} setting. A per-model limit wins because the right value
     * is a property of the model, not of the application.
     */
    private static Duration timeout(AiProvider provider) {
        return provider.timeout() != null
            ? provider.timeout()
            : AppConfig.load().get(CHAT_TIMEOUT);
    }

    // Cache keyed by the provider itself. Built-in providers are records, so two
    // providers with the same model but a different temperature, max tokens or
    // base URL are distinct entries -- a name + modelId key would silently share
    // one model between them. Models are thread-safe.
    private final Map<AiProvider, ChatModel> modelCache = new ConcurrentHashMap<>();

    private LangchainBridge() {}

    static final LangchainBridge INSTANCE = new LangchainBridge();

    /**
     * Returns a {@link ChatModel} for the given provider, creating and
     * caching it on first access.
     *
     * <p>If the provider implements {@link ChatModelAccess}, its model
     * is used directly -- this is the test seam for mock providers.
     *
     * @throws IllegalArgumentException if the provider type is not supported
     */
    ChatModel modelFor(AiProvider provider) {
        // Test seam: providers that directly supply a ChatModel
        if (provider instanceof ChatModelAccess access) {
            return access.toChatModel();
        }
        return modelCache.computeIfAbsent(provider, this::createModel);
    }

    /**
     * Returns a {@link StreamingChatModel} for the given provider.
     * Used by {@code executeVisionStream()} to stream vision responses.
     *
     * <p>If the provider implements {@link StreamingChatModelAccess}, its model
     * is used directly -- this is the test seam for mock streaming providers.
     */
    StreamingChatModel streamingModelFor(AiProvider provider) {
        if (provider instanceof StreamingChatModelAccess access) {
            return access.toStreamingChatModel();
        }
        return createStreamingModel(provider);
    }

    private StreamingChatModel createStreamingModel(AiProvider provider) {
        return switch (provider.type()) {
            case OPENAI -> {
                var builder = OpenAiStreamingChatModel.builder()
                    .apiKey(resolveApiKey("OPENAI_API_KEY", provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider));
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                // max_completion_tokens, not max_tokens: newer OpenAI models reject the latter
                if (provider.maxTokens() != null)   builder.maxCompletionTokens(provider.maxTokens());
                yield builder.build();
            }

            case ANTHROPIC -> {
                var builder = AnthropicStreamingChatModel.builder()
                    .apiKey(resolveApiKey("ANTHROPIC_API_KEY", provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider));
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.maxTokens(provider.maxTokens());
                yield builder.build();
            }

            case OLLAMA -> {
                String baseUrl = provider instanceof OllamaProviderAccess opa
                    ? opa.baseUrl()
                    : "http://localhost:11434";
                var builder = OllamaStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(provider.modelId())
                    .timeout(timeout(provider));
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.numPredict(provider.maxTokens());
                yield builder.build();
            }

            case JLAMA -> {
                var builder = JlamaStreamingChatModel.builder().modelName(provider.modelId());
                if (provider instanceof JlamaProviderAccess jpa && jpa.modelCachePath() != null) {
                    builder.modelCachePath(Path.of(jpa.modelCachePath()));
                }
                if (provider.temperature() != null) builder.temperature(provider.temperature().floatValue());
                if (provider.maxTokens() != null)   builder.maxTokens(provider.maxTokens());
                yield builder.build();
            }

            default -> throw new IllegalArgumentException(
                "Streaming not supported for provider type: " + provider.type());
        };
    }

    private ChatModel createModel(AiProvider provider) {
        return switch (provider.type()) {
            case OPENAI -> {
                var builder = OpenAiChatModel.builder()
                    .apiKey(resolveApiKey("OPENAI_API_KEY", provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider))
                    .logRequests(false)
                    .logResponses(false);
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                // max_completion_tokens, not max_tokens: newer OpenAI models reject the latter
                if (provider.maxTokens() != null)   builder.maxCompletionTokens(provider.maxTokens());
                yield builder.build();
            }

            case ANTHROPIC -> {
                var builder = AnthropicChatModel.builder()
                    .apiKey(resolveApiKey("ANTHROPIC_API_KEY", provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider))
                    .logRequests(false)
                    .logResponses(false);
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.maxTokens(provider.maxTokens());
                yield builder.build();
            }

            case OLLAMA -> {
                String baseUrl = provider instanceof OllamaProviderAccess opa
                    ? opa.baseUrl()
                    : "http://localhost:11434";
                var builder = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(provider.modelId())
                    .timeout(timeout(provider));
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.numPredict(provider.maxTokens());
                yield builder.build();
            }

            case JLAMA -> {
                var builder = JlamaChatModel.builder().modelName(provider.modelId());
                if (provider instanceof JlamaProviderAccess jpa && jpa.modelCachePath() != null) {
                    builder.modelCachePath(Path.of(jpa.modelCachePath()));
                }
                if (provider.temperature() != null) builder.temperature(provider.temperature().floatValue());
                if (provider.maxTokens() != null)   builder.maxTokens(provider.maxTokens());
                yield builder.build();
            }

            default -> throw new IllegalArgumentException(
                "Unsupported provider type: " + provider.type() +
                ". Supported: OPENAI, ANTHROPIC, OLLAMA, JLAMA. " +
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
                "  app.ai(Ollama.of(llama3.3))  // via a local Ollama server\n" +
                "  app.ai(Jlama.of(tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4)) // pure-Java, in-process, no server");
        }
        return key;
    }

    /**
     * Internal interface for Ollama providers that carry a base URL.
     * Public so {@link io.cafeai.core.ai.Ollama.OllamaProvider} can implement it
     * without violating package access rules.
     */
    public interface OllamaProviderAccess {
        String baseUrl();
    }

    /**
     * Internal interface for Jlama providers that carry an on-disk model cache
     * path. A {@code null} return means "use Jlama's default cache directory".
     * Public so {@link io.cafeai.core.ai.Jlama.JlamaProvider} can implement it
     * without violating package access rules.
     */
    public interface JlamaProviderAccess {
        String modelCachePath();
    }

    /**
     * Test seam interface. Any {@link AiProvider} that also implements this
     * interface will have its model used directly, bypassing environment variable
     * lookups and real API connections. Used by mock providers in tests.
     * Public so test classes outside the {@code internal} package can implement it.
     */
    public interface ChatModelAccess {
        ChatModel toChatModel();
    }

    /**
     * Test seam for streaming providers. Any {@link AiProvider} that also implements
     * this interface will have its streaming model used directly.
     */
    public interface StreamingChatModelAccess {
        StreamingChatModel toStreamingChatModel();
    }
}
