package io.acme.claims;

import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.util.Map;

/**
 * Claims API tool stubs for Acme Insurance Group.
 *
 * <p>In production these methods call the Acme claims management system
 * via REST API. Here they return realistic stub data keyed on claim or
 * policy number so the test suite produces deterministic results.
 */
public class ClaimsApiTools {

    // Stub claim database
    private static final Map<String, ClaimRecord> CLAIMS = Map.of(
        "ACM-2024-001142", new ClaimRecord("ACM-2024-001142", "AUTO",
            "UNDER_REVIEW", "Sarah Chen", "2024-11-15",
            "Rear-end collision on I-94, $8,400 damage estimate"),
        "ACM-2024-002891", new ClaimRecord("ACM-2024-002891", "PROPERTY",
            "APPROVED", "David Kim", "2024-10-28",
            "Roof damage from hail storm — approved for $12,750"),
        "ACM-2024-003307", new ClaimRecord("ACM-2024-003307", "AUTO",
            "PENDING", "Mike Torres", "2024-12-01",
            "Awaiting police report and second repair estimate"),
        "ACM-2024-004455", new ClaimRecord("ACM-2024-004455", "LIABILITY",
            "OPEN", "Lisa Park", "2024-12-10",
            "Slip and fall on insured's property — investigation initiated")
    );

    // Stub policy database
    private static final Map<String, PolicyRecord> POLICIES = Map.of(
        "POL-AUTO-88821", new PolicyRecord("POL-AUTO-88821", "AUTO",
            "ACTIVE", "PREMIUM", "2025-06-30"),
        "POL-AUTO-44532", new PolicyRecord("POL-AUTO-44532", "AUTO",
            "ACTIVE", "STANDARD", "2025-03-15"),
        "POL-HOME-77190", new PolicyRecord("POL-HOME-77190", "PROPERTY",
            "ACTIVE", "PREMIUM", "2025-09-01"),
        "POL-HOME-33401", new PolicyRecord("POL-HOME-33401", "PROPERTY",
            "ACTIVE", "STANDARD", "2025-12-31"),
        "POL-AUTO-99001", new PolicyRecord("POL-AUTO-99001", "AUTO",
            "LAPSED", "STANDARD", "2024-09-30"),
        "POL-LIAB-55002", new PolicyRecord("POL-LIAB-55002", "LIABILITY",
            "ACTIVE", "STANDARD", "2025-08-15")
    );

    // Counter for new claim numbers
    private static int claimCounter = 5000;

    @Tool("""
        Look up an existing claim by claim number.
        Returns the current claim status, assigned adjuster, incident date,
        and any notes on the claim. Returns NOT_FOUND if the claim number
        does not exist in the system.
        Always call this tool when the claimant provides a claim number.
        """)
    public String lookupClaim(String claimNumber) {
        String normalized = claimNumber.trim().toUpperCase();
        ClaimRecord claim = CLAIMS.get(normalized);

        if (claim == null) {
            return "CLAIM_NOT_FOUND: No claim found with number " + normalized
                 + ". Verify the claim number and try again, or open a new claim.";
        }

        return String.format("""
            CLAIM FOUND:
              Claim Number:      %s
              Claim Type:        %s
              Status:            %s
              Assigned Adjuster: %s
              Filed Date:        %s
              Notes:             %s
            """,
            claim.claimNumber(), claim.claimType(), claim.status(),
            claim.adjuster(), claim.filedDate(), claim.notes());
    }

    @Tool("""
        Verify whether a policy number is active and what coverage tier it has.
        Returns coverage tier (STANDARD or PREMIUM), policy type, and expiry date.
        Returns POLICY_NOT_FOUND or POLICY_LAPSED if the policy is not active.
        Always call this tool before opening a new claim to confirm coverage.
        """)
    public String verifyPolicyCoverage(String policyNumber) {
        String normalized = policyNumber.trim().toUpperCase();
        PolicyRecord policy = POLICIES.get(normalized);

        if (policy == null) {
            return "POLICY_NOT_FOUND: No policy found with number " + normalized
                 + ". Cannot open a claim without a valid active policy.";
        }

        if ("LAPSED".equals(policy.status())) {
            return String.format("""
                POLICY_LAPSED: Policy %s expired on %s.
                Coverage tier was: %s (%s).
                No new claims can be opened on a lapsed policy.
                Refer claimant to underwriting for reinstatement options.
                """,
                normalized, policy.expiryDate(), policy.tier(), policy.type());
        }

        return String.format("""
            POLICY ACTIVE:
              Policy Number: %s
              Type:          %s
              Tier:          %s
              Status:        %s
              Expires:       %s
            Policy is valid and coverage is active. Claim may be opened.
            """,
            normalized, policy.type(), policy.tier(),
            policy.status(), policy.expiryDate());
    }

    @Tool("""
        Open a new claim in the Acme Insurance claims management system.
        Requires: policy number, claim type (AUTO/PROPERTY/LIABILITY/MEDICAL),
        incident date (YYYY-MM-DD), and a brief incident description.
        Returns the newly assigned claim number and adjuster information.
        Only call this tool AFTER verifyPolicyCoverage confirms the policy is active.
        """)
    public String openClaim(String policyNumber, String claimType,
                            String incidentDate, String incidentDescription) {

        // Validate policy first
        String policyCheck = verifyPolicyCoverage(policyNumber);
        if (policyCheck.startsWith("POLICY_NOT_FOUND") || policyCheck.startsWith("POLICY_LAPSED")) {
            return "CANNOT_OPEN_CLAIM: " + policyCheck;
        }

        // Assign claim number
        String claimNumber = String.format("ACM-%d-%06d",
            LocalDate.now().getYear(), ++claimCounter);

        // Assign adjuster based on claim type
        String adjuster = switch (claimType.toUpperCase()) {
            case "AUTO"      -> "Sarah Chen";
            case "PROPERTY"  -> "David Kim";
            case "LIABILITY" -> "Lisa Park";
            default          -> "Jennifer Walsh";
        };

        return String.format("""
            CLAIM_OPENED SUCCESSFULLY:
              Claim Number:      %s
              Policy:            %s
              Claim Type:        %s
              Incident Date:     %s
              Assigned Adjuster: %s
              Adjuster Email:    %s@acme-insurance.com
              Status:            OPEN
              Next Step:         Adjuster will contact claimant within 48 hours.
              Description:       %s
            """,
            claimNumber, policyNumber, claimType.toUpperCase(),
            incidentDate, adjuster,
            adjuster.toLowerCase().replace(" ", "."),
            incidentDescription);
    }

    // Internal record types
    private record ClaimRecord(String claimNumber, String claimType,
                                String status, String adjuster,
                                String filedDate, String notes) {}

    private record PolicyRecord(String policyNumber, String type,
                                 String status, String tier, String expiryDate) {}
}
