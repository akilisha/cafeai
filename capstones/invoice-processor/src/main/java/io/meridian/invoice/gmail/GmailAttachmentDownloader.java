package io.meridian.invoice.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.MessagePartBody;

import java.io.IOException;
import java.util.Base64;

/**
 * Downloads a specific email attachment as raw bytes.
 *
 * <p>Takes the messageId and attachmentId from {@link GmailEmailBodyReader.AttachmentInfo}
 * and returns the decoded attachment bytes ready for multimodal processing.
 *
 * <p>One responsibility: download one attachment. Nothing else.
 */
public class GmailAttachmentDownloader {

    private final Gmail gmail;

    public GmailAttachmentDownloader(Gmail gmail) {
        this.gmail = gmail;
    }

    /**
     * Downloads an attachment and returns its raw bytes.
     *
     * @param messageId    Gmail message ID containing the attachment
     * @param attachmentId attachment ID from {@link GmailEmailBodyReader.AttachmentInfo}
     * @return raw attachment bytes
     * @throws IOException if the Gmail API call fails
     */
    public byte[] download(String messageId, String attachmentId) throws IOException {
        MessagePartBody body = gmail.users()
            .messages()
            .attachments()
            .get("me", messageId, attachmentId)
            .execute();

        return Base64.getUrlDecoder().decode(body.getData());
    }
}
