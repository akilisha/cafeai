package io.cafeai.core.guardrails;

import io.cafeai.core.middleware.Middleware;

/**
 * Ethical, regulatory, and safety guardrails for CafeAI.
 *
 * <p>Guardrails are middleware. They sit in the pipeline like any other
 * concern — composable, testable, replaceable. This is intentional.
 * In regulated industries (finance, healthcare, insurance), guardrails
 * are not optional bolt-ons. They are first-class architectural requirements.
 * CafeAI treats them as such.
 *
 * <p>Two positions in the pipeline:
 * <pre>
 *   PRE-LLM  guardrails → inspect/transform the prompt before it hits the model
 *   POST-LLM guardrails → inspect/transform the response before it hits the user
 * </pre>
 *
 * <pre>{@code
 *   app.guard(GuardRail.pii());               // scrub PII pre and post
 *   app.guard(GuardRail.jailbreak());         // detect adversarial prompts
 *   app.guard(GuardRail.bias());              // detect biased outputs
 *   app.guard(GuardRail.hallucination());     // score factual grounding
 *   app.guard(GuardRail.toxicity());          // filter toxic content
 *   app.guard(GuardRail.promptInjection());   // detect injection attacks
 *   app.guard(GuardRail.regulatory()          // GDPR, HIPAA, FCRA
 *       .gdpr()
 *       .hipaa());
 *   app.guard(myCustomGuard);                 // bring your own
 * }</pre>
 */
public interface GuardRail extends Middleware {

    // ── Built-in Guardrail Factory Methods ───────────────────────────────────

    /**
     * PII detection and scrubbing — pre and post LLM.
     * Detects names, SSNs, emails, phone numbers, credit cards, addresses.
     * Scrubs from prompts before they reach the model.
     * Masks in responses before they reach the user.
     */
    static GuardRail pii() {
        return new PiiGuardRail();
    }

    /**
     * Jailbreak detection.
     * Identifies adversarial prompts attempting to bypass system instructions,
     * override the AI persona, or extract training data.
     */
    static GuardRail jailbreak() {
        return new JailbreakGuardRail();
    }

    /**
     * Prompt injection detection.
     * Detects malicious content embedded in user input or RAG-retrieved
     * documents that attempts to hijack the agent's instructions.
     * Distinct from jailbreak — injection comes from data, not the user.
     */
    static GuardRail promptInjection() {
        return new PromptInjectionGuardRail();
    }

    /**
     * Bias detection in model outputs.
     * Flags responses exhibiting demographic, racial, gender, or
     * socioeconomic bias. Critical for loan, hiring, and insurance use cases.
     */
    static GuardRail bias() {
        return new BiasGuardRail();
    }

    /**
     * Hallucination scoring.
     * Measures factual grounding of the response against the RAG corpus
     * and the conversation context. Scores are attached to the response
     * as metadata and visible in the observability trace.
     */
    static GuardRail hallucination() {
        return new HallucinationGuardRail();
    }

    /**
     * Toxicity and harmful content filter.
     * Blocks or flags responses containing hate speech, violence,
     * self-harm content, or explicit material.
     */
    static GuardRail toxicity() {
        return new ToxicityGuardRail();
    }

    /**
     * Regulatory compliance guardrail builder.
     * Compose the regulations relevant to your domain.
     *
     * <pre>{@code
     *   app.guard(GuardRail.regulatory()
     *       .gdpr()    // EU data protection
     *       .hipaa()   // US healthcare
     *       .fcra()    // US fair credit
     *       .ccpa()); // California privacy
     * }</pre>
     */
    static RegulatoryGuardRail regulatory() {
        return new RegulatoryGuardRail();
    }

    /**
     * Topic boundary enforcer.
     * Keeps the AI on-topic for your use case.
     * Prevents scope creep in customer-facing deployments.
     *
     * <pre>{@code
     *   app.guard(GuardRail.topicBoundary()
     *       .allow("customer service", "product questions", "order status")
     *       .deny("politics", "medical advice", "legal advice"));
     * }</pre>
     */
    static TopicBoundaryGuardRail topicBoundary() {
        return new TopicBoundaryGuardRail();
    }

    // ── GuardRail Metadata ───────────────────────────────────────────────────

    /** Returns the position of this guardrail in the pipeline. */
    Position position();

    /** Returns a human-readable name for observability traces. */
    String name();

    enum Position {
        PRE_LLM,
        POST_LLM,
        BOTH
    }
}
