package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.internal.Cli;
import io.helidon.json.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AwsCredentials#fromCli(String)} against the real AWS CLI and AWS (MILESTONE-20 phase 7).
 * Each part runs only when asked for, and is skipped otherwise:
 *
 * <ul>
 *   <li>{@code AWS_CLI_LIVE_PROFILE}: after {@code cafeai login aws --profile <p>}, what
 *       {@code aws configure export-credentials} really answers, and a request CafeAI signs with
 *       those credentials (STS GetCallerIdentity, free) accepted by AWS.</li>
 *   <li>{@code AWS_CLI_SIGNED_OUT_PROFILE}: after {@code cafeai logout aws --profile <p>}, the
 *       sign-in-again message.</li>
 *   <li>{@code AWS_BEDROCK_MODEL} (with {@code AWS_CLI_LIVE_PROFILE}): Claude in Amazon Bedrock,
 *       plain and streamed, in the profile's region.</li>
 * </ul>
 */
@DisplayName("AWS CLI sign-in, live")
class AwsCliCredentialsLiveTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String profile() {
        String profile = System.getenv("AWS_CLI_LIVE_PROFILE");
        Assumptions.assumeTrue(profile != null && !profile.isBlank(),
                "set AWS_CLI_LIVE_PROFILE after `cafeai login aws --profile <p>` to run against AWS");
        return profile;
    }

    private static Cli.Result aws(String... args) {
        var command = new ArrayList<String>();
        command.add(Cli.find("aws", System::getenv).orElseThrow().toString());
        command.addAll(List.of(args));
        return Cli.run(command, Duration.ofSeconds(60));
    }

    /** As AwsCredentials finds it: AWS_REGION, AWS_DEFAULT_REGION, then the profile's; null if none. */
    private static String region(String profile) {
        for (String variable : List.of("AWS_REGION", "AWS_DEFAULT_REGION")) {
            String region = System.getenv(variable);
            if (region != null && !region.isBlank()) return region;
        }
        String region = aws("configure", "get", "region", "--profile", profile).out().strip();
        return region.isEmpty() ? null : region;
    }

    @Test @DisplayName("the CLI's credentials sign a request AWS accepts")
    void signedRequest() throws Exception {
        String profile = profile();
        Cli.Result raw = aws("configure", "export-credentials", "--format", "process", "--profile", profile);
        assertThat(raw.ok()).as("aws answered: " + raw.err()).isTrue();
        var json = JsonParser.create(raw.out()).readJsonValue().asObject();
        System.out.println("[live] export-credentials: fields " + json.keys() + ", Expiration "
                + json.stringValue("Expiration").orElse("(none: long-lived keys)"));

        String region = region(profile);
        var credentials = AwsCredentials.fromCli(profile).service("sts");
        if (region == null) {
            URI anywhere = URI.create("https://sts.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15");
            assertThatThrownBy(() -> credentials.sign("GET", anywhere, Map.of("host", anywhere.getHost()), new byte[0]))
                    .satisfies(e -> System.out.println("[live] no region: " + e.getMessage()))
                    .hasMessageContaining("aws configure set region");
            Assumptions.abort("the profile names no region; set AWS_DEFAULT_REGION to sign a request");
        }
        URI uri = URI.create("https://sts." + region + ".amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15");
        Map<String, String> headers = credentials.sign("GET", uri, Map.of("host", uri.getHost()), new byte[0]);
        var request = HttpRequest.newBuilder(uri).GET();
        headers.forEach((name, value) -> { if (!name.equalsIgnoreCase("host")) request.header(name, value); });
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String arn = response.body().replaceAll("(?s).*<Arn>(.*?)</Arn>.*", "$1").replaceAll("\\d{12}", "<account>");
        System.out.println("[live] region " + region + ", GetCallerIdentity HTTP " + response.statusCode()
                + (response.statusCode() == 200 ? ", " + arn : ": " + response.body()));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test @DisplayName("signed out: the message says to sign in again")
    void signedOut() {
        String profile = System.getenv("AWS_CLI_SIGNED_OUT_PROFILE");
        Assumptions.assumeTrue(profile != null && !profile.isBlank(),
                "set AWS_CLI_SIGNED_OUT_PROFILE after `cafeai logout aws --profile <p>`");
        var credentials = AwsCredentials.fromCli(profile).service("sts").region("us-east-1");
        URI uri = URI.create("https://sts.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15");
        assertThatThrownBy(() -> credentials.sign("GET", uri, Map.of("host", uri.getHost()), new byte[0]))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> System.out.println("[live] signed out: " + e.getMessage()))
                .hasMessageContaining("cafeai login aws --profile " + profile);
    }

    @Test @DisplayName("Claude in Amazon Bedrock with the CLI's sign-in, plain and streamed")
    void bedrock() {
        String profile = profile();
        String model = System.getenv("AWS_BEDROCK_MODEL");
        Assumptions.assumeTrue(model != null && !model.isBlank(), "set AWS_BEDROCK_MODEL to call Claude in Bedrock");
        String base = "https://bedrock-mantle." + region(profile) + ".api.aws/anthropic";

        var app = CafeAI.create();
        app.ai(Anthropic.of(model).withBaseUrl(base).withCredentials(AwsCredentials.fromCli(profile)));
        String answer = app.prompt("Reply with the single word: ready").call().text();
        System.out.println("[live] bedrock: " + answer);
        assertThat(answer).isNotBlank();

        List<String> tokens = new ArrayList<>();
        app.prompt("Count from one to five in words, separated by commas.").stream(tokens::add);
        System.out.println("[live] bedrock stream: " + tokens.size() + " chunks -> " + String.join("", tokens));
        assertThat(String.join("", tokens)).containsIgnoringCase("five");
    }
}
