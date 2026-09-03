package io.meridian.invoice;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.RetryPolicy;
import io.cafeai.core.ai.TokenBudget;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.meridian.invoice.billing.DiscrepancyRecorder;
import io.meridian.invoice.billing.InvoiceApprover;
import io.meridian.invoice.billing.VendorContractLookup;
import io.meridian.invoice.classification.AttachmentTypeClassifier;
import io.meridian.invoice.escalation.EscalationNotifier;
import io.meridian.invoice.extraction.InvoiceData;
import io.meridian.invoice.extraction.InvoiceDataExtractor;
import io.meridian.invoice.gmail.GmailAttachmentDownloader;
import io.meridian.invoice.gmail.GmailEmailBodyReader;
import io.meridian.invoice.gmail.GmailEmailBodyReader.AttachmentInfo;
import io.meridian.invoice.gmail.GmailEmailBodyReader.EmailContent;
import io.meridian.invoice.gmail.GmailEmailSender;
import io.meridian.invoice.gmail.GmailUnreadEmailFetcher;
import io.meridian.invoice.reconciliation.InvoiceAmountReconciler;
import io.meridian.invoice.reconciliation.ReconciliationAgent;
import io.meridian.invoice.reconciliation.ReconciliationResult;
import io.meridian.invoice.response.ResponseComposer;
import io.meridian.invoice.sentiment.EmailSentimentAnalyzer;
import io.meridian.invoice.sentiment.SentimentResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Meridian Home Loans Vendor Invoice Processor.
 *
 * <p>Batch job entry point. Fetches unread vendor emails from Gmail and
 * runs each through the full pipeline:
 *
 * <pre>
 *   Gmail (unread emails)
 *       |
 *       v
 *   Pre-filter             ← skip obvious non-vendor emails (no token cost)
 *       |
 *       v
 *   Sentiment Analysis     ← tone + urgency → escalation decision
 *       |
 *       └ escalate=true → EscalationNotifier -> supervisor + vendor ack
 *       |
 *       v
 *   Attachment Classification  ← is this an invoice?
 *       |
 *       v
 *   Invoice Extraction         ← structured fields from PDF/image/body
 *       |
 *       v
 *   Reconciliation             ← contracted vs invoiced via a tool-calling agent
 *       |
 *       v
 *   Response Composition       ← draft vendor reply
 *       |
 *       v
 *   Gmail (send reply)         ← skipped in dry-run mode
 * </pre>
 *
 * <p>Run modes:
 * <pre>
 *   ./gradlew run          → full processing run
 *   ./gradlew run -Pdry    → dry run, no emails sent
 * </pre>
 */
public class InvoiceProcessor {

    static final String SYSTEM_PROMPT = """
        You are an accounts payable assistant for Meridian Home Loans.
        You process vendor invoice emails on behalf of Meridian's AP team.

        Your responsibilities:
        - Analyse the tone and urgency of vendor emails
        - Extract invoice details from email bodies and attachments
        - Compare invoiced amounts against contracted amounts
        - Draft professional responses to vendors
        - Flag discrepancies and escalate urgent situations

        Your boundaries:
        - You do not approve or authorise payments -- you recommend actions
        - You do not share information about other vendors or invoices
        - You do not make commitments on payment dates without system confirmation
        - You always maintain a professional, courteous tone regardless of vendor tone

        Meridian Home Loans vendors include appraisal companies, title agencies,
        property inspection firms, shipping providers, and technology suppliers.
        """;

    private static final String AP_SENDER_NAME     = "AP Processing Team";
    private static final String SUPERVISOR_EMAIL   = "talktoaiguru@gmail.com";
    private static final int    MAX_EMAILS         = 5;

    // ── Run summary record ────────────────────────────────────────────────────

    record ProcessingResult(
        String emailId,
        String subject,
        String from,
        String outcome,    // APPROVED | DISCREPANCY | ESCALATED | SKIPPED | ERROR
        String detail
    ) {}

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        boolean dryRun = "true".equals(System.getProperty("invoice.dryRun"));

