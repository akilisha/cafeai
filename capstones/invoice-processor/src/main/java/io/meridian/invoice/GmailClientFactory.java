package io.meridian.invoice;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Builds and returns an authenticated Gmail API client.
 *
 * <p>On first run, this opens a browser window for OAuth2 authorization.
 * The resulting token is stored in {@code tokens/} and reused on all
 * subsequent runs — no browser interaction needed after the first time.
 *
 * <p>Required scopes:
 * <ul>
 *   <li>GMAIL_READONLY — read emails and attachments</li>
 *   <li>GMAIL_SEND — send reply and escalation emails</li>
 *   <li>GMAIL_MODIFY — mark emails as processed (add label)</li>
 * </ul>
 */
public class GmailClientFactory {

    private static final String APPLICATION_NAME = "Invoice Processor — Meridian AP";
    private static final GsonFactory JSON_FACTORY  = GsonFactory.getDefaultInstance();

    // Token storage — created on first run, gitignored
    private static final String TOKENS_DIR = "tokens";

    // Credentials file bundled in resources
    private static final String CREDENTIALS_RESOURCE =
        "/credentials/gmail-credentials.json";

    // Scopes required by this application
    private static final List<String> SCOPES = List.of(
        GmailScopes.GMAIL_READONLY,
        GmailScopes.GMAIL_SEND,
        GmailScopes.GMAIL_MODIFY
    );

    /**
     * Returns an authenticated Gmail API client.
     *
     * <p>First call opens a browser for authorization.
     * Subsequent calls reuse the stored token silently.
     *
     * @throws IOException              if credentials file is missing or unreadable
     * @throws GeneralSecurityException if TLS setup fails
     */
    public static Gmail build() throws IOException, GeneralSecurityException {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential     = authorize(transport);

        return new Gmail.Builder(transport, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static Credential authorize(NetHttpTransport transport)
            throws IOException {

        // Load client secrets from the bundled credentials JSON
        InputStream credStream = GmailClientFactory.class
            .getResourceAsStream(CREDENTIALS_RESOURCE);

        if (credStream == null) {
            throw new IOException(
                "Gmail credentials not found at: " + CREDENTIALS_RESOURCE + "\n" +
                "Copy your OAuth2 JSON to: " +
                "src/main/resources/credentials/gmail-credentials.json");
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
            JSON_FACTORY, new InputStreamReader(credStream));

        // Build the authorization flow
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
            transport, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(
                new FileDataStoreFactory(new File(TOKENS_DIR)))
            .setAccessType("offline")   // request refresh token for silent renewal
            .build();

        // On first run: opens browser. Subsequent runs: loads stored token.
        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
            .setPort(8888)
            .build();

        return new AuthorizationCodeInstalledApp(flow, receiver)
            .authorize("user");
    }
}
