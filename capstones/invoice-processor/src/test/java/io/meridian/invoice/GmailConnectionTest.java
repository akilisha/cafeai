package io.meridian.invoice;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Profile;

import java.util.List;

/**
 * Phase 2 connection test — run once to confirm Gmail API access.
 *
 * <p>On first run, this opens a browser window for OAuth2 authorization.
 * After you grant access, it prints your Gmail address and label list,
 * confirming the full authentication flow works end-to-end.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew run -PmainClass=io.meridian.invoice.GmailConnectionTest
 * </pre>
 *
 * <p>Expected output:
 * <pre>
 *   Connected to Gmail as: your@gmail.com
 *   Total messages: 1,234
 *   Labels (17):
 *     INBOX
 *     SENT
 *     ...
 * </pre>
 */
public class GmailConnectionTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Connecting to Gmail...");
        System.out.println("(First run will open a browser for authorization)");
        System.out.println();

        // Build authenticated Gmail client
        Gmail gmail = GmailClientFactory.build();

        // ── 1. Confirm identity ────────────────────────────────────────────────
        Profile profile = gmail.users()
            .getProfile("me")
            .execute();

        System.out.println("✓ Connected to Gmail as: " + profile.getEmailAddress());
        System.out.printf ("  Total messages in mailbox: %,d%n",
            profile.getMessagesTotal());
        System.out.printf ("  Total threads in mailbox:  %,d%n%n",
            profile.getThreadsTotal());

        // ── 2. List labels ─────────────────────────────────────────────────────
        ListLabelsResponse labelsResponse = gmail.users()
            .labels()
            .list("me")
            .execute();

        List<Label> labels = labelsResponse.getLabels();
        System.out.printf("Labels (%d):%n", labels.size());

        labels.stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .forEach(label -> System.out.println("  " + label.getName()));

        System.out.println();
        System.out.println("Phase 2 complete — Gmail connection verified.");
        System.out.println("Token stored in tokens/ — subsequent runs will be silent.");
    }
}
