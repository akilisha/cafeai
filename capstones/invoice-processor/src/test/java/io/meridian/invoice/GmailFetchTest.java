package io.meridian.invoice;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import io.meridian.invoice.gmail.GmailAttachmentDownloader;
import io.meridian.invoice.gmail.GmailEmailBodyReader;
import io.meridian.invoice.gmail.GmailEmailBodyReader.AttachmentInfo;
import io.meridian.invoice.gmail.GmailEmailBodyReader.EmailContent;
import io.meridian.invoice.gmail.GmailUnreadEmailFetcher;

import java.util.List;

/**
 * Phase 3 manual integration test.
 *
 * <p>Fetches the first unread email from INBOX, prints its subject,
 * sender, body snippet, and attachment list. Confirms all four
 * Gmail tool classes wire together correctly before the AI layer
 * is introduced in Phase 4.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew testGmailFetch
 * </pre>
 */
public class GmailFetchTest {

    public static void main(String[] args) throws Exception {
        Gmail gmail = GmailClientFactory.build();

        // ── 1. Fetch unread messages ───────────────────────────────────────────
        GmailUnreadEmailFetcher fetcher = new GmailUnreadEmailFetcher(gmail);
        List<Message> unread = fetcher.fetchUnreadInbox();

        System.out.printf("Unread messages in INBOX: %d%n%n", unread.size());

        if (unread.isEmpty()) {
            System.out.println("No unread messages. Send yourself a test email and retry.");
            return;
        }

        // ── 2. Read the first message ──────────────────────────────────────────
        GmailEmailBodyReader reader = new GmailEmailBodyReader(gmail);
        EmailContent email = reader.read(unread.get(0).getId());

        System.out.println("=== First Unread Email ===");
        System.out.println("From:    " + email.from());
        System.out.println("Subject: " + email.subject());
        System.out.println("Date:    " + email.date());
        System.out.println();
        System.out.println("--- Body (first 500 chars) ---");
        String body = email.body();
        System.out.println(body.length() > 500 ? body.substring(0, 500) + "..." : body);
        System.out.println();

        // ── 3. Report attachments ──────────────────────────────────────────────
        if (email.hasAttachments()) {
            System.out.printf("Attachments (%d):%n", email.attachments().size());
            GmailAttachmentDownloader downloader = new GmailAttachmentDownloader(gmail);
            for (AttachmentInfo att : email.attachments()) {
                byte[] bytes = downloader.download(email.messageId(), att.attachmentId());
                System.out.printf("  %-40s %-25s %,d bytes%n",
                    att.filename(), att.mimeType(), bytes.length);
            }
        } else {
            System.out.println("No attachments.");
        }

        System.out.println();
        System.out.println("Phase 3 complete — Gmail tool classes verified.");
    }
}
