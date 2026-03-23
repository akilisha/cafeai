package io.cafeai.core.ai;

/**
 * Routes prompts to different LLM models based on complexity.
 *
 * <p>Reduces cost significantly without sacrificing quality — simple queries
 * go to a cheaper, faster model; complex queries go to a more capable model.
 *
 * <pre>{@code
 *   app.ai(ModelRouter.smart()
 *       .simple(OpenAI.gpt4oMini())
 *       .complex(OpenAI.gpt4o()));
 * }</pre>
 */
public final class ModelRouter {

    private AiProvider simpleModel;
    private AiProvider complexModel;

    private ModelRouter() {}

    /** Creates a smart model router builder. */
    public static ModelRouter smart() {
        return new ModelRouter();
    }

    /**
     * Sets the model for simple queries — cheap, fast.
     * Used for classification, short responses, single-turn queries.
     */
    public ModelRouter simple(AiProvider provider) {
        this.simpleModel = provider;
        return this;
    }

    /**
     * Sets the model for complex queries — more capable, higher cost.
     * Used for multi-step reasoning, tool use, long context.
     */
    public ModelRouter complex(AiProvider provider) {
        this.complexModel = provider;
        return this;
    }

    public AiProvider simpleModel()  { return simpleModel; }
    public AiProvider complexModel() { return complexModel; }
}
