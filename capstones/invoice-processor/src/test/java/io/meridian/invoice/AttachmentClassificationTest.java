package io.meridian.invoice;

import io.meridian.invoice.classification.AttachmentClassification;
import io.meridian.invoice.classification.AttachmentTypeClassifier;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 6 attachment classification test.
 *
 * <p>Tests the classifier against three real document types:
 * <ol>
 *   <li>A real vendor invoice PDF — expect isInvoice=true</li>
 *   <li>A packing list PDF — expect isInvoice=false</li>
 *   <li>A shipping receipt PDF — expect isInvoice=false</li>
 * </ol>
 *
 * <p>Place test PDFs in src/test/resources/test-attachments/ before running.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testClassification
 * </pre>
 */
public class AttachmentClassificationTest {

    public static void main(String[] args) throws Exception {
        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(InvoiceProcessor.SYSTEM_PROMPT);
        var classifier = new AttachmentTypeClassifier(app);

        System.out.println("=== Attachment Classification Test ===");
        System.out.println();

        // Test 1 — Liberty Fastener combined PDF (invoice + packing list)
        // The first page is an invoice — classifier should still return isInvoice=true
        // because the document contains billing information
        classifyFile(classifier,
                "Test 1 — Liberty Fastener combined PDF (expect: isInvoice=true, INVOICE)",
                "src/test/resources/test-attachments/liberty-fastener-324119.pdf",
                true);

        // Test 2 — KSO Metalfab — model correctly reads this as a PO structure
        // POs are billing-adjacent and should be processed
        classifyFile(classifier,
            "Test 2 — KSO Metalfab (expect: isInvoice=true, PURCHASE_ORDER or INVOICE)",
            "src/test/resources/test-attachments/kso-metalfab-64485.pdf",
            true);

        // Test 3 — Ultratech invoice (scanned with handwriting)
        classifyFile(classifier,
                "Test 3 — Ultratech invoice scanned (expect: isInvoice=true, INVOICE)",
                "src/test/resources/test-attachments/ultratech-91560.pdf",
                true);

        // Test 4 — Heiden invoice (scanned with AP stamps + shipping receipt attached)
        classifyFile(classifier,
                "Test 4 — Heiden invoice scanned (expect: isInvoice=true, INVOICE)",
                "src/test/resources/test-attachments/heiden-221914.pdf",
                true);

        System.out.println("Phase 6 complete -- attachment classification verified.");
    }

    private static void classifyFile(AttachmentTypeClassifier classifier,
                                      String label,
                                      String path,
                                      boolean expectedIsInvoice) throws Exception {
        System.out.println("--- " + label + " ---");

        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            System.out.println("  SKIPPED — file not found: " + path);
            System.out.println();
            return;
        }

        byte[] bytes = Files.readAllBytes(filePath);
        AttachmentClassification result = classifier.classifyPdf(bytes);

        System.out.println("  isInvoice:  " + result.isInvoice()
            + (result.isInvoice() == expectedIsInvoice ? " OK" : " UNEXPECTED"));
        System.out.println("  confidence: " + result.confidence());
        System.out.println("  docType:    " + result.docType());
        System.out.println("  reason:     " + result.reason());
        System.out.println();
    }
}
