package io.meridian.invoice.classification;

/**
 * Structured result from {@link AttachmentTypeClassifier}.
 *
 * <p>The {@code isInvoice} flag is the routing gate:
 * when true, the attachment proceeds to {@link io.meridian.invoice.extraction.InvoiceDataExtractor}.
 * When false, it is logged and skipped.
 */
public record AttachmentClassification(

    /** Whether this attachment is an invoice or invoice-related document.
     * isInvoice=true for: invoices, billing statements, royalty statements,
     * and purchase orders (which accompany vendor billing).
     * isInvoice=false for: packing lists, shipping receipts, contracts,
     * photos, or any non-billing document.*/
    boolean isInvoice,

    /**
     * Model confidence in the classification.
     * One of: HIGH, MEDIUM, LOW
     */
    String confidence,

    /**
     * The type of document detected.
     * Examples: INVOICE, STATEMENT, PURCHASE_ORDER, CONTRACT,
     *           SHIPPING_RECEIPT, PACKING_LIST, OTHER
     */
    String docType,

    /**
     * Brief reason for the classification decision.
     * Useful for audit trails and debugging.
     */
    String reason

) {}
