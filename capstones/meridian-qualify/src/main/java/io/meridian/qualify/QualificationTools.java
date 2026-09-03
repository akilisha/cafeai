package io.meridian.qualify;

import dev.langchain4j.agent.tool.Tool;

import java.util.Set;

public class QualificationTools {

    // Meridian's twelve-state lending footprint
    private static final Set<String> FOOTPRINT = Set.of(
        "WI", "IL", "MN", "MI", "IN", "OH",
        "IA", "MO", "KS", "NE", "ND", "SD"
    );

    @Tool("""
    Verify whether a property state is within Meridian Home Loans'
    lending footprint. Returns APPROVED with the state name if eligible,
    or DECLINED with an explanation if the state is outside the footprint.
    Always call this tool first before any other assessment.
    The result of this tool is authoritative and must not be overridden.
    """)
    public String verifyLendingFootprint(String stateCode) {
        String code = stateCode.trim().toUpperCase();
        if (FOOTPRINT.contains(code)) {
            return "FOOTPRINT CHECK RESULT: APPROVED. "
                    + code + " is confirmed within Meridian's lending footprint. "
                    + "Proceed with the full qualification assessment.";
        }
        return "FOOTPRINT CHECK RESULT: DECLINED. "
                + "Meridian Home Loans does not lend in " + code + ". "
                + "The twelve eligible states are: WI, IL, MN, MI, IN, OH, IA, MO, KS, NE, ND, SD. "
                + "Set footprintStatus to DECLINED in your JSON response. "
                + "Set decision to LIKELY_INELIGIBLE. "
                + "Stop all assessment immediately — do not calculate DTI or payments.";
    }

    @Tool("""
        Calculate the debt-to-income ratio (DTI) for a loan applicant and
        assess it against Meridian's thresholds for the specified loan type.
        Requires annual income, total monthly debts, proposed loan amount,
        and loan type (CONVENTIONAL, FHA, or VA).
        Returns the calculated DTI, whether it meets Meridian's threshold,
        and specific guidance if the DTI is too high.
        """)
    public String calculateDTI(
            double annualIncome,
            double monthlyDebts,
            double loanAmount,
            String loanType) {

        double grossMonthly = annualIncome / 12.0;

        // Estimate proposed monthly PITI using Meridian's rule of thumb
        // (loan amount * 0.007 approximates 30-year payment at ~7% including taxes/insurance)
        double proposedPayment = loanAmount * 0.007;

        double totalMonthly = monthlyDebts + proposedPayment;
        double dti = totalMonthly / grossMonthly;
        double dtiPct = Math.round(dti * 1000.0) / 10.0; // one decimal place

        // Meridian thresholds by loan type
        double maxDTI = switch (loanType.toUpperCase()) {
            case "FHA" -> 0.50;       // 50% with compensating factors, standard 43%
            case "VA"  -> 0.60;       // VA uses residual income, not strict DTI cap
            default    -> 0.43;       // CONVENTIONAL: hard 43% cap
        };

        double standardMax = loanType.equalsIgnoreCase("FHA") ? 0.43 : maxDTI;

        StringBuilder result = new StringBuilder();
        result.append("DTI CALCULATION RESULTS:\n");
        result.append(String.format("  Gross monthly income:     $%.2f%n", grossMonthly));
        result.append(String.format("  Existing monthly debts:   $%.2f%n", monthlyDebts));
        result.append(String.format("  Estimated PITI payment:   $%.2f%n", proposedPayment));
        result.append(String.format("  Total monthly obligations:$%.2f%n", totalMonthly));
        result.append(String.format("  Calculated DTI:           %.1f%%%n", dtiPct));
        result.append(String.format("  Meridian limit (%s): %.0f%%%n",
            loanType.toUpperCase(), maxDTI * 100));

        if (dti <= standardMax) {
            result.append("  ASSESSMENT: DTI is within Meridian's standard limit. PASS.");
        } else if (loanType.equalsIgnoreCase("FHA") && dti <= maxDTI) {
            result.append("  ASSESSMENT: DTI exceeds standard 43% but is below FHA maximum "
                + "of 50%. May qualify with compensating factors such as strong reserves, "
                + "low LTV, or residual income. Requires loan officer review.");
        } else {
            result.append("  ASSESSMENT: DTI EXCEEDS Meridian's maximum. FAIL.\n");
            result.append("  To qualify, applicant must either:\n");
            result.append(String.format(
                "  - Reduce monthly debts to below $%.2f, OR%n",
                (grossMonthly * maxDTI) - proposedPayment));
            result.append(String.format(
                "  - Increase annual income above $%.2f, OR%n",
                (totalMonthly / maxDTI) * 12));
            result.append("  - Reduce the requested loan amount.");
        }

        return result.toString();
    }

    @Tool("""
        Estimate the monthly mortgage payment (PITI — Principal, Interest,
        Taxes, Insurance) for a given loan amount and term.
        Uses Meridian's standard estimation formula.
        Returns the estimated monthly payment and the annualized cost.
        """)
    public String estimateMonthlyPayment(double loanAmount, int termYears) {
        // Meridian's rule of thumb: loan amount * 0.007 for 30-year at ~7%
        // Adjusted for term: shorter terms have higher payments
        double factor = termYears == 15 ? 0.009 : 0.007;
        double monthly = loanAmount * factor;
        double annual  = monthly * 12;

        return String.format(
            "PAYMENT ESTIMATE:%n"
            + "  Loan amount:        $%.2f%n"
            + "  Term:               %d years%n"
            + "  Estimated monthly:  $%.2f (PITI approximation)%n"
            + "  Estimated annual:   $%.2f%n"
            + "  Note: Actual payment depends on final interest rate, "
            + "exact property taxes, and insurance costs. "
            + "A Meridian loan officer will provide precise figures.",
            loanAmount, termYears, monthly, annual
        );
    }
}
