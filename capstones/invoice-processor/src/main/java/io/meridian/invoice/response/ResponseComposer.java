package io.meridian.invoice.response;

import io.cafeai.core.CafeAI;
import io.meridian.invoice.reconciliation.ReconciliationResult;

/**
 * Drafts the vendor reply email body given a reconciliation result.
 *
 * <p>Makes a direct {@code app.prompt()} call — the LLM writes the email.
 * The tone and content are driven by the reconciliation decision:
 * <ul>
 *   <li>APPROVED — professional acknowledgement, payment processing confirmed</li>
 *   <li>QUERIED — polite request for clarification or revised invoice</li>
 *   <li>DISCREPANCY_LOGGED — formal notification, escalation reference number</li>
 * </ul>
 *
 * <p>One responsibility: compose one reply email. Nothing else.
 */
public class ResponseComposer {

    private final CafeAI app;

    public ResponseComposer(CafeAI app) {
        this.app = app;
    }

    /**
     * Drafts a vendor reply email for the given reconciliation result.
     *
     * @param result    the reconciliation decision and details
     * @param senderName name to sign the email with
     * @return plain text email body ready to send
     */
    public String compose(ReconciliationResult result, String senderName) {
        String prompt = buildPrompt(result, senderName);
        return app.prompt(prompt).call().text();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String buildPrompt(ReconciliationResult result, String senderName) {
        return switch (result.decision()) {

            case APPROVED -> String.format("""
                Write a professional accounts payable reply email to a vendor.

                Context:
                - Vendor: %s
                - Invoice Number: %s
                - Invoiced Amount: $%.2f
                - Decision: APPROVED for AP processing
                - Explanation: %s

                The email should:
                - Thank the vendor for their invoice
                - Confirm the invoice has been received and approved for processing
                - Note that payment will be processed per contract terms
                - Be concise — 3-4 sentences maximum
                - Sign off as: %s, Meridian Home Loans Accounts Payable

                Write only the email body — no subject line, no extra commentary.
                """,
                result.vendorName(), result.invoiceNumber(),
                result.invoicedAmount(), result.explanation(), senderName);

            case QUERIED -> String.format("""
                Write a professional accounts payable reply email to a vendor
                requesting clarification on a billing discrepancy.

                Context:
                - Vendor: %s
                - Invoice Number: %s
                - Invoiced Amount: $%.2f
                - Contracted Amount: $%.2f
                - Variance: $%.2f (%.1f%% over contracted, tolerance is %.1f%%)
                - Explanation: %s

                The email should:
                - Acknowledge receipt of the invoice
                - Politely note the discrepancy between the invoiced and contracted amount
                - Request a revised invoice or written explanation for the difference
                - Provide our AP contact for follow-up questions
                - Be professional and non-accusatory
                - Sign off as: %s, Meridian Home Loans Accounts Payable

                Write only the email body — no subject line, no extra commentary.
                """,
                result.vendorName(), result.invoiceNumber(),
                result.invoicedAmount(), result.contractedAmount(),
                result.variance(), result.variancePct(), result.tolerancePct(),
                result.explanation(), senderName);

            case DISCREPANCY_LOGGED -> String.format("""
                Write a formal accounts payable reply email notifying a vendor
                of a logged billing discrepancy requiring escalated review.

                Context:
                - Vendor: %s
                - Invoice Number: %s
                - Invoiced Amount: $%.2f
                - Contracted Amount: $%.2f
                - Variance: $%.2f (%.1f%% over contracted amount)
                - Explanation: %s

                The email should:
                - Acknowledge receipt of the invoice
                - Formally notify the vendor that a discrepancy has been identified
                - Explain that this has been escalated to our AP supervisor for review
                - Request that the vendor not resubmit until they hear from us
                - Provide a reference that our team will follow up within 2 business days
                - Be formal but professional — not accusatory
                - Sign off as: %s, Meridian Home Loans Accounts Payable

                Write only the email body — no subject line, no extra commentary.
                """,
                result.vendorName(), result.invoiceNumber(),
                result.invoicedAmount(), result.contractedAmount(),
                result.variance(), result.variancePct(),
                result.explanation(), senderName);
        };
    }
}
