package io.cafeai.core.internal;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
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
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
                    .modelName(provider.modelId())
                    .timeout(timeout(provider)).httpClientBuilder(http());
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                // max_completion_tokens, not max_tokens: newer OpenAI models reject the latter
                if (provider.maxTokens() != null)   builder.maxCompletionTokens(provider.maxTokens());
                if (provider.baseUrl() != null)     builder.baseUrl(provider.baseUrl());
                if (provider.credentials() != null) builder.customHeaders(perCallAuthorization(provider.credentials()));
                else                                builder.apiKey(resolveApiKey("OPENAI_API_KEY", provider));
                yield builder.build();
            }

            case ANTHROPIC -> {
                var builder = AnthropicStreamingChatModel.builder()
                    .apiKey(anthropicKey(provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider)).httpClientBuilder(anthropicHttp(provider));
                if (anthropicBase(provider) != null) builder.baseUrl(anthropicBase(provider));
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
                    .timeout(timeout(provider)).httpClientBuilder(http());
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.numPredict(provider.maxTokens());
                yield builder.build();
            }

            case JLAMA -> {
                var jlama = JlamaSettings.of(provider);
                var builder = JlamaStreamingChatModel.builder().modelName(jlama.modelName());
                if (jlama.modelCachePath() != null) builder.modelCachePath(jlama.modelCachePath());
                if (jlama.temperature() != null)    builder.temperature(jlama.temperature());
                if (jlama.maxTokens() != null)      builder.maxTokens(jlama.maxTokens());
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
                    .modelName(provider.modelId())
                    .timeout(timeout(provider)).httpClientBuilder(http())
                    .logRequests(false)
                    .logResponses(false);
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                // max_completion_tokens, not max_tokens: newer OpenAI models reject the latter
                if (provider.maxTokens() != null)   builder.maxCompletionTokens(provider.maxTokens());
                if (provider.baseUrl() != null)     builder.baseUrl(provider.baseUrl());
                if (provider.credentials() != null) builder.customHeaders(perCallAuthorization(provider.credentials()));
                else                                builder.apiKey(resolveApiKey("OPENAI_API_KEY", provider));
                yield builder.build();
            }

            case ANTHROPIC -> {
                var builder = AnthropicChatModel.builder()
                    .apiKey(anthropicKey(provider))
                    .modelName(provider.modelId())
                    .timeout(timeout(provider)).httpClientBuilder(anthropicHttp(provider))
                    .logRequests(false)
                    .logResponses(false);
                if (anthropicBase(provider) != null) builder.baseUrl(anthropicBase(provider));
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
                    .timeout(timeout(provider)).httpClientBuilder(http());
                if (provider.temperature() != null) builder.temperature(provider.temperature());
                if (provider.maxTokens() != null)   builder.numPredict(provider.maxTokens());
                yield builder.build();
            }

            case JLAMA -> {
                var jlama = JlamaSettings.of(provider);
                var builder = JlamaChatModel.builder().modelName(jlama.modelName());
                if (jlama.modelCachePath() != null) builder.modelCachePath(jlama.modelCachePath());
                if (jlama.temperature() != null)    builder.temperature(jlama.temperature());
                if (jlama.maxTokens() != null)      builder.maxTokens(jlama.maxTokens());
                yield builder.build();
            }

            default -> throw new IllegalArgumentException(
                "Unsupported provider type: " + provider.type() +
                ". Supported: OPENAI, ANTHROPIC, OLLAMA, JLAMA. " +
                "For other providers, implement AiProvider and wire Langchain4j manually.");
        };
    }

    /**
     * The HTTP client a provider's model is built on: LangChain4j's JDK client, over one that
     * keeps a {@code 429}'s {@code Retry-After} for the request ({@link ProviderHttp}).
     */
    private static JdkHttpClientBuilder http() {
        return new JdkHttpClientBuilder().httpClientBuilder(ProviderHttp.builder());
    }

    /**
     * Anthropic's client has no per-request header hook, and always sends {@code x-api-key}; with
     * credentials, the HTTP client below it sets the credential on every request instead
     * ({@link ProviderHttp}): an API key as {@code x-api-key}, a token as
     * {@code Authorization: Bearer}, which the Claude API prefers and Microsoft Foundry requires
     * for Entra ID tokens.
     */
    private static JdkHttpClientBuilder anthropicHttp(AiProvider provider) {
        ProviderHttp.Rewrite rewrite = vertex(provider) == null ? null
                : new ProviderHttp.VertexRewrite(vertex(provider).vertexProject(), vertex(provider).vertexLocation());
        Credentials credentials = anthropicCredentials(provider);
        if (credentials == null && rewrite == null) return http();
        return new JdkHttpClientBuilder().httpClientBuilder(ProviderHttp.builder(credentials, "x-api-key", rewrite));
    }

    /** Where the bridge reads the Anthropic environment variables; replaced in tests. */
    static volatile UnaryOperator<String> environment = System::getenv;

    /**
     * What authenticates an Anthropic provider's calls, in the order Anthropic's own SDKs use:
     * {@code withCredentials(...)}; {@code ANTHROPIC_API_KEY} (then {@code null}: the client
     * sends it); the Claude Console sign-in {@code ant auth login} (or {@code cafeai login
     * claude}) made. The sign-in only for Anthropic's own API: its token is never sent to another
     * host.
     */
    private static Credentials anthropicCredentials(AiProvider provider) {
        if (provider.credentials() != null || vertex(provider) != null) return provider.credentials();
        return signIn(provider).map(AnthropicProfile::credentials).orElse(null);
    }

    /** The Claude Console sign-in this provider would use, if it uses one. */
    private static Optional<AnthropicProfile> signIn(AiProvider provider) {
        if (provider.credentials() != null || vertex(provider) != null || provider.baseUrl() != null
                || present(environment.apply("ANTHROPIC_API_KEY"))) {
            return Optional.empty();
        }
        return AnthropicProfile.active(environment).filter(AnthropicProfile::isSignIn);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** The provider's Vertex AI target, or {@code null} when it calls an Anthropic-compatible API. */
    private static VertexAccess vertex(AiProvider provider) {
        return provider instanceof VertexAccess v && v.vertexProject() != null ? v : null;
    }

    /**
     * Where Anthropic's client sends its requests: the given base URL, or, on Vertex AI, the
     * location's Vertex host (each request is then rewritten for Vertex in {@link ProviderHttp}).
     */
    private static String anthropicBase(AiProvider provider) {
        if (provider.baseUrl() != null) return anthropicBaseUrl(provider.baseUrl());
        VertexAccess v = vertex(provider);
        if (v != null) return ProviderHttp.VertexRewrite.host(v.vertexLocation()) + "/v1/";
        String profileBase = signIn(provider).map(AnthropicProfile::baseUrl).orElse(null);
        return profileBase == null ? null : anthropicBaseUrl(profileBase);
    }

    /** The key Anthropic's client is built with: with per-request credentials, a placeholder that is never sent. */
    private String anthropicKey(AiProvider provider) {
        return anthropicCredentials(provider) != null ? "per-request" : resolveApiKey("ANTHROPIC_API_KEY", provider);
    }

    /**
     * An Anthropic-compatible endpoint's base URL as LangChain4j's client wants it, ending in
     * {@code /v1/}: {@code https://api.deepseek.com/anthropic} and
     * {@code https://api.deepseek.com/anthropic/v1} both work, as the endpoints document them.
     */
    static String anthropicBaseUrl(String url) {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return (base.endsWith("/v1") ? base : base + "/v1") + "/";
    }

    /**
     * The {@code Authorization} header, asked for on every request rather than fixed when the
     * client is built, so one shared client serves every caller with their own, current
     * credential. Spelled exactly as the client's own: LangChain4j's header map is
     * case-sensitive, and a differently cased name would be sent beside it, not instead of it.
     * No API key is set on such a client, so there is no other to replace.
     */
    private static Supplier<Map<String, String>> perCallAuthorization(Credentials credentials) {
        return () -> Map.of("Authorization", "Bearer " + credentials.token());
    }

    /**
     * Reads the API key from environment variables.
     * Throws a clear, actionable error if the key is absent.
     */
    private String resolveApiKey(String envVar, AiProvider provider) {
        var vendor = KeyVendor.byEnvVar(envVar);
        if (vendor.isPresent()) {
            return SavedKeys.require(vendor.get(), provider.name(),
                "\n\nOr use a local model with no API key:\n" +
                "  app.ai(Ollama.of(llama3.3))  // via a local Ollama server\n" +
                "  app.ai(Jlama.of(tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4)) // pure-Java, in-process, no server");
        }
        String key = environment.apply(envVar);
        if (key == null || key.isBlank()) {
            String signIn = envVar.equals("ANTHROPIC_API_KEY") && provider.baseUrl() == null
                ? "Sign in with your Claude Console account (no key needed):\n\n" +
                  "  cafeai login claude\n\nOr set the " + envVar + " environment variable:\n\n"
                : "Set the " + envVar + " environment variable:\n\n";
            throw new IllegalStateException(
                "Missing API key for " + provider.name() + " provider. " +
                signIn +
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
     * An Anthropic provider that reaches Claude on Google Cloud's Vertex AI: its project and
     * location, or {@code null} for the Anthropic API itself. Public so
     * {@link io.cafeai.core.ai.Anthropic}'s provider can implement it.
     */
    public interface VertexAccess {
        String vertexProject();
        String vertexLocation();
    }

    /**
     * What a Jlama provider asks LangChain4j's builders for. Applying it needs a model (building one
     * loads it, and downloads it the first time), so the mapping is kept apart where it can be tested
     * without one: an unset value stays {@code null} and is never passed on, {@code 0.0} is a value,
     * and the temperature narrows to the {@code Float} Jlama takes.
     */
    record JlamaSettings(String modelName, Path modelCachePath, Float temperature, Integer maxTokens) {
        static JlamaSettings of(AiProvider provider) {
            Path cache = provider instanceof JlamaProviderAccess jpa && jpa.modelCachePath() != null
                ? Path.of(jpa.modelCachePath()) : null;
            Float temperature = provider.temperature() != null ? provider.temperature().floatValue() : null;
            return new JlamaSettings(provider.modelId(), cache, temperature, provider.maxTokens());
        }
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
     * The {@link ChatModel} CafeAI would use for {@code provider}. For a provider that
     * wraps another and must call the real model underneath it (as
     * {@code cafeai-test}'s {@code Replay} does).
     */
    public static ChatModel chatModel(AiProvider provider) {
        return INSTANCE.modelFor(provider);
    }

    /** The {@link StreamingChatModel} CafeAI would use for {@code provider}; see {@link #chatModel}. */
    public static StreamingChatModel streamingChatModel(AiProvider provider) {
        return INSTANCE.streamingModelFor(provider);
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
