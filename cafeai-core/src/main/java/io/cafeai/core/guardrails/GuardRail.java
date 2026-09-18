package io.cafeai.core.guardrails;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Ethical, regulatory, and safety guardrails for CafeAI.
 *
 * <p>Guardrails are middleware -- composable, testable, replaceable.
 * They sit in the pipeline at {@code PRE_LLM}, {@code POST_LLM}, or both.
 * In regulated industries, guardrails are first-class architectural requirements.
 * CafeAI treats them as such (ADR-002).
 *
 * <p><strong>Enforcement.</strong> A guardrail registered with {@code app.guard(...)} is applied
 * by the engine to every {@code app.prompt()}, {@code .vision()} and {@code .audio()} call —
 * streamed or not — using {@link #checkInput(String)} on the text the model is about to see and
 * {@link #checkOutput(String)} on what it returned, and to {@code app.agent(...)} through the
 * LangChain4j guardrail adapters. Its {@link Action} decides what a violation does:
 * <ul>
 *   <li>{@code BLOCK} — input throws {@link GuardRailViolationException} and <em>no model call is
 *       made</em>; output is replaced with a refusal.</li>
 *   <li>{@code WARN} / {@code LOG} — the violation is logged and the call proceeds.</li>
 * </ul>
 * A streamed response cannot be retracted once tokens are sent, so for {@code .stream()} a
 * {@code POST_LLM} guardrail gates what is remembered and exposed, not the tokens themselves. Use
 * {@code .call()} when output must be screened before anything is released. The
 * {@link #handle} middleware form only screens the HTTP body; it runs after the route handler, so
 * it cannot stop a response that handler has already sent — the engine path is what enforces.
 *
 * <p>The pattern-based implementations are provided by {@code cafeai-guardrails}. Without
 * that module on the classpath, every such factory method throws
 * {@link GuardRailModuleNotFoundException} rather than return a guardrail that passes everything
 * through — a safety control that silently does nothing is worse than none. Add the dependency:
 *
 * <pre>{@code
 *   // build.gradle
 *   implementation 'com.akilisha.oss:cafeai-guardrails'
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 *   app.guard(GuardRail.pii());
 *   app.guard(GuardRail.jailbreak());
 *   app.guard(GuardRail.regulatory().gdpr().hipaa());
 *   app.guard(GuardRail.topicBoundary()
 *       .allow("customer service", "orders")
 *       .deny("politics", "medical advice"));
 * }</pre>
 */
public interface GuardRail extends Middleware {

    /** Human-readable name used in observability traces and logs. */
    String name();

    /** Position in the pipeline -- before LLM, after LLM, or both. */
    Position position();

    /** What happens when this guardrail triggers. */
    Action action();

    // -- Factory Methods -------------------------------------------------------
    // The pattern-based factories delegate to GuardRailProvider (cafeai-guardrails).
    // Without it they throw GuardRailModuleNotFoundException -- never a silent no-op.

    /** PII detection and scrubbing -- pre and post LLM. */
    static GuardRail pii() {
        return requireProvider("GuardRail.pii()").pii();
    }

    /** Adversarial prompt / jailbreak detection. */
    static GuardRail jailbreak() {
        return requireProvider("GuardRail.jailbreak()").jailbreak();
    }

    /** Data-sourced prompt injection detection -- checks user input and RAG documents. */
    static GuardRail promptInjection() {
        return requireProvider("GuardRail.promptInjection()").promptInjection();
    }

    /**
     * Content moderation by a LangChain4j {@code ModerationModel} — a model, not a pattern list.
     * Works without {@code cafeai-guardrails}, since it needs no CafeAI implementation module.
     * See {@link ModerationGuardRail}.
     *
     * <pre>{@code
     *   app.guard(GuardRail.moderation(OpenAI.moderation("omni-moderation-latest")));
     * }</pre>
     */
    static ModerationGuardRail moderation(dev.langchain4j.model.moderation.ModerationModel model) {
        return ModerationGuardRail.of(model);
    }

    /**
     * Credentials and secrets — API keys, tokens, private keys, connection strings — in what users
     * send (they end up at a third-party model) and in what the model says. Never includes the
     * secret itself in a report. Requires {@code cafeai-guardrails}.
     */
    static GuardRail secrets() {
        return requireProvider("GuardRail.secrets()").secrets();
    }

    /**
     * Stops the model repeating {@code systemPrompt} back to the user. Works without
     * {@code cafeai-guardrails}. See {@link PromptLeakGuardRail} for how it decides and what it
     * cannot catch.
     *
     * <pre>{@code
     *   app.system(prompt);
     *   app.guard(GuardRail.promptLeak(prompt));
     * }</pre>
     */
    static PromptLeakGuardRail promptLeak(String systemPrompt) {
        return PromptLeakGuardRail.of(systemPrompt);
    }

    /** Toxic and harmful content filtering. */
    static GuardRail toxicity() {
        return requireProvider("GuardRail.toxicity()").toxicity();
    }

    /**
     * Regulatory compliance guardrail builder -- GDPR, HIPAA, FCRA, CCPA.
     *
     * <p>Requires {@code cafeai-guardrails}; throws {@link GuardRailModuleNotFoundException} without it.
     */
    static RegulatoryGuardRail regulatory() {
        return (RegulatoryGuardRail) requireProvider("GuardRail.regulatory()").regulatory();
    }

    /**
     * Topic scope enforcement builder.
     *
     * <p>Requires {@code cafeai-guardrails}; throws {@link GuardRailModuleNotFoundException} without it.
     */
    static TopicBoundaryGuardRail topicBoundary() {
        return (TopicBoundaryGuardRail) requireProvider("GuardRail.topicBoundary()").topicBoundary();
    }

    // -- Internal helpers ------------------------------------------------------

    private static io.cafeai.core.spi.GuardRailProvider requireProvider(String factory) {
        return java.util.ServiceLoader
            .load(io.cafeai.core.spi.GuardRailProvider.class)
            .findFirst()
            .orElseThrow(() -> new GuardRailModuleNotFoundException(
                factory + " requires the cafeai-guardrails module. Add the dependency:\n\n"
                + "  Gradle: implementation 'com.akilisha.oss:cafeai-guardrails'\n"
                + "  Maven:  <artifactId>cafeai-guardrails</artifactId>\n\n"
                + "CafeAI will not hand you a guardrail that silently passes everything through: "
                + "it would look like protection and be none. Guardrails that need no module "
                + "still work without it: GuardRail.moderation(model), GuardRail.promptLeak(prompt), "
                + "and any GuardRail you implement yourself."));
    }

    // -- Enums -----------------------------------------------------------------

    enum Position { PRE_LLM, POST_LLM, BOTH }

    enum Action   { BLOCK, WARN, LOG }

    // -- POST_LLM output check -------------------------------------------------

    /**
     * The result of a guardrail check.
     *
     * <p>Returned by {@link #checkInput(String)} (PRE_LLM) and
     * {@link #checkOutput(String)} (POST_LLM) to indicate whether the text
     * passes or violates this guardrail.
     *
     * @param isViolation {@code true} if the text violates this guardrail
     * @param reason      human-readable reason for the violation, or {@code null} if passing
     */
    record OutputCheckResult(boolean isViolation, String reason) {
        public static OutputCheckResult pass()              { return new OutputCheckResult(false, null);   }
        public static OutputCheckResult violation(String r) { return new OutputCheckResult(true,  r);     }
    }

    /**
     * Inspects the user's message before it reaches the LLM.
     *
     * <p>Called by {@code CafeAIApp} and the {@code cafeai-agents} input-guardrail
     * adapter for guardrails with position {@link Position#PRE_LLM} or
     * {@link Position#BOTH}. The default passes through — override (or extend
     * {@code AbstractGuardRail}) to screen the prompt.
     *
     * @param input the user's message text
     * @return {@link OutputCheckResult#pass()} to allow, or
     *         {@link OutputCheckResult#violation(String)} to block
     */
    default OutputCheckResult checkInput(String input) {
        return OutputCheckResult.pass();
    }

    /**
     * Inspects the LLM's assembled response text after generation.
     *
     * <p>Called by {@code CafeAIApp} after every LLM call — including the final
     * output of a tool-calling loop — for guardrails with position
     * {@link Position#POST_LLM} or {@link Position#BOTH}.
     *
     * <p>The default implementation passes through (no check). Override in
     * concrete guardrail implementations to inspect the response.
     *
     * @param output the assembled LLM response text
     * @return {@link OutputCheckResult#pass()} to allow, or
     *         {@link OutputCheckResult#violation(String)} to flag a violation
     */
    default OutputCheckResult checkOutput(String output) {
        return OutputCheckResult.pass();
    }

    /**
     * Inspects one document retrieved for RAG before it is placed in the model's context — the
     * <em>indirect</em> route for an injected instruction, where the text comes from your own
     * knowledge base and so looks trusted.
     *
     * <p>Called by {@code CafeAIApp} for each retrieved document, for guardrails at
     * {@link Position#PRE_LLM} or {@link Position#BOTH}. A {@code BLOCK} guardrail's violation
     * <em>drops that document</em> (the request is still answered, from the rest); {@code WARN} and
     * {@code LOG} keep it. The default passes: only a guardrail about instructions hidden in data
     * (see {@code GuardRail.promptInjection()}) has anything to say here. A PII or topic check
     * belongs on the user's message, not on your own documents.
     *
     * @param documentText the retrieved document as it would appear in the model's context
     */
    default OutputCheckResult checkRetrieved(String documentText) {
        return OutputCheckResult.pass();
    }

    // -- Builder types ---------------------------------------------------------

    /**
     * The regulatory guardrail returned by {@link #regulatory()}; each method adds a rule set.
     * Implemented by {@code cafeai-guardrails}.
     */
    interface RegulatoryGuardRail extends GuardRail {
        RegulatoryGuardRail gdpr();
        RegulatoryGuardRail hipaa();
        RegulatoryGuardRail fcra();
        RegulatoryGuardRail ccpa();
        RegulatoryGuardRail ecoa();
        RegulatoryGuardRail fairHousing();
    }

    /**
     * The topic-scope guardrail returned by {@link #topicBoundary()}; allow and deny topics.
     * Implemented by {@code cafeai-guardrails}.
     */
    interface TopicBoundaryGuardRail extends GuardRail {
        TopicBoundaryGuardRail allow(String... topics);
        TopicBoundaryGuardRail deny(String... topics);
    }
}
