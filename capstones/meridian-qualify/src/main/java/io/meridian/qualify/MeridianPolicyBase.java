package io.meridian.qualify;

import io.cafeai.core.CafeAI;
import io.cafeai.rag.Source;

public class MeridianPolicyBase {

    public static void seed(CafeAI app) {

        app.ingest(Source.text("""
            # Meridian Home Loans — Lending Footprint

            Meridian Home Loans originates mortgages in the following
            twelve states only: Wisconsin (WI), Illinois (IL), Minnesota (MN),
            Michigan (MI), Indiana (IN), Ohio (OH), Iowa (IA), Missouri (MO),
            Kansas (KS), Nebraska (NE), North Dakota (ND), South Dakota (SD).

            Applications for properties in any other state must be declined
            at intake with the message: "Meridian Home Loans does not currently
            lend in [STATE]. We operate across twelve Midwestern states."

            There are no county-level exclusions within the approved states
            as of the current policy version (March 2026).
            """, "meridian/footprint"));

        app.ingest(Source.text("""
            # Meridian Home Loans — Conventional Loan Requirements

            Minimum credit score: 620
            Preferred credit score for best rates: 740+

            Maximum debt-to-income ratio (DTI): 43%
            DTI is calculated as: (total monthly debts + proposed mortgage payment)
            divided by gross monthly income.

            Maximum loan-to-value (LTV): 97% with PMI, 80% without PMI
            Minimum down payment: 3% of purchase price

            Loan limits (2026): $766,550 for single-family properties
            in standard cost areas. High-cost area limits vary by county.

            Income documentation required:
            - Salaried employees: 2 years W-2 + 30 days recent paystubs
            - Self-employed: 2 years personal and business tax returns
            - Rental income: Schedule E + 2-year history

            Cash reserves required: 2 months PITI after closing for loans
            under $500,000. 6 months PITI for loans $500,000 and above.
            """, "meridian/conventional"));

        app.ingest(Source.text("""
            # Meridian Home Loans — FHA Loan Requirements

            Minimum credit score: 580 (with 3.5% down payment)
            Minimum credit score: 500 (with 10% down payment)
            Meridian internal minimum: 580 — we do not originate FHA loans
            below 580 regardless of down payment.

            Maximum DTI: 50% with compensating factors (strong reserves,
            residual income, low LTV). Standard maximum: 43%.

            Minimum down payment: 3.5% for scores 580+, 10% for scores 500-579.
            Meridian does not originate FHA loans for scores below 580.

            Mortgage Insurance Premium (MIP):
            - Upfront MIP: 1.75% of base loan amount
            - Annual MIP: 0.55% for most 30-year loans (paid monthly)
            MIP is required for the life of the loan if down payment < 10%.

            FHA loan limits (2026): $498,257 for standard areas.
            Properties must meet FHA minimum property standards.
            Appraiser must be FHA-approved.
            """, "meridian/fha"));

        app.ingest(Source.text("""
            # Meridian Home Loans — VA Loan Requirements

            Eligibility: Active duty service members, veterans with honorable
            discharge, surviving spouses. Certificate of Eligibility (COE)
            required at application.

            No minimum credit score set by VA. Meridian internal minimum: 580.
            No maximum DTI set by VA. Meridian reviews residual income per
            VA guidelines for the applicable region and family size.

            No down payment required (0% down).
            No PMI — VA funding fee applies instead.
            VA funding fee: 2.15% for first use (no down payment),
            1.25% for subsequent use, 0.5% with 10%+ down payment.
            Funding fee may be financed into the loan.

            VA loan limits: No limit for borrowers with full entitlement.
            Meridian lends up to $1,500,000 on VA loans.
            """, "meridian/va"));

        app.ingest(Source.text("""
            # Meridian Home Loans — Common Pre-Qualification Concerns

            DTI TOO HIGH:
            If DTI exceeds 43% (conventional) or 50% (FHA with compensating
            factors), the application is likely to be declined. Applicant
            options: pay down existing debts, increase income, reduce requested
            loan amount, or apply with a co-borrower.

            CREDIT SCORE TOO LOW:
            Conventional below 620: consider FHA if score is 580+.
            FHA below 580: Meridian cannot originate. Recommend credit
            counseling, secured card, and 6-12 months of on-time payments.

            OUT OF FOOTPRINT:
            Meridian only lends in its twelve-state Midwest footprint.
            Decline at intake — do not process further.

            LOAN AMOUNT EXCEEDS LIMITS:
            Conventional above $766,550: refer to jumbo lending team
            (separate application process, not handled by this system).
            FHA above $498,257: cannot originate. Consider conventional.

            INSUFFICIENT INCOME DOCUMENTATION:
            Self-employed applicants must provide full two-year history.
            Recent job changes (< 2 years) require explanation letter.
            Gap in employment > 6 months requires explanation letter.
            """, "meridian/concerns"));

        app.ingest(Source.text("""
            # Meridian Home Loans — DTI Calculation Reference

            Gross monthly income = annual income / 12

            Front-end ratio (housing ratio):
            Proposed mortgage payment (PITI) / gross monthly income
            Conventional guideline: 28% or below preferred.

            Back-end ratio (total DTI):
            (All monthly debts + proposed mortgage payment) / gross monthly income
            This is the primary qualifying ratio Meridian uses.

            Monthly debts to include:
            - Minimum credit card payments
            - Auto loan payments
            - Student loan payments (1% of balance if in deferment)
            - Personal loan payments
            - Child support / alimony obligations
            - Any other installment debt

            Monthly debts to exclude:
            - Utilities
            - Insurance (unless escrowed)
            - Cell phone
            - Subscriptions

            Proposed mortgage payment estimate:
            Lenders use PITI — Principal, Interest, Taxes, Insurance.
            Rule of thumb for estimation: multiply loan amount by 0.007
            to get approximate monthly PITI for a 30-year loan at ~7% rate.
            """, "meridian/dti-reference"));

        System.out.println("Meridian policy base loaded (6 documents)");
    }
}
