package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GoogleApplicationDefault} against real Google (MILESTONE-20 phase 4). Each part runs only
 * when asked for, and is skipped otherwise:
 *
 * <ul>
 *   <li>{@code GOOGLE_ADC_LIVE=1}: after {@code cafeai login google}, a token from Google's real
 *       token endpoint, checked at Google's tokeninfo endpoint.</li>
 *   <li>{@code GOOGLE_ADC_REVOKED_FILE}: a copy of the credentials file taken before
 *       {@code gcloud auth application-default revoke}; its refresh must be refused with the
 *       sign-in-again message.</li>
 *   <li>{@code VERTEX_PROJECT} and {@code VERTEX_LIVE_MODEL} ({@code VERTEX_LOCATION}, default
 *       {@code global}): Claude on Vertex AI with the sign-in, plain and streamed.</li>
 * </ul>
 */
@DisplayName("Google Application Default Credentials, live")
class GoogleApplicationDefaultLiveTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test @DisplayName("a real token from the developer's sign-in, kept for the second call")
    void token() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("GOOGLE_ADC_LIVE")),
                "set GOOGLE_ADC_LIVE=1 after `cafeai login google` to run against Google");

        var credentials = GoogleApplicationDefault.credentials();
        System.out.println("[live] file: " + credentials.file());
        String token = credentials.token();
        assertThat(token).isNotBlank();
        assertThat(credentials.token()).as("the second call reuses the token").isEqualTo(token);

        var info = HTTP.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/tokeninfo?access_token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
        String body = info.body().replace(token, "<token>");
        System.out.println("[live] tokeninfo: HTTP " + info.statusCode() + " " + body);
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(body).contains(GoogleApplicationDefault.CLOUD_PLATFORM);
    }

    @Test @DisplayName("a revoked sign-in is refused with the sign-in-again message")
    void revoked() {
        String file = System.getenv("GOOGLE_ADC_REVOKED_FILE");
        Assumptions.assumeTrue(file != null && Files.isRegularFile(Path.of(file)),
                "set GOOGLE_ADC_REVOKED_FILE to a copy of the credentials file taken before revoking it");

        var credentials = GoogleApplicationDefault.credentials().environment(name ->
                "GOOGLE_APPLICATION_CREDENTIALS".equals(name) ? file : System.getenv(name));
        assertThatThrownBy(credentials::token)
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> System.out.println("[live] refused: " + e.getMessage()))
                .hasMessageContaining("cafeai login google");
    }

    @Test @DisplayName("Claude on Vertex AI with the developer's sign-in, plain and streamed")
    void vertex() {
        String project = System.getenv("VERTEX_PROJECT");
        String model = System.getenv("VERTEX_LIVE_MODEL");
        Assumptions.assumeTrue(project != null && !project.isBlank() && model != null && !model.isBlank(),
                "set VERTEX_PROJECT and VERTEX_LIVE_MODEL to call Claude on Vertex AI");
        String location = System.getenv().getOrDefault("VERTEX_LOCATION", "global");

        var app = CafeAI.create();
        app.ai(Anthropic.onVertex(model, project, location).withCredentials(GoogleApplicationDefault.credentials()));

        String answer = app.prompt("Reply with the single word: ready").call().text();
        System.out.println("[live] vertex: " + answer);
        assertThat(answer).isNotBlank();

        List<String> tokens = new ArrayList<>();
        app.prompt("Count from one to five in words, separated by commas.").stream(tokens::add);
        System.out.println("[live] vertex stream: " + tokens.size() + " chunks -> " + String.join("", tokens));
        assertThat(String.join("", tokens)).containsIgnoringCase("five");
    }
}
