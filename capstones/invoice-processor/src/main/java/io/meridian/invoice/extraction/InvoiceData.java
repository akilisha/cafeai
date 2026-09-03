package io.meridian.invoice.extraction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Structured invoice data extracted from an email attachment or body.
 *
 * <p>Populated by {@link InvoiceDataExtractor} via multimodal LLM extraction.
 * All fields are nullable — the LLM fills what it can find and leaves
 * the rest null. Callers must null-check before use.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} tolerates any
 * extra fields the LLM includes in its JSON response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceData(

    /** Vendor company name as it appears on the invoice. */
    String vendorName,

    /** Invoice number / reference number. */
    String invoiceNumber,

    /** Invoice date in ISO format where possible, e.g. "2025-09-22" */
    String invoiceDate,

    /** Payment due date in ISO format where possible. */
    String dueDate,

    /** Total amount due as a numeric string, e.g. "1535.00" */
    String totalAmount,

    /** Currency code, e.g. "USD" */
    String currency,

    /** Purchase order number referenced on the invoice. */
    String poNumber,

    /** Individual line items on the invoice. */
    List<LineItem> lineItems,

    /**
     * Payment terms as stated on the invoice, e.g. "Net 30", "1% 10 Net 30".
     */
    String paymentTerms,

    /**
     * The source path used for extraction.
     * One of: IMAGE, PDF, EMAIL_BODY
     * Set by the extractor, not the LLM.
     */
    String extractionSource

) {

    /** Returns true if the minimum fields needed for reconciliation are present. */
    public boolean isComplete() {
        return vendorName  != null && !vendorName.isBlank()
            && invoiceNumber != null && !invoiceNumber.isBlank()
            && totalAmount   != null && !totalAmount.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(
        String description,
        String quantity,
        String unitPrice,
        String amount
    ) {}
}
