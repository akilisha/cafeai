package io.meridian.invoice.classification;

import io.cafeai.core.CafeAI;

/**
 * Classifies whether an email attachment is an invoice.
 *
 * <p>Uses CafeAI's {@code app.vision()} for multimodal LLM calls --
 * guardrails, observability, and token budget all apply automatically.
 * Handles both image attachments (JPEG, PNG -- scanned paper invoices)
 * and PDF attachments (digital invoices).
 *
 * <p>One responsibility: classify one attachment. Nothing else.
 */
public class AttachmentTypeClassifier {

    private final CafeAI app;

    public AttachmentTypeClassifier(CafeAI app) {
        this.app = app;
    }

    public AttachmentClassification classifyImage(byte[] imageBytes,
                                                   String mimeType) throws Exception {
        return app.vision(buildPrompt(), imageBytes, mimeType)
            .returning(AttachmentClassification.class)
            .call(AttachmentClassification.class);
    }

    public AttachmentClassification classifyPdf(byte[] pdfBytes) throws Exception {
        return app.vision(buildPrompt(), pdfBytes, "application/pdf")
            .returning(AttachmentClassification.class)
            .call(AttachmentClassification.class);
    }

    private String buildPrompt() {
        return """
            You are reviewing an email attachment received by Meridian Home Loans
            accounts payable department.

            IMPORTANT: This document may be multi-page. Scan ALL pages before
            classifying. A document that contains BOTH a packing list and an invoice
            should be classified as isInvoice=true, because it contains billing
            information. Look for: invoice numbers, billing totals, "amount due",
            "remit to", tax lines, or payment terms on ANY page.

            Examine the complete document and classify it. Respond with ONLY a valid
            JSON object. No explanation, no preamble, no markdown formatting.

            JSON schema:
            {
              "isInvoice": <true|false>,
              "confidence": "<HIGH|MEDIUM|LOW>",
              "docType": "<INVOICE|STATEMENT|PURCHASE_ORDER|CONTRACT|SHIPPING_RECEIPT|PACKING_LIST|OTHER>",
              "reason": "<one sentence explaining the classification>"
            }

            isInvoice=true for: invoices, billing statements, royalty statements,
            and any document that contains an invoice page even if it also contains
            a packing list or shipping receipt on another page.
            isInvoice=false ONLY for documents that contain NO billing information
            at all: pure packing lists, pure shipping receipts, contracts,
            photos, or any document with zero financial totals or payment terms.
            """;
    }
}
