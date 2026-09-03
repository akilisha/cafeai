package io.meridian.invoice.billing;

import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * Looks up vendor contract details and expected billing amounts from
 * Meridian's internal billing system.
 *
 * <p>Stubbed with realistic data derived from actual vendor invoices.
 * In production this would call Meridian's ERP or AP system via HTTP.
 *
 * <p>Handed to the reconciliation agent via
 * {@code app.agent("reconciler", ReconciliationAgent.class).tool(new VendorContractLookup())}
 * — LangChain4j calls these methods autonomously during the agent's reasoning
 * loop when it needs billing information to complete reconciliation.
 */
public class VendorContractLookup {

    // ── Vendor master data ────────────────────────────────────────────────────
    // VendorID matches handwritten VENDOR# stamps on scanned invoices

    private static final Map<String, VendorRecord> VENDORS = Map.of(
        "VND-1001", new VendorRecord("VND-1001", "FedEx",
            "Courier/Shipping", "billing@fedex.com",
            "Monthly ground shipping account. Variable by volume. " +
            "Contracted base ~$1,800/month. Tolerance ±15%."),

        "VND-1002", new VendorRecord("VND-1002", "Heiden, Inc.",
            "Precision Manufacturing", "billing@heidenco.com",
            "Per-PO manufacturing contract. Amount fixed per purchase order. " +
            "No tolerance — exact PO amount expected. Terms: 2% 10 Net 30."),

        "VND-1003", new VendorRecord("VND-1003", "Honeywell International",
            "IP Licensing / Royalties", "hipifinance@honeywell.com",
            "Quarterly royalty contract #2023-11748. Fixed $15,774.57/quarter. " +
            "Period: quarterly (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec). " +
            "No tolerance — exact amount required. Terms: due 30 days after invoice."),

        "VND-1004", new VendorRecord("VND-1004", "KSO Metalfab",
            "Metal Fabrication", "ar@kso.com",
            "Per-PO fabrication contract. Amount fixed per purchase order. " +
            "No tolerance — exact PO amount expected. Terms: 1% 10 Net 30."),

        "VND-1005", new VendorRecord("VND-1005", "Liberty Fastener Company",
            "Hardware / Fasteners", "kevin@libertyfastener.com",
            "Per-order hardware supply. Contracted amount per approved PO. " +
            "Tolerance ±5% for quantity adjustments. Terms: 1% 10 Net 30."),

        "VND-1006", new VendorRecord("VND-1006", "Sigmatron International",
            "Electronic Assembly", "ar@sigmatronintl.com",
            "Per-PO electronic board assembly. Amount fixed per purchase order. " +
            "No tolerance — exact PO amount expected. Terms: 1% 10 Net 30."),

        "VND-1008", new VendorRecord("VND-1008", "Graybar Electric Co., Inc.",
            "Electrical Supply", "ap@graybar.com",
            "Per-PO electrical supply contract. Amount fixed per purchase order. " +
            "No tolerance -- exact PO amount expected. Terms: Net 30 Days."),

        "VND-1007", new VendorRecord("VND-1007", "Ultratech, Inc.",
            "Sheet Metal Fabrication", "billing@ultratech-inc.com",
            "Per-order fabrication. Contracted amount per approved PO. " +
            "Tolerance ±3% for material cost variance. Terms: 2% 10 Net 30.")
    );

    // ── Contract amounts keyed by vendorId:poNumber ───────────────────────────

    private static final Map<String, Double> CONTRACT_AMOUNTS = Map.of(
        "VND-1001:MONTHLY",   1800.00,   // FedEx — monthly base
        "VND-1002:PO105365",  4741.80,   // Heiden — exact PO amount
        "VND-1003:Q3-2024",  15774.57,   // Honeywell — quarterly royalty
        "VND-1004:PO105942",   269.20,   // KSO — exact PO amount
        "VND-1005:PO106068",  1400.00,   // Liberty — contracted (invoice shows 1535)
        "VND-1006:PO105241", 36084.50,   // Sigmatron — exact PO amount
        "VND-1007:PO105136",  7500.00    // Ultratech — contracted (invoice shows 7938)
    );

    // ── @Tool methods (called by the reconciliation agent's reasoning loop) ───

    @Tool("Look up vendor details and contract information by vendor name. " +
                "Returns vendor ID, contact email, vendor type, and contract summary.")
    public String lookupVendorByName(String vendorName) {
        // Normalise both sides: lowercase, strip punctuation, collapse whitespace
        String normalised = vendorName.toLowerCase()
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ").trim();

        return VENDORS.values().stream()
            .filter(v -> {
                String vn = v.name().toLowerCase()
                    .replaceAll("[^a-z0-9 ]", " ")
                    .replaceAll("\\s+", " ").trim();
                return vn.contains(normalised) || normalised.contains(vn)
                    || normalised.contains(vn.split(" ")[0]);  // first word match
            })
            .findFirst()
            .map(VendorRecord::toJson)
            .orElse("{\"error\": \"Vendor not found: " + vendorName + "\"}");
    }

    @Tool("Look up the contracted amount expected for a vendor and purchase order. " +
                "Provide vendorId (e.g. VND-1005) and poNumber. " +
                "For FedEx monthly invoices use poNumber='MONTHLY'. " +
                "For Honeywell quarterly royalties use poNumber='Q3-2024'. " +
                "Returns expected amount and tolerance percentage.")
    public String getContractedAmount(String vendorId, String poNumber) {
        String key = vendorId + ":" + poNumber;
        Double amount = CONTRACT_AMOUNTS.get(key);

        if (amount == null) {
            // Try without PO — look up vendor to give a helpful message
            VendorRecord vendor = VENDORS.get(vendorId);
            if (vendor == null) {
                return "{\"error\": \"Unknown vendor: " + vendorId + "\"}";
            }
            return "{\"error\": \"No contract found for " + vendorId +
                   " / PO " + poNumber + "\", " +
                   "\"vendor\": \"" + vendor.name() + "\", " +
                   "\"hint\": \"" + vendor.contractSummary() + "\"}";
        }

        double tolerancePct = toleranceFor(vendorId);
        double toleranceAmt = amount * tolerancePct / 100.0;

        return String.format(
            "{\"vendorId\": \"%s\", \"poNumber\": \"%s\", " +
            "\"contractedAmount\": %.2f, " +
            "\"tolerancePct\": %.1f, " +
            "\"toleranceMin\": %.2f, " +
            "\"toleranceMax\": %.2f, " +
            "\"currency\": \"USD\"}",
            vendorId, poNumber, amount,
            tolerancePct, amount - toleranceAmt, amount + toleranceAmt);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private double toleranceFor(String vendorId) {
        return switch (vendorId) {
            case "VND-1001" -> 15.0;  // FedEx — volume-based, high tolerance
            case "VND-1005" -> 5.0;   // Liberty — quantity adjustments
            case "VND-1007" -> 3.0;   // Ultratech — material cost variance
            default         -> 0.0;   // All others — exact match required
        };
    }

    private record VendorRecord(
        String vendorId,
        String name,
        String type,
        String email,
        String contractSummary
    ) {
        String toJson() {
            return String.format(
                "{\"vendorId\": \"%s\", \"name\": \"%s\", " +
                "\"type\": \"%s\", \"email\": \"%s\", " +
                "\"contractSummary\": \"%s\"}",
                vendorId, name, type, email,
                contractSummary.replace("\"", "'"));
        }
    }
}
