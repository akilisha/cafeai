package io.cafeai.core.ai;

/**
 * Routes prompts to different LLM models based on complexity.
 *
 * <p>Reduces cost significantly without sacrificing quality -- simple queries
 * go to a cheaper, faster model; complex queries go to a more capable model.
 *
 * <pre>{@code
 *   app.ai(ModelRouter.smart()
 *       .simple(OpenAI.gpt4oMini())
 *       .complex(OpenAI.gpt4o()));
 * }</pre>
 */
public final class ModelRouter implements AiProvider {

    private AiProvider simpleModel;
    private AiProvider complexModel;

    private ModelRouter() {}

    /** Creates a smart model router builder. */
    public static ModelRouter smart() {
        return new ModelRouter();
    }

    /**
     * Sets the model for simple queries -- cheap, fast.
     * Used for classification, short responses, single-turn queries.
     */
    public ModelRouter simple(AiProvider provider) {
        this.simpleModel = provider;
        return this;
    }

    /**
     * Sets the model for complex queries -- more capable, higher cost.
     * Used for multi-step reasoning, tool use, long context.
     */
    public ModelRouter complex(AiProvider provider) {
        this.complexModel = provider;
        return this;
    }

    public AiProvider simpleModel()  { return simpleModel; }
    public AiProvider complexModel() { return complexModel; }

    /**
     * Routes a prompt to the appropriate model based on input length.
     * Messages over 500 characters are considered complex.
     *
     * @param inputLength the length of the prompt text
     */
    public AiProvider route(int inputLength) {
        return inputLength > 500 ? complexModel : simpleModel;
    }

    // ── AiProvider implementation ─────────────────────────────────────────────
    // ModelRouter presents as the complex model for capability checks (vision,
    // audio) and for named provider registration. Actual routing happens in
    // CafeAIApp when it detects the provider is a ModelRouter instance.

    @Override public String       name()    { return complexModel != null ? complexModel.name() : "router"; }
    @Override public String       modelId() { return complexModel != null ? complexModel.modelId() : "router"; }
    @Override public ProviderType type()    { return complexModel != null ? complexModel.type() : ProviderType.CUSTOM; }

    @Override public boolean supportsVision() {
        return complexModel != null && complexModel.supportsVision();
    }

    @Override public boolean supportsAudio() {
        return complexModel != null && complexModel.supportsAudio();
    }
}
