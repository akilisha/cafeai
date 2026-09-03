package io.meridian.invoice.reconciliation;

import io.cafeai.core.CafeAI;
import io.meridian.invoice.extraction.InvoiceData;

/**
 * Reconciles an extracted invoice amount against the contracted amount by
 * invoking the {@link ReconciliationAgent} — a LangChain4j {@code AiService}
 * registered as {@code app.agent("reconciler", ReconciliationAgent.class)}.
 *
 * <p>The agent runs the tool-calling loop ({@code VendorContractLookup},
 * {@code DiscrepancyRecorder}, {@code InvoiceApprover}) and returns a typed
 * {@link ReconciliationVerdict}. This class computes the variance figures in
 * Java so they stay exact, and assembles the {@link ReconciliationResult}.
 *
 * <p>One responsibility: reconcile one invoice. Nothing else.
 */
public class InvoiceAmountReconciler {

    private final CafeAI app;

    public InvoiceAmountReconciler(CafeAI app) {
        this.app = app;
    }

    public ReconciliationResult reconcile(InvoiceData invoice) {
        if (!invoice.isComplete()) {
            throw new IllegalArgumentException(
                "Invoice is incomplete — missing vendor, invoice number, or total amount. "
                + "Cannot reconcile: " + invoice);
        }

        double invoicedAmount = Double.parseDouble(
            invoice.totalAmount().replaceAll("[^0-9.]", ""));

        ReconciliationAgent agent =
            app.agent("reconciler", ReconciliationAgent.class, null);

        ReconciliationVerdict v = agent.reconcile(buildContext(invoice, invoicedAmount));

        double variance    = invoicedAmount - v.contractedAmount();
        double variancePct = v.contractedAmount() > 0
            ? (variance / v.contractedAmount()) * 100.0 : 0;

        return new ReconciliationResult(
            v.decision(),
            v.vendorId(),
            v.vendorName() != null && !v.vendorName().isBlank()
                ? v.vendorName() : invoice.vendorName(),
            invoice.invoiceNumber(),
            invoicedAmount,
            v.contractedAmount(),
            variance,
            variancePct,
            v.tolerancePct(),
            v.explanation(),
            v.vendorEmail());
    }

    private String buildContext(InvoiceData invoice, double invoicedAmount) {
        return String.format("""
            Reconcile this vendor invoice against our billing system.

            - Vendor: %s
            - Invoice Number: %s
            - Invoice Date: %s
            - PO Number: %s
            - Invoiced Amount: $%.2f
            """,
            invoice.vendorName(),
            invoice.invoiceNumber(),
            invoice.invoiceDate() != null ? invoice.invoiceDate() : "unknown",
            invoice.poNumber()    != null ? invoice.poNumber()    : "unknown",
            invoicedAmount);
    }
}
