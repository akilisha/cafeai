package io.cafeai.core.ai;

/**
 * Factory for Anthropic Claude LLM providers.
 *
 * <pre>{@code
 *   app.ai(Anthropic.claude35Sonnet());
 *   app.ai(Anthropic.claude3Haiku());  // fast, cost-efficient
 *   app.ai(Anthropic.claude3Opus());   // most capable
 * }</pre>
 */
public final class Anthropic {

    private Anthropic() {}

    public static AiProvider claude35Sonnet() { return of("claude-3-5-sonnet-20241022"); }
    public static AiProvider claude3Opus()    { return of("claude-3-opus-20240229"); }
    public static AiProvider claude3Haiku()   { return of("claude-3-haiku-20240307"); }

    /** Any Anthropic model by its model ID string. */
    public static AiProvider of(String modelId) {
        return new AnthropicProvider(modelId);
    }

    private record AnthropicProvider(String modelId) implements AiProvider {
        @Override public String name()       { return "anthropic"; }
        @Override public ProviderType type() { return ProviderType.ANTHROPIC; }
    }
}
