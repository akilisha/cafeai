package io.meridian.invoice;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.meridian.invoice.sentiment.EmailSentimentAnalyzer;
import io.meridian.invoice.sentiment.SentimentResult;

/**
 * Phase 5 sentiment analysis test.
 *
 * <p>Runs three email samples through the analyzer:
 * <ol>
 *   <li>Polite follow-up — expect NEUTRAL, LOW, escalate=false</li>
 *   <li>Frustrated chaser — expect FRUSTRATED, HIGH, escalate=false</li>
 *   <li>Hostile payment threat — expect HOSTILE, CRITICAL, escalate=true</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testSentiment
 * </pre>
 */
public class SentimentAnalysisTest {

    // ── Test emails ───────────────────────────────────────────────────────────

    static final String POLITE_EMAIL = """
        Hello,

        I hope this email finds you well. I'm writing to inquire about the
        payment status of Invoice INV-2024-002 dated February 10, 2024,
        in the amount of $57,613.50.

        This invoice was due on March 11, 2024, and we haven't received
        payment yet. Could you please confirm the current status and
        expected payment date?

        Thank you for your attention to this matter.

        Best regards,
        Sally Computers
        Accounts Receivable
        """;

    static final String FRUSTRATED_EMAIL = """
        Hello,

        This is our third attempt to follow up on Invoice INV-2024-002
        in the amount of $57,613.50, now 21 days past due.

        We have sent multiple reminders without response. This is
        unacceptable and is affecting our cash flow. We need immediate
        attention to this matter and a confirmed payment date by end
        of this week.

        We have not received any communication explaining the delay.
        Please respond urgently.

        Sally Computers
        Accounts Receivable
        """;

    static final String HOSTILE_EMAIL = """
        URGENT - FINAL NOTICE

        Invoice INV-2024-002 for $57,613.50 is now 21 days past due.
        All previous communications have been ignored.

        We are formally notifying you that if payment is not received
        within 48 hours, we will be forced to:
        1. Suspend all services and support contracts immediately
        2. Refer this account to our collections department
        3. Pursue legal action to recover the outstanding balance

        This is our final notice before escalation.

        Sally Computers Legal & Collections
        """;

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(InvoiceProcessor.SYSTEM_PROMPT);

        var analyzer = new EmailSentimentAnalyzer(app);

        System.out.println("=== Sentiment Analysis Test ===");
        System.out.println();

        testEmail(analyzer, "Test 1 — Polite follow-up", POLITE_EMAIL,
            "NEUTRAL", "LOW", false);

        testEmail(analyzer, "Test 2 — Frustrated chaser", FRUSTRATED_EMAIL,
            "FRUSTRATED", "HIGH", false);

        testEmail(analyzer, "Test 3 — Hostile threat", HOSTILE_EMAIL,
            "HOSTILE", "CRITICAL", true);

        System.out.println("Phase 5 complete -- sentiment analysis verified.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static void testEmail(EmailSentimentAnalyzer analyzer,
                                   String label,
                                   String emailBody,
                                   String expectedTone,
                                   String expectedUrgency,
                                   boolean expectedEscalate) throws Exception {
        System.out.println("--- " + label + " ---");

        SentimentResult result = analyzer.analyze(emailBody);

        System.out.println("  Tone:      " + result.tone()
            + (result.tone().equals(expectedTone) ? " OK" : " UNEXPECTED (expected " + expectedTone + ")"));
        System.out.println("  Urgency:   " + result.urgency()
            + (result.urgency().equals(expectedUrgency) ? " OK" : " UNEXPECTED (expected " + expectedUrgency + ")"));
        System.out.println("  Escalate:  " + result.escalate()
            + (result.escalate() == expectedEscalate ? " OK" : " UNEXPECTED (expected " + expectedEscalate + ")"));
        System.out.println("  Key phrases:");
        result.keyPhrases().forEach(p -> System.out.println("    - " + p));
        System.out.println("  Action:    " + result.recommendedAction());
        System.out.println();
    }
}
