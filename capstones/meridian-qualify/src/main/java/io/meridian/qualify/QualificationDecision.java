package io.meridian.qualify;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured pre-qualification decision returned by the Meridian
 * qualification assistant.
 *
 * <p>Three possible decisions:
 * <ul>
 *   <li>LIKELY_QUALIFIED   -- profile meets Meridian's thresholds</li>
 *   <li>FURTHER_REVIEW     -- borderline; loan officer review needed</li>
 *   <li>LIKELY_INELIGIBLE  -- profile does not meet minimum thresholds</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualificationDecision(
    String       applicantId,
    String       decision,        // LIKELY_QUALIFIED | FURTHER_REVIEW | LIKELY_INELIGIBLE
    String       confidence,      // HIGH | MEDIUM | LOW
    double       loanAmount,
    double       dtiRatio,        // as decimal, e.g. 0.404
    double       estimatedMonthlyPayment,
    List<String> strengths,
    List<String> concerns,
    List<String> conditions,      // things applicant must provide
    List<String> declineReasons,  // populated when LIKELY_INELIGIBLE
    String       explanation,     // plain-language summary
    String       footprintStatus  // APPROVED | DECLINED
) {
    // The LLM no longer hand-produces this JSON — the QualificationAgent method
    // returns the record directly and LangChain4j parses the model output.
}
