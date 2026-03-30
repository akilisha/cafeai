package io.cafeai.core;

/**
 * Pre-defined keys for per-request attributes stored in {@code req.setAttribute()}.
 *
 * <p>Distinct from {@link Locals} (application-lifetime infrastructure) --
 * attributes are scoped to a single HTTP request and do not survive it.
 *
 * <p>These constants eliminate magic strings in middleware chains:
 *
 * <pre>{@code
 *   // RAG middleware stores retrieved documents:
 *   req.setAttribute(Attributes.RAG_DOCUMENTS, retrievedChunks);
 *
 *   // Handler reads them:
 *   List<RagDocument> docs = req.attribute(Attributes.RAG_DOCUMENTS, List.class);
 * }</pre>
 */
public final class Attributes {

    private Attributes() {}

    // -- Auth ------------------------------------------------------------------

    /**
     * The authenticated principal for the current request.
     * Set by authentication middleware, read by authorization middleware
     * and route handlers.
     * Type: application-defined principal object.
     */
    public static final String AUTH_PRINCIPAL   = "cafeai.auth.principal";

    // -- RAG -------------------------------------------------------------------

    /**
     * List of documents retrieved by the RAG pipeline for the current request.
     * Set by the RAG retrieval middleware before the LLM call.
     * Type: {@code List<io.cafeai.rag.RagDocument>}
     */
    public static final String RAG_DOCUMENTS    = "cafeai.rag.documents";

    /**
     * The rendered RAG context string injected into the LLM prompt.
     * Contains the concatenated content of retrieved documents.
     * Type: {@code String}
     */
    public static final String RAG_CONTEXT      = "cafeai.rag.context";

    // -- Guardrails ------------------------------------------------------------

    /**
     * Guardrail evaluation score for the current request/response.
     * Attached by guardrail middleware (PII, hallucination, bias).
     * Type: {@code Double} (0.0 = clean, 1.0 = fully triggered)
     */
    public static final String GUARDRAIL_SCORE  = "cafeai.guardrail.score";

    /**
     * Name of the guardrail that triggered, if any.
     * Type: {@code String}
     */
    public static final String GUARDRAIL_NAME   = "cafeai.guardrail.name";

    // -- LLM Response ----------------------------------------------------------

    /**
     * The text of the LLM response for the current request.
     *
     * <p>Set by {@code CafeAIApp.executePrompt()} after the LLM call completes.
     * Read by POST_LLM guardrails to inspect the model's output before it
     * reaches the handler.
     *
     * <p>Type: {@code String}
     */
    public static final String LLM_RESPONSE_TEXT = "cafeai.llm.responseText";

    // -- Observability ---------------------------------------------------------

    /**
     * Evaluation scores computed for the current RAG response.
     * Attached by the observability layer.
     * Type: {@code Map<String, Double>} keyed by metric name.
     */
    public static final String EVAL_SCORES      = "cafeai.eval.scores";
}
