package io.meridian.invoice.sentiment;

import java.util.List;

/**
 * Structured result from {@link EmailSentimentAnalyzer}.
 *
 * <p>Immutable record. All fields are populated by JSON parsing
 * of the LLM's response — never constructed manually in production code.
 *
 * <p>The {@code escalate} flag is the decision gate:
 * when true, the email bypasses normal reconciliation and goes
 * directly to {@code EscalationNotifier}.
 */
public record SentimentResult(

    /**
     * Overall tone of the email.
     * One of: POSITIVE, NEUTRAL, FRUSTRATED, HOSTILE
     */
    String tone,

    /**
     * Urgency level inferred from language and context.
     * One of: LOW, MEDIUM, HIGH, CRITICAL
     */
    String urgency,

    /**
     * Whether this email should bypass normal processing
     * and be escalated to the AP supervisor immediately.
     */
    boolean escalate,

    /**
     * The specific phrases in the email that drove the
     * tone and urgency classification. Useful for audit
     * trails and for explaining the escalation decision.
     */
    List<String> keyPhrases,

    /**
     * One-sentence summary of the recommended action.
     * Example: "Expedite payment review — vendor is frustrated
     * and invoice is 21 days overdue."
     */
    String recommendedAction

) {
    /** Convenience — is this a high-urgency situation? */
    public boolean isUrgent() {
        return "HIGH".equals(urgency) || "CRITICAL".equals(urgency);
    }

    /** Convenience — is the tone negative? */
    public boolean isNegative() {
        return "FRUSTRATED".equals(tone) || "HOSTILE".equals(tone);
    }
}
