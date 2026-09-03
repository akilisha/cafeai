package io.meridian.invoice.sentiment;

import io.cafeai.core.CafeAI;

/**
 * Analyses the tone and urgency of a vendor email body.
 *
 * <p>Makes a direct {@code app.prompt().returning(SentimentResult.class)} call —
 * CafeAI appends the JSON schema hint and parses the response into the record.
 *
 * <p>One responsibility: analyse sentiment. Nothing else.
 *
 * <p>Unlike the reconciliation agent's {@code @Tool} methods (which LangChain4j
 * invokes autonomously), this is an explicit orchestration call — the batch
 * processor decides when to run it, not the model.
 */
public class EmailSentimentAnalyzer {

    private final CafeAI app;

    public EmailSentimentAnalyzer(CafeAI app) {
        this.app = app;
    }

    /**
     * Analyses the sentiment of a vendor email body.
     *
     * @param emailBody the plain text body of the vendor email
     * @return structured sentiment result
     */
    public SentimentResult analyze(String emailBody) {
        return app.prompt(buildPrompt(emailBody))
            .returning(SentimentResult.class)
            .call(SentimentResult.class);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String buildPrompt(String emailBody) {
        return """
            Analyse the sentiment of the following vendor email sent to \
            Meridian Home Loans accounts payable.

            Classify the email and respond with ONLY a valid JSON object.
            Do not include any explanation, preamble, or markdown formatting.
            Do not wrap the JSON in code blocks.

            JSON schema:
            {
              "tone": "<POSITIVE|NEUTRAL|FRUSTRATED|HOSTILE>",
              "urgency": "<LOW|MEDIUM|HIGH|CRITICAL>",
              "escalate": <true|false>,
              "keyPhrases": ["<phrase1>", "<phrase2>"],
              "recommendedAction": "<one sentence>"
            }

            Escalation rules:
            - escalate=true if tone is HOSTILE
            - escalate=true if urgency is CRITICAL
            - escalate=true if the email contains legal threats, \
            service suspension warnings, or collection notices
            - escalate=false for FRUSTRATED tone unless combined \
            with CRITICAL urgency

            Vendor email:
            ---
            %s
            ---
            """.formatted(emailBody);
    }
}
