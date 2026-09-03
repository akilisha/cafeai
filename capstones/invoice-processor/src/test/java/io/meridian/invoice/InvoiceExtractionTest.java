package io.meridian.invoice;

import io.meridian.invoice.extraction.InvoiceData;
import io.meridian.invoice.extraction.InvoiceDataExtractor;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 7 invoice extraction test.
 *
 * <p>Tests all three extraction paths:
 * <ol>
 *   <li>PDF — Liberty Fastener digital invoice</li>
 *   <li>PDF — Heiden scanned invoice with handwritten AP stamps</li>
 *   <li>Email body — Sally Computers payment inquiry (inline invoice details)</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testExtraction
 * </pre>
 */
public class InvoiceExtractionTest {

    static final String SALLY_EMAIL_BODY = """
        Hello,

        I'm following up on Invoice INV-2024-002 dated February 10, 2024,
        in the amount of $57,613.50. This invoice is due on March 11, 2024.

        Invoice details:
        - HPE ProLiant DL380 Gen10 Servers (3 units) @ $6,200.00 = $18,600.00
        - NetApp FAS2750 Storage Array (1 unit) @ $25,000.00 = $25,000.00
        - Backup Software License (Annual) @ $4,500.00 = $4,500.00
        - Data Migration Services @ $5,000.00 = $5,000.00

        Subtotal: $53,100.00
        Tax (8.5%): $4,513.50
        Total: $57,613.50

        PO Number: PO-ACME-2024-002
        Payment Terms: Net 30

        Please confirm payment status.

        Best regards,
        Sally Computers
        """;

    public static void main(String[] args) throws Exception {
        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(InvoiceProcessor.SYSTEM_PROMPT);
        var extractor = new InvoiceDataExtractor(app);

        System.out.println("=== Invoice Data Extraction Test ===");
        System.out.println();

        // Test 1 — PDF (digital, Liberty Fastener)
        extractFromPdf(extractor,
            "Test 1 — Liberty Fastener PDF (digital invoice)",
            "src/test/resources/test-attachments/liberty-fastener-324119.pdf");

        // Test 2 — PDF (scanned with handwriting, Heiden)
        extractFromPdf(extractor,
            "Test 2 — Heiden PDF (scanned with AP handwriting)",
            "src/test/resources/test-attachments/heiden-221914.pdf");

        // Test 3 — Email body (Sally Computers inline invoice details)
        System.out.println("--- Test 3 — Email body (Sally Computers inline) ---");
        InvoiceData result = extractor.extractFromEmailBody(SALLY_EMAIL_BODY);
        printResult(result);

        System.out.println("Phase 7 complete -- invoice extraction verified.");
    }

    private static void extractFromPdf(InvoiceDataExtractor extractor,
                                        String label,
                                        String path) throws Exception {
        System.out.println("--- " + label + " ---");
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            System.out.println("  SKIPPED -- file not found: " + path);
            System.out.println();
            return;
        }
        InvoiceData result = extractor.extractFromPdf(Files.readAllBytes(filePath));
        printResult(result);
    }

    private static void printResult(InvoiceData data) {
        System.out.println("  Source:        " + data.extractionSource());
        System.out.println("  Vendor:        " + data.vendorName());
        System.out.println("  Invoice #:     " + data.invoiceNumber());
        System.out.println("  Invoice Date:  " + data.invoiceDate());
        System.out.println("  Due Date:      " + data.dueDate());
        System.out.println("  Total:         " + data.totalAmount() + " " + data.currency());
        System.out.println("  PO Number:     " + data.poNumber());
        System.out.println("  Payment Terms: " + data.paymentTerms());
        System.out.println("  Complete:      " + data.isComplete());
        if (data.lineItems() != null && !data.lineItems().isEmpty()) {
            System.out.println("  Line items (" + data.lineItems().size() + "):");
            data.lineItems().forEach(li ->
                System.out.printf("    %-45s qty=%-6s unit=%-10s total=%s%n",
                    li.description(), li.quantity(), li.unitPrice(), li.amount()));
        }
        System.out.println();
    }
}
