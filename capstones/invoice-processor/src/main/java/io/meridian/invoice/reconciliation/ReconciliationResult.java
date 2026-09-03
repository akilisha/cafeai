package io.meridian.invoice.reconciliation;

/**
 * The outcome of comparing an invoiced amount against the contracted amount.
 *
 * <p>Three possible decisions:
 * <ul>
 *   <li>{@code APPROVED} — invoiced amount is within contracted tolerance</li>
 *   <li>{@code QUERIED} — invoiced amount is outside tolerance, vendor needs
 *       to explain or resubmit</li>
 *   <li>{@code DISCREPANCY_LOGGED} — significant discrepancy recorded in the
 *       billing system, flagged for AP supervisor</li>
 * </ul>
 */
public record ReconciliationResult(

    /** The reconciliation decision. */
    Decision decision,

    /** Vendor ID from the billing system. */
    String vendorId,

    /** Vendor name. */
    String vendorName,

    /** Invoice number being reconciled. */
    String invoiceNumber,

    /** Amount as extracted from the invoice. */
    double invoicedAmount,

    /** Amount as per the contract/PO in the billing system. */
    double contractedAmount,

    /** Variance: invoicedAmount - contractedAmount. */
    double variance,

    /** Variance as a percentage of contracted amount. */
    double variancePct,

    /** Tolerance percentage configured for this vendor. */
    double tolerancePct,

    /** Human-readable explanation of the decision. */
    String explanation,

    /** Vendor contact email for the response. */
    String vendorEmail

) {

    public enum Decision {
        APPROVED,
        QUERIED,
        DISCREPANCY_LOGGED
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED;
    }

    public boolean requiresQuery() {
        return decision == Decision.QUERIED;
    }

    public boolean hasDiscrepancy() {
        return decision == Decision.DISCREPANCY_LOGGED;
    }
}
