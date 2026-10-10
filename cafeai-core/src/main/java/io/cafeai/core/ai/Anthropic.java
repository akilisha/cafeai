package io.cafeai.core.ai;

import io.cafeai.core.internal.LangchainBridge;

import java.time.Duration;
import java.util.Objects;

/**
 * Factory for Anthropic Claude LLM providers.
 *
 * <p>Model ids are provider data — they change and get retired — so CafeAI does
 * not ship named constants for them. Pass the id you want; Anthropic's API is the
 * source of truth for what is valid, and a wrong id surfaces as a clean
 * "model not found" from the provider rather than a stale default that fails in
 * production.
 *
 * <pre>{@code
 *   app.ai(Anthropic.of("claude-sonnet-4-5"));
 *   app.ai(Anthropic.of("claude-opus-4-1"));   // whatever your account has access to
 * }</pre>
 *
 * <p>{@code withBaseUrl} reaches any Anthropic-compatible endpoint (Claude in Microsoft Foundry,
 * a company gateway, DeepSeek's or Kimi's Anthropic-compatible API), and {@code withCredentials}
 * authenticates each call in place of {@code ANTHROPIC_API_KEY}: an API key as
 * {@code x-api-key}, an OAuth token (Entra ID, Workload Identity Federation) as
 * {@code Authorization: Bearer}.
 *
 * <pre>{@code
 *   app.ai(Anthropic.of("claude-opus-5-5")
 *           .withBaseUrl("https://my-resource.services.ai.azure.com/anthropic")
 *           .withCredentials(OAuthCredentials.clientCredentials(entra, "orders-api", secret)
 *                   .scope("https://ai.azure.com/.default")));
 * }</pre>
 */
public final class Anthropic {

    private Anthropic() {}

    /** An Anthropic provider for the given model id (e.g. {@code "claude-sonnet-4-5"}). */
    public static AiProvider of(String modelId) {
        return new AnthropicProvider(modelId, null, null, null, null, null, null, null);
    }

    /**
     * Claude on Google Cloud's Vertex AI: {@code project} and {@code location} ({@code global},
     * {@code us}, {@code eu}, or a region such as {@code us-east5}). Vertex takes the Messages API
     * in its own form (the model in the URL, {@code anthropic_version} in the body); each request
     * is reshaped for it. Authenticate with a Google token:
     *
     * <pre>{@code
     *   app.ai(Anthropic.onVertex("claude-opus-5-5", "my-project", "global")
     *           .withCredentials(GoogleFederation.workload(provider, IdentityToken.clientCredentials(issuer, id, secret))));
     * }</pre>
     */
    public static AiProvider onVertex(String modelId, String project, String location) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(location, "location");
        return new AnthropicProvider(modelId, null, null, null, null, null, project, location);
    }

    private record AnthropicProvider(String modelId, Double temperature, Integer maxTokens, Duration timeout,
                                     String baseUrl, Credentials credentials, String vertexProject, String vertexLocation)
            implements AiProvider, LangchainBridge.VertexAccess {
        @Override public AiProvider withTemperature(double t) { return new AnthropicProvider(modelId, t, maxTokens, timeout, baseUrl, credentials, vertexProject, vertexLocation); }
        @Override public AiProvider withMaxTokens(int n)      { return new AnthropicProvider(modelId, temperature, n, timeout, baseUrl, credentials, vertexProject, vertexLocation); }
        @Override public AiProvider withTimeout(Duration d)   { return new AnthropicProvider(modelId, temperature, maxTokens, d, baseUrl, credentials, vertexProject, vertexLocation); }

        @Override public AiProvider withBaseUrl(String url) {
            Objects.requireNonNull(url, "baseUrl");
            if (url.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
            return new AnthropicProvider(modelId, temperature, maxTokens, timeout, url, credentials, vertexProject, vertexLocation);
        }

        @Override public AiProvider withCredentials(Credentials c) {
            return new AnthropicProvider(modelId, temperature, maxTokens, timeout, baseUrl,
                    Objects.requireNonNull(c, "credentials"), vertexProject, vertexLocation);
        }

        @Override public String       name()          { return "anthropic"; }
        @Override public ProviderType type()          { return ProviderType.ANTHROPIC; }
        // Every Claude 3 model and later is multimodal; let the API reject the rare exception.
        @Override public boolean      supportsVision() { return true; }
    }
}
