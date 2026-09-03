package io.meridian.invoice.reconciliation;

/**
 * What the {@link ReconciliationAgent} produces — the decision plus the facts
 * it gathered from the billing tools. The numeric variance fields on
 * {@link ReconciliationResult} are computed in Java from these, so they never
 * depend on the model doing arithmetic.
 */
public record ReconciliationVerdict(
    ReconciliationResult.Decision decision,
    String vendorId,
    String vendorName,
    double contractedAmount,
    double tolerancePct,
    String explanation,
    String vendorEmail
) {}
