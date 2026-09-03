package io.meridian.invoice.billing;

import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Marks an invoice as approved in Meridian's billing system.
 *
 * <p>Stubbed — in production this would update Meridian's ERP via HTTP.
 * Approvals are logged to an in-memory list for the duration of the run.
 *
 * <p>The LLM calls this tool when it determines the invoiced amount matches
 * the contracted amount within tolerance and no issues are found.
 *
 * <p><strong>Important:</strong> This tool approves for processing only —
 * it does not authorise payment. Final payment authorisation remains with
 * the AP team. This boundary is enforced by the system prompt.
 */
public class InvoiceApprover {

    private final List<ApprovalRecord> log = new ArrayList<>();

    @Tool("Mark an invoice as approved for processing when the invoiced amount " +
                "matches the contracted amount within tolerance. " +
                "This approves the invoice for AP review — it does NOT authorise payment. " +
                "Provide vendorId, invoiceNumber, approvedAmount, and poNumber.")
    public String approveInvoice(String vendorId,
                                  String invoiceNumber,
                                  String approvedAmount,
                                  String poNumber) {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        ApprovalRecord record = new ApprovalRecord(
            timestamp, vendorId, invoiceNumber, approvedAmount, poNumber);

        log.add(record);

        System.out.printf("[APPROVED] %s | vendor=%s | invoice=%s | " +
                          "amount=%s | po=%s%n",
            timestamp, vendorId, invoiceNumber, approvedAmount, poNumber);

        return String.format(
            "{\"status\": \"approved\", \"approvalId\": \"APR-%05d\", " +
            "\"vendorId\": \"%s\", \"invoiceNumber\": \"%s\", " +
            "\"approvedAmount\": \"%s\", " +
            "\"nextStep\": \"Forwarded to AP team for payment authorisation\"}",
            log.size(), vendorId, invoiceNumber, approvedAmount);
    }

    /** Returns all approvals recorded during this run. */
    public List<ApprovalRecord> getLog() {
        return List.copyOf(log);
    }

    public record ApprovalRecord(
        String timestamp,
        String vendorId,
        String invoiceNumber,
        String approvedAmount,
        String poNumber
    ) {}
}