        System.out.println("=================================================");
        System.out.println("  Meridian Home Loans");
        System.out.println("  Vendor Invoice Processor");
        System.out.println("=================================================");
        System.out.println("  Mode:    " + (dryRun ? "DRY RUN (no emails sent)" : "LIVE"));
        System.out.println("  Started: " + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("=================================================");
        System.out.println();

        // ── CafeAI setup ──────────────────────────────────────────────────────
        var lookup   = new VendorContractLookup();
        var recorder = new DiscrepancyRecorder();
        var approver = new InvoiceApprover();

        var app = CafeAI.create();
        app.ai(OpenAI.gpt4o());
        app.system(SYSTEM_PROMPT);
        app.guard(GuardRail.jailbreak());
        app.budget(TokenBudget.perMinute(30_000));   // OpenAI free tier
        app.retry(RetryPolicy.onRateLimit().maxAttempts(3).backoff(java.time.Duration.ofSeconds(10)));

        // The reconciliation tool loop is an agent — LangChain4j owns the loop,
        // CafeAI gives it the model, guardrails, and observability context.
        app.agent("reconciler", ReconciliationAgent.class)
           .tool(lookup)
           .tool(recorder)
           .tool(approver);

        // ── Service setup ─────────────────────────────────────────────────────
        var analyzer   = new EmailSentimentAnalyzer(app);
        var classifier = new AttachmentTypeClassifier(app);
        var extractor  = new InvoiceDataExtractor(app);
        var reconciler = new InvoiceAmountReconciler(app);
        var composer   = new ResponseComposer(app);
        var notifier   = new EscalationNotifier(app);

        // ── Gmail setup ───────────────────────────────────────────────────────
        Gmail gmail    = GmailClientFactory.build();
        var fetcher    = new GmailUnreadEmailFetcher(gmail);
        var reader     = new GmailEmailBodyReader(gmail);
        var downloader = new GmailAttachmentDownloader(gmail);
        var sender     = new GmailEmailSender(gmail);

        // ── Fetch unread emails ───────────────────────────────────────────────
        List<Message> unread = fetcher.fetchUnreadInbox();
        int total = Math.min(unread.size(), MAX_EMAILS);

        System.out.printf("Found %d unread email(s) in INBOX. " +
                          "Processing up to %d.%n%n", unread.size(), total);

        List<ProcessingResult> results = new ArrayList<>();

        // ── Process each email ────────────────────────────────────────────────
        for (int i = 0; i < total; i++) {
            String messageId = unread.get(i).getId();
            System.out.printf("---------------------------------------------%n");
            System.out.printf("Email %d of %d  [id: %s]%n", i + 1, total, messageId);

            try {
                // Read first -- cheap, no AI -- so pre-filter runs before any token spend
                EmailContent email = reader.read(messageId);
                System.out.println("  From:    " + email.from());
                System.out.println("  Subject: " + email.subject());

                if (isObviouslyNotVendor(email)) {
                    System.out.println("  SKIPPED -- not a vendor email (pre-filter)");
                    results.add(new ProcessingResult(messageId, email.subject(),
                        email.from(), "SKIPPED", "Pre-filtered: not a vendor email"));
                    continue;
                }

                ProcessingResult result = processEmail(
                    email, downloader, sender,
                    analyzer, classifier, extractor, reconciler,
                    composer, notifier, dryRun);
                results.add(result);

            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
                results.add(new ProcessingResult(
                    messageId, "unknown", "unknown", "ERROR", e.getMessage()));
            }

            // Token budget managed by CafeAI -- no manual Thread.sleep needed

            System.out.println();
        }

        // ── Summary ───────────────────────────────────────────────────────────
        printSummary(results, approver, recorder, dryRun);
    }

    // ── Pre-filter ────────────────────────────────────────────────────────────

    /**
     * Returns true for emails that are obviously not vendor invoices.
     *
     * <p>Checked before any AI call -- no token cost for filtered emails.
     * Add patterns here as new non-vendor email types appear in the inbox.
     */
    private static boolean isObviouslyNotVendor(EmailContent email) {
        String from    = email.from().toLowerCase();
        String subject = email.subject().toLowerCase();

        return from.contains("anthropic.com")
            || from.contains("noreply")
            || from.contains("no-reply")
            || from.contains("newsletter")
            || subject.contains("webinar")
            || subject.contains("unsubscribe")
            || subject.contains("log in to")
            || subject.contains("sign in to")
            || subject.contains("did you know");
    }

    // ── Email processor ───────────────────────────────────────────────────────

