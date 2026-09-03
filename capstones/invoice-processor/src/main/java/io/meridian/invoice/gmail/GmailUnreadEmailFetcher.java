package io.meridian.invoice.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ListMessagesResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches unread emails from Gmail matching a label filter.
 *
 * <p>Returns lightweight message stubs (id + threadId only).
 * Use {@link GmailEmailBodyReader} to read the full content of each message.
 *
 * <p>One responsibility: list unread message IDs. Nothing else.
 */
public class GmailUnreadEmailFetcher {

    private final Gmail gmail;

    public GmailUnreadEmailFetcher(Gmail gmail) {
        this.gmail = gmail;
    }

    /**
     * Returns unread message stubs from the given label.
     *
     * @param label Gmail label name, e.g. "INBOX", "Monitoring"
     * @param maxResults maximum number of messages to return
     * @return list of message stubs — id and threadId populated, body not fetched
     * @throws IOException if the Gmail API call fails
     */
    public List<Message> fetchUnread(String label, long maxResults) throws IOException {
        ListMessagesResponse response = gmail.users()
            .messages()
            .list("me")
            .setLabelIds(List.of(label))
            .setQ("is:unread")
            .setMaxResults(maxResults)
            .execute();

        List<Message> messages = response.getMessages();
        return messages != null ? messages : new ArrayList<>();
    }

    /**
     * Returns all unread messages from INBOX, up to 50.
     */
    public List<Message> fetchUnreadInbox() throws IOException {
        return fetchUnread("INBOX", 50);
    }
}
