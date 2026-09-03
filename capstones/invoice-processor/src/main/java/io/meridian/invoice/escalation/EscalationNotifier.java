package io.meridian.invoice.escalation;

import io.cafeai.core.CafeAI;
import io.meridian.invoice.gmail.GmailEmailBodyReader.EmailContent;
import io.meridian.invoice.sentiment.SentimentResult;

/**
 * Handles the escalation path for hostile or critically urgent vendor emails.
 *
 * <p>When {@link SentimentResult#escalate()} is true, this class:
 * <ol>
 *   <li>Composes an escalation note to the AP supervisor</li>
 *   <li>Composes an immediate acknowledgement to the vendor</li>
 * </ol>
 *
 * <p>Both emails are drafted via {@code app.prompt()} — the LLM writes them.
 * Actual sending is done by the caller via {@link io.meridian.invoice.gmail.GmailEmailSender}.
 *
 * <p>One responsibility: compose escalation communications. Nothing else.
 */
public class EscalationNotifier {

    private final CafeAI app;

    public EscalationNotifier(CafeAI app) {
        this.app = app;
    }

    /**
     * Composes an escalation note to the AP supervisor.
     *
     * @param email     the original vendor email
     * @param sentiment the sentiment analysis result that triggered escalation
     * @return plain text escalation note body
     */
    public String composeEscalationNote(EmailContent email,
                                         SentimentResult sentiment) {
        String prompt = String.format("""
            Write an internal escalation note to the Accounts Payable supervisor
            at Meridian Home Loans.

            A vendor email has been flagged for immediate escalation.

            Email details:
            - From: %s
            - Subject: %s
            - Tone detected: %s
            - Urgency level: %s
            - Key phrases triggering escalation: %s
            - Recommended action: %s

            Email body:
            ---
            %s
            ---

            The escalation note should:
            - Briefly summarise why this email was escalated
            - Quote the most concerning phrases verbatim
            - Recommend immediate action
            - Be concise — this is an internal alert, not a formal letter
            - Include a reference timestamp

            Write only the note body — no subject line.
            """,
            email.from(),
            email.subject(),
            sentiment.tone(),
            sentiment.urgency(),
            String.join(", ", sentiment.keyPhrases()),
            sentiment.recommendedAction(),
            email.body());

        return app.prompt(prompt).call().text();
    }

    /**
     * Composes an immediate vendor acknowledgement for escalated emails.
     *
     * <p>The vendor receives a professional holding response while the
     * escalation is reviewed. The tone matches the urgency without
     * making any commitments.
     *
     * @param email     the original vendor email
     * @param sentiment the sentiment analysis result
     * @param senderName name to sign the email with
     * @return plain text acknowledgement email body
     */
    public String composeVendorAcknowledgement(EmailContent email,
                                                SentimentResult sentiment,
                                                String senderName) {
        String prompt = String.format("""
            Write a professional vendor acknowledgement email for an escalated
            accounts payable situation at Meridian Home Loans.

            Context:
            - Vendor email subject: %s
            - Tone of their email: %s
            - Urgency: %s
            - Their recommended action: %s

            The acknowledgement should:
            - Confirm we have received their email
            - Acknowledge the urgency of the situation
            - Confirm the matter has been escalated to our AP supervisor
            - State that a substantive response will follow within 4 business hours
            - NOT make any payment commitments or concessions
            - Match a calm, professional tone regardless of their tone
            - Be concise — 3-4 sentences
            - Sign off as: %s, Meridian Home Loans Accounts Payable

            Write only the email body — no subject line.
            """,
            email.subject(),
            sentiment.tone(),
            sentiment.urgency(),
            sentiment.recommendedAction(),
            senderName);

        return app.prompt(prompt).call().text();
    }
}
