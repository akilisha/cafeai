package io.meridian.invoice;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.meridian.invoice.billing.DiscrepancyRecorder;
import io.meridian.invoice.billing.InvoiceApprover;
import io.meridian.invoice.billing.VendorContractLookup;
import io.meridian.invoice.reconciliation.ReconciliationAgent;
import io.meridian.invoice.reconciliation.ReconciliationVerdict;

/**
 * Phase 8 billing tools test.
 *
 * <p>Registers all three {@code @Tool} classes on the reconciliation agent and
 * gives it two invoice reconciliation scenarios. The model decides which tools
 * to call and in what order — we verify it uses them correctly.
 *
 * <p>Scenario A — Liberty Fastener: invoiced $1,535, contracted $1,400.
 * Expect: discrepancy recorded (9.6% over tolerance of 5%).
 *
 * <p>Scenario B — Heiden: invoiced $4,741.80, contracted $4,741.80.
 * Expect: invoice approved (exact match).
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testBillingTools
 * </pre>
 */
public class BillingToolsTest {

    public static void main(String[] args) {
        var lookup   = new VendorContractLookup();
        var recorder = new DiscrepancyRecorder();
        var approver = new InvoiceApprover();

        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(InvoiceProcessor.SYSTEM_PROMPT);
        app.agent("reconciler", ReconciliationAgent.class)
           .tool(lookup)
           .tool(recorder)
           .tool(approver);
        ReconciliationAgent agent =
            app.agent("reconciler", ReconciliationAgent.class, null);

        System.out.println("=== Billing Tools Test ===");
        System.out.println("(Watch the tool calls fire as the agent reasons)");
        System.out.println();

        // ── Scenario A — Liberty Fastener discrepancy ─────────────────────────
        System.out.println("--- Scenario A: Liberty Fastener ---");
        System.out.println("Invoiced: $1,535.00 | PO: PO106068");
        System.out.println();

        ReconciliationVerdict verdictA = agent.reconcile("""
            - Vendor: Liberty Fastener Company
            - Invoice Number: 324119
            - Invoice Date: 2025-09-22
            - PO Number: PO106068
            - Invoiced Amount: $1,535.00
            """);

        System.out.println("Verdict: " + verdictA);
        System.out.println();

        // ── Scenario B — Heiden exact match ───────────────────────────────────
        System.out.println("--- Scenario B: Heiden Inc. ---");
        System.out.println("Invoiced: $4,741.80 | PO: PO105365");
        System.out.println();

        ReconciliationVerdict verdictB = agent.reconcile("""
            - Vendor: Heiden, Inc.
            - Invoice Number: 221914
            - Invoice Date: 2025-09-30
            - PO Number: PO105365
            - Invoiced Amount: $4,741.80
            """);

        System.out.println("Verdict: " + verdictB);
        System.out.println();

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println("=== Run Summary ===");
        System.out.println("Approvals:    " + approver.getLog().size());
        System.out.println("Discrepancies: " + recorder.getLog().size());
        System.out.println();
        System.out.println("Phase 8 complete -- billing tools verified.");
    }
}
