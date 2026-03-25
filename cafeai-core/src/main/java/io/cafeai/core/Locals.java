package io.cafeai.core;

/**
 * Pre-defined keys for AI infrastructure locals stored in {@code app.local()}.
 *
 * <p>CafeAI stores AI infrastructure references in the application-scoped
 * locals map so that middleware, guardrails, and observability hooks can
 * access them without a direct reference to the application.
 *
 * <p>These constants eliminate magic strings throughout the codebase:
 *
 * <pre>{@code
 *   // Framework stores these when app.ai(), app.memory(), etc. are called:
 *   app.local(Locals.AI_PROVIDER, provider);
 *   app.local(Locals.MEMORY_STRATEGY, strategy);
 *
 *   // Middleware and plugins read them:
 *   AiProvider p = req.app().local(Locals.AI_PROVIDER, AiProvider.class);
 * }</pre>
 */
public final class Locals {

    private Locals() {}

    // ── AI Infrastructure ─────────────────────────────────────────────────────

    /** The registered {@link io.cafeai.core.ai.AiProvider}. */
    public static final String AI_PROVIDER      = "__cafeai.ai.provider";

    /** The registered {@link io.cafeai.core.ai.ModelRouter}, if any. */
    public static final String MODEL_ROUTER     = "__cafeai.ai.modelRouter";

    /** The system prompt string registered via {@code app.system()}. */
    public static final String SYSTEM_PROMPT    = "__cafeai.ai.systemPrompt";

    /** The registered {@link io.cafeai.core.memory.MemoryStrategy}. */
    public static final String MEMORY_STRATEGY  = "__cafeai.memory.strategy";

    /** The registered vector store (type: {@code EmbeddingStore}). */
    public static final String VECTOR_STORE     = "__cafeai.rag.vectorStore";

    /** The registered embedding model (type: {@code EmbeddingModel}). */
    public static final String EMBEDDING_MODEL  = "__cafeai.rag.embeddingModel";

    // ── Observability ─────────────────────────────────────────────────────────

    /** The registered {@link io.cafeai.core.observability.ObserveStrategy}, if any. */
    public static final String OBSERVE_STRATEGY = "__cafeai.observe.strategy";

    /**
     * Key for the list of registered {@code io.cafeai.connect.Connection} instances.
     * Stored as {@code List<Object>} to avoid a compile-time dependency on cafeai-connect.
     * Used by {@code Connect.healthCheck()} to probe registered services.
     */
    public static final String CONNECTIONS      = "__cafeai.connect.connections";

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given key is a CafeAI-internal locals key
     * (prefixed with {@code __cafeai.}).
     * Internal keys are excluded from {@code app.locals()} snapshots.
     */
    public static boolean isInternal(String key) {
        return key != null && key.startsWith("__cafeai.");
    }
}
