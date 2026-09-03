package io.meridian.invoice.extraction;

import io.cafeai.core.CafeAI;

/**
 * Extracts structured invoice data from three input types:
 * <ul>
 *   <li>Image -- scanned paper invoice or photo (JPEG, PNG)</li>
 *   <li>PDF -- digital or scanned PDF invoice</li>
 *   <li>Email body -- vendor pasted invoice details into the email</li>
 * </ul>
 *
 * <p>Uses CafeAI's {@code app.vision()} for binary content and
 * {@code app.prompt()} for email body text. Structured output via
 * {@code .returning(InvoiceData.class)} -- no manual JSON parsing.
 *
 * <p>One responsibility: extract invoice data from one input. Nothing else.
 */
public class InvoiceDataExtractor {

    private final CafeAI app;

    public InvoiceDataExtractor(CafeAI app) {
        this.app = app;
    }

    public InvoiceData extractFromImage(byte[] imageBytes,
                                         String mimeType) throws Exception {
        InvoiceData parsed = app.vision(extractionPrompt(), imageBytes, mimeType)
            .returning(InvoiceData.class)
            .call(InvoiceData.class);
        return withSource(parsed, "IMAGE");
    }

    public InvoiceData extractFromPdf(byte[] pdfBytes) throws Exception {
        InvoiceData parsed = app.vision(extractionPrompt(), pdfBytes, "application/pdf")
            .returning(InvoiceData.class)
            .call(InvoiceData.class);
        return withSource(parsed, "PDF");
    }

    public InvoiceData extractFromEmailBody(String emailBody) throws Exception {
        String prompt = extractionPrompt() + "\n\nEmail body:\n---\n" + emailBody + "\n---";
        InvoiceData parsed = app.prompt(prompt)
            .returning(InvoiceData.class)
            .call(InvoiceData.class);
        return withSource(parsed, "EMAIL_BODY");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String extractionPrompt() {
        return """
            You are extracting invoice data from a vendor document received by
            Meridian Home Loans accounts payable.

            Extract all available invoice fields and respond with ONLY a valid
            JSON object. No explanation, no preamble, no markdown formatting.

            JSON schema:
            {
              "vendorName":    "<vendor company name>",
              "invoiceNumber": "<invoice or reference number>",
              "invoiceDate":   "<date in YYYY-MM-DD format if possible>",
              "dueDate":       "<due date in YYYY-MM-DD format if possible>",
              "totalAmount":   "<total amount as numeric string, no currency symbol>",
              "currency":      "<currency code, default USD>",
              "poNumber":      "<purchase order number or null>",
              "paymentTerms":  "<payment terms as stated>",
              "lineItems": [
                {
                  "description": "<item description>",
                  "quantity":    "<quantity as string>",
                  "unitPrice":   "<unit price as numeric string>",
                  "amount":      "<line total as numeric string>"
                }
              ]
            }

            Rules:
            - Extract exactly what is on the document -- do not infer or estimate
            - If a field is not present, use null
            - totalAmount must be the final total including tax if shown
            - For scanned documents with handwriting, read both printed and
              handwritten text
            - For multi-page documents, use the first invoice page for totals
            """;
    }

    private InvoiceData withSource(InvoiceData parsed, String source) {
        return new InvoiceData(
            parsed.vendorName(),
            parsed.invoiceNumber(),
            parsed.invoiceDate(),
            parsed.dueDate(),
            parsed.totalAmount(),
            parsed.currency(),
            parsed.poNumber(),
            parsed.lineItems(),
            parsed.paymentTerms(),
            source
        );
    }
}
