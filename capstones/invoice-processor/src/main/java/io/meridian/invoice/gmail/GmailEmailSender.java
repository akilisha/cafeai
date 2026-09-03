package io.meridian.invoice.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Properties;

/**
 * Sends an email via Gmail on behalf of the authenticated account.
 *
 * <p>Used for vendor reply emails and escalation notifications.
 * In dry-run mode, the caller is responsible for skipping this class.
 *
 * <p>One responsibility: send one email. Nothing else.
 */
public class GmailEmailSender {

    private final Gmail gmail;

    public GmailEmailSender(Gmail gmail) {
        this.gmail = gmail;
    }

    /**
     * Sends a plain-text email.
     *
     * @param to      recipient email address
     * @param subject email subject line
     * @param body    plain text email body
     * @throws IOException        if the Gmail API call fails
     * @throws MessagingException if the MIME message cannot be constructed
     */
    public void send(String to, String subject, String body)
            throws IOException, MessagingException {

        MimeMessage mimeMessage = buildMimeMessage(to, subject, body);
        Message message = encodeMessage(mimeMessage);
        gmail.users().messages().send("me", message).execute();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private MimeMessage buildMimeMessage(String to, String subject, String body)
            throws MessagingException {

        Session session = Session.getDefaultInstance(new Properties(), null);
        MimeMessage email = new MimeMessage(session);
        email.setFrom(new InternetAddress("me"));
        email.addRecipient(MimeMessage.RecipientType.TO, new InternetAddress(to));
        email.setSubject(subject);
        email.setText(body);
        return email;
    }

    private Message encodeMessage(MimeMessage mimeMessage)
            throws IOException, MessagingException {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        String encoded = Base64.getUrlEncoder()
            .encodeToString(buffer.toByteArray());
        return new Message().setRaw(encoded);
    }
}
