package io.cafeai.core.ai;

/**
 * Factory for OpenAI LLM providers.
 *
 * <pre>{@code
 *   app.ai(OpenAI.gpt4o());
 *   app.ai(OpenAI.gpt4oMini());   // cheaper, faster — good for simple queries
 *   app.ai(OpenAI.o1());          // reasoning model
 *   app.ai(OpenAI.of("gpt-4-turbo")); // any model by ID
 * }</pre>
 */
public final class OpenAI {

    private OpenAI() {}

    public static AiProvider gpt4o()        { return of("gpt-4o"); }
    public static AiProvider gpt4oMini()    { return of("gpt-4o-mini"); }
    public static AiProvider o1()           { return of("o1"); }
    public static AiProvider o1Mini()       { return of("o1-mini"); }

    /** Any OpenAI model by its model ID string. */
    public static AiProvider of(String modelId) {
        return new OpenAiProvider(modelId);
    }

    private record OpenAiProvider(String modelId) implements AiProvider {
        @Override public String name()       { return "openai"; }
        @Override public ProviderType type() { return ProviderType.OPENAI; }
    }
}
