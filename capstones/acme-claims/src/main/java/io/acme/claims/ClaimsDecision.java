package io.acme.claims;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured claims intake decision returned by the Acme Insurance
 * claims assistant.
 *
 * <p>Four possible decisions:
 * <ul>
 *   <li>CLAIM_OPENED      -- new claim created, adjuster assigned</li>
 *   <li>CLAIM_EXISTS      -- existing claim found, status provided</li>
 *   <li>NOT_COVERED       -- incident type not covered by policy</li>
 *   <li>ESCALATED         -- complex situation requiring human review</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimsDecision(
    String       claimantId,
    String       policyNumber,
    String       decision,           // CLAIM_OPENED | CLAIM_EXISTS | NOT_COVERED | ESCALATED
    String       claimType,          // AUTO | PROPERTY | LIABILITY | MEDICAL
    String       incidentDate,
    String       claimNumber,        // existing or newly assigned
    String       claimStatus,        // OPEN | UNDER_REVIEW | APPROVED | DENIED | PENDING
    String       assignedAdjuster,
    List<String> coveredItems,
    List<String> notCoveredItems,
    List<String> requiredDocuments,
    List<String> nextSteps,
    String       estimatedResolution, // e.g. "5-7 business days"
    String       explanation
) {
    // The ClaimsAgent method returns this record directly — LangChain4j parses
    // the model output, so there is no hand-rolled JSON step.
}
