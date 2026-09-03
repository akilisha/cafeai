package io.meridian.invoice.reconciliation;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * The reconciliation agent — a LangChain4j {@code AiService} registered with
 * CafeAI as {@code app.agent("reconciler", ReconciliationAgent.class)}.
 *
 * <p>Given an extracted invoice, it drives a tool-calling loop over
 * {@code VendorContractLookup}, {@code DiscrepancyRecorder}, and
 * {@code InvoiceApprover}, then returns a typed {@link ReconciliationVerdict}.
 * LangChain4j owns the loop and the JSON parsing; the caller
 * ({@link InvoiceAmountReconciler}) computes the variance figures in Java so
 * they stay exact.
 */
public interface ReconciliationAgent {

    @SystemMessage("""
        You reconcile a single vendor invoice for Meridian Home Loans accounts payable.

        Steps, in order:
        1. Call lookupVendorByName with the vendor name to get the vendor ID and contact email.
        2. Call getContractedAmount with the vendor ID and PO number to get the contracted
           amount and tolerance. For FedEx monthly invoices use poNumber 'MONTHLY';
           for Honeywell quarterly royalties use poNumber 'Q3-2024'.
        3. Compare the invoiced amount against the contracted amount and tolerance.
        4. If within tolerance: call approveInvoice, then return decision APPROVED.
           If outside tolerance: call recordDiscrepancy, then return decision
           DISCREPANCY_LOGGED. If the vendor or contract cannot be found, return
           decision QUERIED.
        5. Return the verdict. Never invent a contracted amount — it must come from the tool.
        """)
    ReconciliationVerdict reconcile(@UserMessage String invoiceContext);
}
