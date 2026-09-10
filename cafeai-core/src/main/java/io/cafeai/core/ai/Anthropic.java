package io.cafeai.core.ai;

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
 */
public final class Anthropic {

    private Anthropic() {}

    /** An Anthropic provider for the given model id (e.g. {@code "claude-sonnet-4-5"}). */
    public static AiProvider of(String modelId) {
        return new AnthropicProvider(modelId);
    }

    private record AnthropicProvider(String modelId) implements AiProvider {
        @Override public String       name()          { return "anthropic"; }
        @Override public ProviderType type()          { return ProviderType.ANTHROPIC; }
        // Every Claude 3 model and later is multimodal; let the API reject the rare exception.
        @Override public boolean      supportsVision() { return true; }
    }
}
