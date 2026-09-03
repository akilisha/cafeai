package io.meridian.invoice.billing;

import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records invoice discrepancies in Meridian's billing system.
 *
 * <p>Stubbed — in production this would write to Meridian's ERP via HTTP.
 * Discrepancies are logged to an in-memory list for the duration of the run,
 * then printed in the batch summary.
 *
 * <p>The LLM calls this tool when it determines an invoiced amount does not
 * match the contracted amount within tolerance.
 */
public class DiscrepancyRecorder {

    private final List<DiscrepancyRecord> log = new ArrayList<>();

    @Tool("Record a billing discrepancy when an invoiced amount does not match " +
                "the contracted amount within tolerance. " +
                "Provide vendorId, invoiceNumber, invoicedAmount, contractedAmount, " +
                "and a brief reason describing the discrepancy.")
    public String recordDiscrepancy(String vendorId,
                                    String invoiceNumber,
                                    String invoicedAmount,
                                    String contractedAmount,
                                    String reason) {
        String timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        DiscrepancyRecord record = new DiscrepancyRecord(
            timestamp, vendorId, invoiceNumber,
            invoicedAmount, contractedAmount, reason);

        log.add(record);

        System.out.printf("[DISCREPANCY] %s | vendor=%s | invoice=%s | " +
                          "invoiced=%s | contracted=%s | reason=%s%n",
            timestamp, vendorId, invoiceNumber,
            invoicedAmount, contractedAmount, reason);

        return String.format(
            "{\"status\": \"recorded\", \"discrepancyId\": \"DISC-%05d\", " +
            "\"vendorId\": \"%s\", \"invoiceNumber\": \"%s\", " +
            "\"action\": \"Flagged for AP supervisor review\"}",
            log.size(), vendorId, invoiceNumber);
    }

    /** Returns all discrepancies recorded during this run. */
    public List<DiscrepancyRecord> getLog() {
        return List.copyOf(log);
    }

    public record DiscrepancyRecord(
        String timestamp,
        String vendorId,
        String invoiceNumber,
        String invoicedAmount,
        String contractedAmount,
        String reason
    ) {}
}
