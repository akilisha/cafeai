package io.meridian.invoice.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Reads the full content of a Gmail message by ID.
 *
 * <p>Returns a structured {@link EmailContent} record containing
 * the subject, sender, body text, and attachment metadata.
 *
 * <p>One responsibility: read one email's content. Nothing else.
 */
public class GmailEmailBodyReader {

    private final Gmail gmail;

    public GmailEmailBodyReader(Gmail gmail) {
        this.gmail = gmail;
    }

    /**
     * Reads the full content of the message with the given ID.
     *
     * @param messageId Gmail message ID from {@link GmailUnreadEmailFetcher}
     * @return structured email content
     * @throws IOException if the Gmail API call fails
     */
    public EmailContent read(String messageId) throws IOException {
        Message message = gmail.users()
            .messages()
            .get("me", messageId)
            .setFormat("full")
            .execute();

        Map<String, String> headers = extractHeaders(message);
        String body             = extractBody(message.getPayload());
        List<AttachmentInfo> attachments = extractAttachmentInfo(message.getPayload());

        return new EmailContent(
            messageId,
            headers.getOrDefault("Subject", "(no subject)"),
            headers.getOrDefault("From", "(unknown sender)"),
            headers.getOrDefault("Date", ""),
            body,
            attachments
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, String> extractHeaders(Message message) {
        Map<String, String> headers = new HashMap<>();
        if (message.getPayload() == null) return headers;
        List<MessagePartHeader> headerList = message.getPayload().getHeaders();
        if (headerList == null) return headers;
        for (MessagePartHeader h : headerList) {
            headers.put(h.getName(), h.getValue());
        }
        return headers;
    }

    private String extractBody(MessagePart part) {
        if (part == null) return "";

        // Plain text part
        if ("text/plain".equals(part.getMimeType()) && part.getBody() != null) {
            String data = part.getBody().getData();
            if (data != null) {
                return new String(
                    Base64.getUrlDecoder().decode(data),
                    StandardCharsets.UTF_8);
            }
        }

        // Recurse into multipart
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String body = extractBody(child);
                if (!body.isBlank()) return body;
            }
        }

        return "";
    }

    private List<AttachmentInfo> extractAttachmentInfo(MessagePart part) {
        if (part == null) return List.of();
        List<AttachmentInfo> result = new java.util.ArrayList<>();
        collectAttachments(part, result);
        return result;
    }

    private void collectAttachments(MessagePart part, List<AttachmentInfo> result) {
        if (part.getFilename() != null && !part.getFilename().isBlank()) {
            String attachmentId = part.getBody() != null
                ? part.getBody().getAttachmentId()
                : null;
            if (attachmentId != null) {
                result.add(new AttachmentInfo(
                    part.getFilename(),
                    part.getMimeType(),
                    attachmentId
                ));
            }
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectAttachments(child, result);
            }
        }
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /**
     * Structured representation of a Gmail message's content.
     */
    public record EmailContent(
        String messageId,
        String subject,
        String from,
        String date,
        String body,
        List<AttachmentInfo> attachments
    ) {
        public boolean hasAttachments() {
            return attachments != null && !attachments.isEmpty();
        }
    }

    /**
     * Metadata about an attachment — enough to download it via
     * {@link GmailAttachmentDownloader}.
     */
    public record AttachmentInfo(
        String filename,
        String mimeType,
        String attachmentId
    ) {}
}