    private static ProcessingResult processEmail(
            EmailContent email,
            GmailAttachmentDownloader downloader,
            GmailEmailSender sender,
            EmailSentimentAnalyzer analyzer,
            AttachmentTypeClassifier classifier,
            InvoiceDataExtractor extractor,
            InvoiceAmountReconciler reconciler,
            ResponseComposer composer,
            EscalationNotifier notifier,
            boolean dryRun) throws Exception {

        // Step 1 -- Sentiment analysis
        System.out.println("  Sentiment: analysing...");
        SentimentResult sentiment = analyzer.analyze(
            email.body().isBlank() ? email.subject() : email.body());
        System.out.printf("  Sentiment: %s / %s / escalate=%b%n",
            sentiment.tone(), sentiment.urgency(), sentiment.escalate());

        // Step 2 -- Escalation path
        if (sentiment.escalate()) {
            System.out.println("  -> ESCALATION PATH");

            String supervisorNote = notifier.composeEscalationNote(email, sentiment);
            String vendorAck      = notifier.composeVendorAcknowledgement(
                email, sentiment, AP_SENDER_NAME);

            if (!dryRun) {
                sender.send(SUPERVISOR_EMAIL,
                    "ESCALATION: " + email.subject(), supervisorNote);
                sender.send(email.from(),
                    "Re: " + email.subject(), vendorAck);
                System.out.println("  Escalation emails sent.");
            } else {
                System.out.println("  [DRY RUN] Would send escalation to supervisor.");
                System.out.println("  [DRY RUN] Would send acknowledgement to vendor.");
                System.out.println();
                System.out.println("  -- Supervisor Note --");
                System.out.println("  " + supervisorNote.replace("\n", "\n  "));
            }

            return new ProcessingResult(email.messageId(), email.subject(),
                email.from(), "ESCALATED", sentiment.recommendedAction());
        }

        // Step 3 -- Classify attachments and extract invoice data
        InvoiceData invoice = null;

        if (email.hasAttachments()) {
            System.out.println("  Attachments: " + email.attachments().size());
            for (AttachmentInfo att : email.attachments()) {
                System.out.println("    Classifying: " + att.filename());
                byte[] bytes = downloader.download(email.messageId(), att.attachmentId());

                boolean isImage = att.mimeType().startsWith("image/");
                boolean isPdf   = att.mimeType().contains("pdf");

                if (!isImage && !isPdf) {
                    System.out.println("    Skipped -- unsupported type: " + att.mimeType());
                    continue;
                }

                var classification = isImage
                    ? classifier.classifyImage(bytes, att.mimeType())
                    : classifier.classifyPdf(bytes);

                System.out.printf("    Classification: %s (isInvoice=%b, confidence=%s)%n",
                    classification.docType(),
                    classification.isInvoice(),
                    classification.confidence());

                if (classification.isInvoice() && invoice == null) {
                    System.out.println("    Extracting invoice data...");
                    invoice = isImage
                        ? extractor.extractFromImage(bytes, att.mimeType())
                        : extractor.extractFromPdf(bytes);
                }
            }
        }

        // Fallback -- try email body if no invoice found in attachments
        if (invoice == null && !email.body().isBlank()) {
            System.out.println("  No invoice attachment -- trying email body...");
            invoice = extractor.extractFromEmailBody(email.body());
        }

        if (invoice == null || !invoice.isComplete()) {
            System.out.println("  SKIPPED -- could not extract invoice data.");
            return new ProcessingResult(email.messageId(), email.subject(),
                email.from(), "SKIPPED", "No extractable invoice found");
        }

        System.out.printf("  Invoice: %s | %s | $%s%n",
                invoice.vendorName(), invoice.invoiceNumber(), invoice.totalAmount());

        // Step 4 -- Reconcile
        System.out.println("  Reconciling...");
        ReconciliationResult reconciliation = reconciler.reconcile(invoice);
        System.out.printf("  Decision: %s (variance $%.2f / %.1f%%)%n",
            reconciliation.decision(),
            reconciliation.variance(),
            reconciliation.variancePct());

        // Step 5 -- Compose and send reply
        System.out.println("  Composing reply...");
        String replyBody = composer.compose(reconciliation, AP_SENDER_NAME);

        if (!dryRun) {
            sender.send(email.from(), "Re: " + email.subject(), replyBody);
            System.out.println("  Reply sent.");
        } else {
            System.out.println("  [DRY RUN] Reply drafted (not sent):");
            String preview = replyBody.length() > 300
                ? replyBody.substring(0, 300) + "..." : replyBody;
            System.out.println("  " + preview.replace("\n", "\n  "));
        }

        String outcome = switch (reconciliation.decision()) {
            case APPROVED           -> "APPROVED";
            case QUERIED            -> "QUERIED";
            case DISCREPANCY_LOGGED -> "DISCREPANCY";
        };

        return new ProcessingResult(email.messageId(), email.subject(),
            email.from(), outcome, reconciliation.explanation());
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private static void printSummary(List<ProcessingResult> results,
                                      InvoiceApprover approver,
                                      DiscrepancyRecorder recorder,
                                      boolean dryRun) {
        System.out.println("=================================================");
        System.out.println("  Run Summary");
        System.out.println("=================================================");
        System.out.printf("  Emails processed: %d%n", results.size());
        System.out.printf("  Approvals:        %d%n", approver.getLog().size());
        System.out.printf("  Discrepancies:    %d%n", recorder.getLog().size());
        System.out.printf("  Escalations:      %d%n",
            results.stream().filter(r -> "ESCALATED".equals(r.outcome())).count());
        System.out.printf("  Skipped:          %d%n",
            results.stream().filter(r -> "SKIPPED".equals(r.outcome())).count());
        System.out.printf("  Errors:           %d%n",
            results.stream().filter(r -> "ERROR".equals(r.outcome())).count());
        System.out.println();
        System.out.println("  Detail:");
        for (ProcessingResult r : results) {
            System.out.printf("  [%-11s] %s%n", r.outcome(), r.subject());
        }
        System.out.println();
        if (dryRun) {
            System.out.println("  DRY RUN complete -- no emails were sent.");
        }
        System.out.println("=================================================");
    }
}
