package io.acme.claims;

import io.cafeai.core.CafeAI;
import io.cafeai.rag.Source;

/**
 * Seeds the Acme Insurance Group knowledge base with six policy documents.
 *
 * <p>In production these would be ingested from a document management
 * system. Here they are inline stubs that give the LLM accurate
 * Acme-specific policy context.
 */
public class AcmeKnowledgeBase {

    public static void seed(CafeAI app) {

        app.ingest(Source.text("""
            # Acme Insurance Group — Auto Coverage Policy

            COVERED INCIDENTS:
            - Collision damage (deductible: $500 standard, $250 premium tier)
            - Comprehensive (theft, weather, vandalism — deductible: $250)
            - Liability: bodily injury and property damage to third parties
            - Uninsured/underinsured motorist coverage
            - Roadside assistance (premium tier only)
            - Rental reimbursement: up to $40/day, 30-day maximum (premium tier)

            NOT COVERED:
            - Mechanical breakdown or wear and tear
            - Damage from driving under the influence
            - Racing or speed competitions
            - Intentional damage
            - Commercial use of personal vehicle without commercial rider

            REQUIRED DOCUMENTATION FOR AUTO CLAIM:
            - Police report (if applicable — required for theft and accidents with injury)
            - Photos of damage
            - Driver's license and registration
            - Other party's insurance information (if collision)
            - Medical records (if injury claim)

            TYPICAL RESOLUTION TIME: 5-10 business days for straightforward collision.
            Total loss assessment: 10-15 business days.
            """, "acme/auto-coverage"));

        app.ingest(Source.text("""
            # Acme Insurance Group — Property (Homeowners) Coverage Policy

            COVERED INCIDENTS:
            - Fire and smoke damage
            - Wind and hail damage
            - Water damage from burst pipes (not flooding)
            - Theft and vandalism
            - Falling objects (trees, aircraft)
            - Electrical damage from power surges
            - Liability for injuries on property

            NOT COVERED:
            - Flood damage (requires separate NFIP policy)
            - Earthquake damage (requires separate rider)
            - Mold (unless resulting from a covered water event)
            - Normal wear and tear or maintenance failures
            - Pest damage (termites, rodents)
            - Sewer backup (requires separate rider)
            - Home business equipment above $2,500

            COVERAGE LIMITS:
            - Dwelling: policy face value
            - Personal property: 50% of dwelling value
            - Loss of use: 20% of dwelling value
            - Liability: $100,000 standard, $300,000 premium

            REQUIRED DOCUMENTATION:
            - Completed claim form with incident description
            - Photos/video of damage
            - Estimates from licensed contractors (2+ estimates required)
            - Receipts for damaged personal property
            - Police report (for theft and vandalism)

            TYPICAL RESOLUTION: 7-14 business days. Catastrophic events: 30+ days.
            """, "acme/property-coverage"));

        app.ingest(Source.text("""
            # Acme Insurance Group — Liability Coverage Policy

            COVERED:
            - Third-party bodily injury claims
            - Third-party property damage claims
            - Legal defense costs
            - Medical payments to injured parties (up to $5,000 regardless of fault)
            - Personal liability (standard homeowners rider)

            NOT COVERED:
            - Intentional acts
            - Business liability without commercial policy
            - Professional liability (requires separate E&O policy)
            - Claims between household members
            - Damage to insured's own property

            LIMITS:
            - Standard: $100,000 per occurrence
            - Premium: $300,000 per occurrence
            - Umbrella riders available: $1M, $2M, $5M

            REQUIRED DOCUMENTATION:
            - Incident report
            - Third-party contact information
            - Any demand letters or legal notices received
            - Witness statements if available

            TYPICAL RESOLUTION: Varies widely — 30-180 days for contested claims.
            """, "acme/liability-coverage"));

        app.ingest(Source.text("""
            # Acme Insurance Group — Claims Process and SLAs

            INTAKE PROCESS:
            1. Claimant submits claim via portal, phone, or email
            2. System assigns claim number: ACM-YYYY-NNNNNN format
            3. Initial coverage verification within 24 hours
            4. Adjuster assigned within 48 hours
            5. Inspection scheduled within 5 business days
            6. Decision issued within SLA based on claim type

            CLAIM STATUS DEFINITIONS:
            - OPEN: Newly filed, initial review not yet complete
            - UNDER_REVIEW: Assigned to adjuster, investigation in progress
            - APPROVED: Coverage confirmed, payment processing
            - DENIED: Claim not covered under policy terms
            - PENDING: Awaiting documentation from claimant
            - CLOSED: Fully resolved and paid or denied

            ESCALATION CRITERIA (routes to senior adjuster):
            - Claim amount estimated above $50,000
            - Disputed liability
            - Legal representation involved
            - Multiple parties or vehicles
            - Potential fraud indicators

            ADJUSTER ASSIGNMENTS:
            - Auto claims: Sarah Chen, Mike Torres, Jennifer Walsh
            - Property claims: David Kim, Rachel Moore, Tom Sullivan
            - Liability claims: Lisa Park, James Carter

            CONTACT: claims@acme-insurance.com | 1-800-ACME-CLM
            """, "acme/claims-process"));

        app.ingest(Source.text("""
            # Acme Insurance Group — Fraud Indicators and Escalation

            AUTOMATIC ESCALATION TRIGGERS:
            - Incident reported more than 30 days after occurrence
            - Claimant has filed more than 2 claims in 12 months
            - Claim amount significantly exceeds market value of damaged item
            - Inconsistencies between police report and claimant's account
            - Damage inconsistent with described incident
            - Prior history of claim denial for fraud

            FRAUD INDICATORS (flag for review, do not deny):
            - Cash settlement demands before inspection
            - Reluctance to provide documentation
            - Witnesses who are family members or friends only
            - Staged appearance of incident

            RESPONSE FOR SUSPECTED FRAUD:
            Do not accuse. Escalate to Special Investigations Unit (SIU).
            Inform claimant only that additional review is required.
            SIU contact: siu@acme-insurance.com

            PRIVACY REQUIREMENTS:
            - Never share one claimant's information with another party
            - Third-party information only disclosed with consent or legal requirement
            - Medical information governed by HIPAA
            - All claim communications logged for compliance
            """, "acme/fraud-escalation"));

        app.ingest(Source.text("""
            # Acme Insurance Group — Common Claim Scenarios and Responses

            SCENARIO: Rear-end collision, claimant at fault
            Coverage: Collision (after deductible). Liability covers other party.
            Documents needed: Police report, photos, other driver's info.
            Typical outcome: CLAIM_OPENED, adjuster assigned within 48 hours.

            SCENARIO: Tree fell on roof during storm
            Coverage: Comprehensive property — wind/falling object. Covered.
            Documents needed: Photos, contractor estimates (2 required).
            Note: Damage to the tree itself not covered.

            SCENARIO: Basement flooded after heavy rain
            Coverage: NOT COVERED — flood damage requires NFIP policy.
            Exception: If flooding caused by burst pipe, covered under property.
            Response: Explain exclusion, refer to NFIP for flood coverage.

            SCENARIO: Vehicle stolen from driveway
            Coverage: Comprehensive auto — theft covered.
            Documents needed: Police report (mandatory), photos of empty space,
            key inventory (all sets accounted for).

            SCENARIO: Slip and fall on claimant's property (visitor injured)
            Coverage: Liability / medical payments.
            Documents needed: Incident report, medical records of injured party,
            any demand letters received.

            SCENARIO: Claimant injured in accident (not their fault)
            Coverage: Uninsured motorist if other driver uninsured.
            Documents needed: Police report, other driver's insurance (or proof
            of no insurance), medical records.
            """, "acme/claim-scenarios"));

        System.out.println("Acme Insurance knowledge base loaded (6 documents)");
    }
}
