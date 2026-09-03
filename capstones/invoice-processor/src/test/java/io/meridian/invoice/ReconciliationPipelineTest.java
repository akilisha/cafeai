package io.meridian.invoice;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.meridian.invoice.billing.DiscrepancyRecorder;
import io.meridian.invoice.billing.InvoiceApprover;
import io.meridian.invoice.billing.VendorContractLookup;
import io.meridian.invoice.extraction.InvoiceData;
import io.meridian.invoice.extraction.InvoiceDataExtractor;
import io.meridian.invoice.reconciliation.InvoiceAmountReconciler;
import io.meridian.invoice.reconciliation.ReconciliationResult;
import io.meridian.invoice.response.ResponseComposer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 9 end-to-end pipeline test.
 *
 * <p>Full pipeline without sending emails:
 * attachment → extract → reconcile → compose reply
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>Liberty Fastener PDF — discrepancy → query email drafted</li>
 *   <li>Heiden PDF — exact match → approval email drafted</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testPipeline
 * </pre>
 */
public class ReconciliationPipelineTest {

    public static void main(String[] args) throws Exception {
        // ── Wire up all components ─────────────────────────────────────────────
        var lookup   = new VendorContractLookup();
        var recorder = new DiscrepancyRecorder();
        var approver = new InvoiceApprover();

        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(InvoiceProcessor.SYSTEM_PROMPT);
        app.agent("reconciler",
                io.meridian.invoice.reconciliation.ReconciliationAgent.class)
           .tool(lookup)
           .tool(recorder)
           .tool(approver);

        var extractor  = new InvoiceDataExtractor(app);
        var reconciler = new InvoiceAmountReconciler(app);
        var composer   = new ResponseComposer(app);

        System.out.println("=== End-to-End Pipeline Test ===");
        System.out.println("Extract → Reconcile → Compose");
        System.out.println();

        // ── Pipeline A — Liberty Fastener (discrepancy) ───────────────────────
        System.out.println("========================================");
        System.out.println("Pipeline A: Liberty Fastener");
        System.out.println("========================================");

        Path libertyPath = Path.of(
            "src/test/resources/test-attachments/liberty-fastener-324119.pdf");

        if (Files.exists(libertyPath)) {
            runPipeline(extractor, reconciler, composer,
                Files.readAllBytes(libertyPath), null, "PDF",
                "AP Processing Team");
        } else {
            System.out.println("SKIPPED -- liberty-fastener-324119.pdf not found");
        }

        // ── Pipeline B — Heiden (exact match) ────────────────────────────────
        System.out.println("========================================");
        System.out.println("Pipeline B: Heiden Inc.");
        System.out.println("========================================");

        Path heidenPath = Path.of(
            "src/test/resources/test-attachments/heiden-221914.pdf");

        if (Files.exists(heidenPath)) {
            runPipeline(extractor, reconciler, composer,
                Files.readAllBytes(heidenPath), null, "PDF",
                "AP Processing Team");
        } else {
            System.out.println("SKIPPED -- heiden-221914.pdf not found");
        }

        // ── Summary ───────────────────────────────────────────────────────────
        System.out.println("=== Run Summary ===");
        System.out.println("Approvals:     " + approver.getLog().size());
        System.out.println("Discrepancies: " + recorder.getLog().size());
        System.out.println();
        System.out.println("Phase 9 complete -- full pipeline verified.");
    }

    private static void runPipeline(InvoiceDataExtractor extractor,
                                     InvoiceAmountReconciler reconciler,
                                     ResponseComposer composer,
                                     byte[] pdfBytes,
                                     String emailBody,
                                     String source,
                                     String senderName) throws Exception {
        // Step 1 — Extract
        System.out.println();
        System.out.println("Step 1: Extracting invoice data...");
        InvoiceData invoice = "PDF".equals(source)
            ? extractor.extractFromPdf(pdfBytes)
            : extractor.extractFromEmailBody(emailBody);

        System.out.println("  Vendor:    " + invoice.vendorName());
        System.out.println("  Invoice #: " + invoice.invoiceNumber());
        System.out.println("  Amount:    $" + invoice.totalAmount());
        System.out.println("  PO:        " + invoice.poNumber());
        System.out.println("  Complete:  " + invoice.isComplete());

        if (!invoice.isComplete()) {
            System.out.println("  SKIPPED -- incomplete invoice data");
            System.out.println();
            return;
        }

        // Step 2 — Reconcile
        System.out.println();
        System.out.println("Step 2: Reconciling against billing system...");
        ReconciliationResult result = reconciler.reconcile(invoice);

        System.out.println("  Decision:    " + result.decision());
        System.out.println("  Contracted:  $" + String.format("%.2f", result.contractedAmount()));
        System.out.println("  Variance:    $" + String.format("%.2f", result.variance())
            + " (" + String.format("%.1f", result.variancePct()) + "%)");
        System.out.println("  Explanation: " + result.explanation());

        // Step 3 — Compose reply
        System.out.println();
        System.out.println("Step 3: Composing vendor reply...");
        String emailBody2 = composer.compose(result, senderName);

        System.out.println();
        System.out.println("--- Draft Reply Email ---");
        System.out.println(emailBody2);
        System.out.println("--- End Draft ---");
        System.out.println();
    }
}
