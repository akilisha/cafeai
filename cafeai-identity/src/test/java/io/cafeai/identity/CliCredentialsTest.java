package io.cafeai.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.internal.Cli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Credentials from the developer's own cloud CLI sign-in: az, aws, gcloud's application default")
class CliCredentialsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @TempDir Path dir;
    private FakeAnthropicServer model;
    private CafeAI app;
    private final List<List<String>> ran = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        model = new FakeAnthropicServer();
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        model.close();
    }

    private String ask(AiProvider provider) throws Exception {
        app = CafeAI.create();
        app.ai(provider);
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ask")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private Optional<Path> program(String name) {
        return Optional.of(dir.resolve(name));
    }

    // ── Azure: az account get-access-token ──────────────────────────────────────

    @Test @DisplayName("az: the token for the scope, as Bearer, kept until five minutes before it expires")
    void azure() throws Exception {
        var azure = AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY).tenant("t-123").cli(() -> program("az"), command -> {
            ran.add(command);
            return new Cli.Result(0, "{\"accessToken\":\"entra-" + ran.size() + "\",\"expiresOn\":\"2026-10-10 08:00:00.000000\","
                    + "\"expires_on\":" + NOW.plusSeconds(3600).getEpochSecond() + ",\"tokenType\":\"Bearer\"}", "");
        }, at(NOW));

        assertThat(azure.token()).isEqualTo("entra-1");
        assertThat(azure.token()).as("kept, not asked again").isEqualTo("entra-1");
        assertThat(ran).singleElement().isEqualTo(List.of(dir.resolve("az").toString(), "account", "get-access-token",
                "--scope", "https://ai.azure.com/.default", "--output", "json", "--tenant", "t-123"));

        azure.cli(() -> program("az"), azure_runner(), at(NOW.plusSeconds(3600 - 299)));
        assertThat(azure.token()).as("renewed within five minutes of expiry").isEqualTo("entra-2");
    }

    private java.util.function.Function<List<String>, Cli.Result> azure_runner() {
        return command -> {
            ran.add(command);
            return new Cli.Result(0, "{\"accessToken\":\"entra-" + ran.size() + "\",\"expires_on\":\""
                    + NOW.plusSeconds(7200).getEpochSecond() + "\"}", "");
        };
    }

    @Test @DisplayName("az: a Foundry call carries the developer's Entra token")
    void azureCall() throws Exception {
        var azure = AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY).cli(() -> program("az"), azure_runner(), Clock.systemUTC());
        assertThat(ask(Anthropic.of("claude-opus-5-5").withBaseUrl(model.baseUrl("/anthropic")).withCredentials(azure)))
                .isEqualTo("ok");
        assertThat(model.calls.getFirst().bearer()).isEqualTo("entra-1");
    }

    @Test @DisplayName("az: not signed in, and not installed, say what to do")
    void azureErrors() {
        var notSignedIn = AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY).cli(() -> program("az"),
                command -> new Cli.Result(1, "", "ERROR: Please run 'az login' to setup account."), at(NOW));
        assertThatThrownBy(notSignedIn::token).isInstanceOf(IdentityException.class)
                .hasMessageContaining("cafeai login azure").hasMessageContaining("Please run 'az login'");

        var missing = AzureCliCredentials.scope(AzureCliCredentials.FOUNDRY).cli(Optional::empty,
                command -> { throw new AssertionError(); }, at(NOW));
        assertThatThrownBy(missing::token).hasMessageContaining("install-azure-cli");
    }

    @Test @DisplayName("az: a real process (a script standing in for az) is run and its JSON read, on this OS")
    void azureRealProcess() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path json = Files.writeString(dir.resolve("token.json"),
                "{\"accessToken\":\"from-a-real-process\",\"expires_on\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        Path args = dir.resolve("args.txt");
        Path script;
        if (windows) {
            script = Files.writeString(dir.resolve("az.cmd"),
                    "@echo off\r\necho %* > \"" + args + "\"\r\ntype \"" + json + "\"\r\n");
        } else {
            script = Files.writeString(dir.resolve("az"), "#!/bin/sh\necho \"$@\" > '" + args + "'\ncat '" + json + "'\n");
            script.toFile().setExecutable(true);
        }
        Map<String, String> env = Map.of("PATH", dir.toString());
        assertThat(Cli.find("az", env::get)).contains(script);

        var azure = AzureCliCredentials.scope(AzureCliCredentials.AZURE_OPENAI)
                .cli(() -> Cli.find("az", env::get), command -> Cli.run(command, Duration.ofSeconds(30)), Clock.systemUTC());
        assertThat(azure.token()).isEqualTo("from-a-real-process");
        assertThat(Files.readString(args)).contains("account get-access-token --scope https://cognitiveservices.azure.com/.default");
    }

    // ── AWS: aws configure export-credentials ───────────────────────────────────

    private java.util.function.Function<List<String>, Cli.Result> aws(String expiration) {
        return command -> {
            ran.add(command);
            if (command.contains("get")) return new Cli.Result(0, "us-west-2\n", "");
            return new Cli.Result(0, "{\"Version\":1,\"AccessKeyId\":\"ASIAEXAMPLE\",\"SecretAccessKey\":\"secret\","
                    + "\"SessionToken\":\"session-token\"" + (expiration == null ? "" : ",\"Expiration\":\"" + expiration + "\"") + "}", "");
        };
    }

    @Test @DisplayName("aws: the profile's role credentials sign each call (SigV4), in the profile's region")
    void awsCall() throws Exception {
        var credentials = AwsCredentials.fromCli("work").cli(() -> program("aws"), aws(Instant.now().plusSeconds(3600).toString()));
        assertThat(ask(Anthropic.of("anthropic.claude-opus-5-5").withBaseUrl(model.baseUrl("/anthropic"))
                .withCredentials(credentials))).isEqualTo("ok");

        var call = model.calls.getFirst();
        assertThat(call.authorization()).singleElement().asString()
                .startsWith("AWS4-HMAC-SHA256 Credential=ASIAEXAMPLE/")
                .contains("/us-west-2/bedrock-mantle/aws4_request");
        assertThat(call.securityToken()).isEqualTo("session-token");
        assertThat(ran).contains(
                List.of(dir.resolve("aws").toString(), "configure", "export-credentials", "--format", "process", "--profile", "work"),
                List.of(dir.resolve("aws").toString(), "configure", "get", "region", "--profile", "work"));
    }

    @Test @DisplayName("aws: credentials are kept until five minutes before their Expiration")
    void awsCached() throws Exception {
        var credentials = AwsCredentials.fromCli().region("us-east-1")
                .cli(() -> program("aws"), aws(NOW.plusSeconds(3600).toString())).clock(at(NOW));
        var headers = Map.of("host", "bedrock-mantle.us-east-1.api.aws");
        credentials.sign("POST", URI.create("https://bedrock-mantle.us-east-1.api.aws/anthropic/v1/messages"), headers, new byte[0]);
        credentials.sign("POST", URI.create("https://bedrock-mantle.us-east-1.api.aws/anthropic/v1/messages"), headers, new byte[0]);
        assertThat(ran).as("one export for both").hasSize(1);
        credentials.clock(at(NOW.plusSeconds(3600 - 299)));
        credentials.sign("POST", URI.create("https://bedrock-mantle.us-east-1.api.aws/anthropic/v1/messages"), headers, new byte[0]);
        assertThat(ran).hasSize(2);
    }

    @Test @DisplayName("aws: an expired SSO sign-in says to sign in again, with the profile")
    void awsExpired() {
        var credentials = AwsCredentials.fromCli("work").region("us-east-1").cli(() -> program("aws"), command ->
                new Cli.Result(255, "", "Error when retrieving token from sso: Token has expired and refresh failed"));
        assertThatThrownBy(() -> credentials.sign("POST", URI.create("https://x.example/v1/messages"),
                Map.of("host", "x.example"), new byte[0]))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("cafeai login aws --profile work").hasMessageContaining("Token has expired");
    }

    // ── Google: application default credentials ─────────────────────────────────

    private HttpServer google;
    private final List<Map<String, String>> refreshes = new CopyOnWriteArrayList<>();
    private final List<String> impersonations = new CopyOnWriteArrayList<>();
    private volatile int refreshStatus = 200;

    private String startGoogle() throws IOException {
        google = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        google.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            refreshes.add(Arrays.stream(form.split("&")).map(kv -> kv.split("=", 2))
                    .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8))));
            if (refreshStatus != 200) {
                send(exchange, refreshStatus, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");
                return;
            }
            send(exchange, 200, "{\"access_token\":\"ya29.person-" + refreshes.size() + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
        });
        google.createContext("/v1/projects/-/serviceAccounts/", exchange -> {
            impersonations.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"accessToken\":\"ya29.service-account\",\"expireTime\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        });
        google.start();
        return "http://127.0.0.1:" + google.getAddress().getPort();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static final String USER = "{\"client_id\":\"764086051850.apps.googleusercontent.com\",\"client_secret\":\"d-FL95Q\","
            + "\"refresh_token\":\"1//refresh\",\"type\":\"authorized_user\",\"quota_project_id\":\"my-project\"}";

    private GoogleApplicationDefault adc(String base, String content) throws IOException {
        Path gcloud = Files.createDirectories(dir.resolve("gcloud"));
        Files.writeString(gcloud.resolve("application_default_credentials.json"), content);
        Map<String, String> env = new HashMap<>(Map.of("CLOUDSDK_CONFIG", gcloud.toString()));
        return GoogleApplicationDefault.credentials().testing(base + "/token", env::get, Clock.systemUTC());
    }

    @Test @DisplayName("gcloud ADC: the refresh token is redeemed as Google's libraries do, and the call to Vertex carries it")
    void googleUser() throws Exception {
        String base = startGoogle();
        try {
            var credentials = adc(base, USER);
            assertThat(ask(Anthropic.onVertex("claude-opus-5-5", "my-project", "global").withBaseUrl(model.baseUrl(""))
                    .withCredentials(credentials))).isEqualTo("ok");
            assertThat(model.calls.getFirst().bearer()).isEqualTo("ya29.person-1");
            assertThat(refreshes).singleElement().isEqualTo(Map.of("grant_type", "refresh_token",
                    "client_id", "764086051850.apps.googleusercontent.com", "client_secret", "d-FL95Q",
                    "refresh_token", "1//refresh"));
            assertThat(credentials.token()).as("kept, not redeemed again").isEqualTo("ya29.person-1");
            assertThat(refreshes).hasSize(1);
        } finally {
            google.stop(0);
        }
    }

    @Test @DisplayName("gcloud ADC with --impersonate-service-account: the person's token, then the service account's")
    void googleImpersonated() throws Exception {
        String base = startGoogle();
        try {
            var credentials = adc(base, "{\"type\":\"impersonated_service_account\",\"delegates\":[],"
                    + "\"service_account_impersonation_url\":\"" + base
                    + "/v1/projects/-/serviceAccounts/claude@my-project.iam.gserviceaccount.com:generateAccessToken\","
                    + "\"source_credentials\":" + USER + "}");
            assertThat(credentials.token()).isEqualTo("ya29.service-account");
            assertThat(impersonations).singleElement().asString()
                    .startsWith("Bearer ya29.person-1 ").contains("cloud-platform").contains("\"lifetime\":\"3600s\"");
        } finally {
            google.stop(0);
        }
    }

    @Test @DisplayName("gcloud ADC: revoked sign-in, key files, and no file each say what to do")
    void googleErrors() throws Exception {
        String base = startGoogle();
        try {
            refreshStatus = 400;
            assertThatThrownBy(() -> adc(base, USER).token()).hasMessageContaining("cafeai login google")
                    .hasMessageContaining("expired or was revoked");
            assertThatThrownBy(() -> adc(base, "{\"type\":\"service_account\",\"private_key\":\"x\"}").token())
                    .hasMessageContaining("long-lived key");
            Map<String, String> env = Map.of("CLOUDSDK_CONFIG", dir.resolve("nowhere").toString());
            assertThatThrownBy(() -> GoogleApplicationDefault.credentials().testing(base, env::get, Clock.systemUTC()).token())
                    .hasMessageContaining("No Google Application Default Credentials").hasMessageContaining("cafeai login google");
        } finally {
            google.stop(0);
        }
    }

    @Test @DisplayName("gcloud ADC: found where Google's libraries look")
    void googleWhere() {
        Map<String, String> explicit = Map.of("GOOGLE_APPLICATION_CREDENTIALS", "/x/creds.json", "CLOUDSDK_CONFIG", "/y");
        assertThat(GoogleApplicationDefault.credentials().testing("http://127.0.0.1/t", explicit::get, Clock.systemUTC()).file())
                .isEqualTo(Path.of("/x/creds.json"));
        Map<String, String> config = Map.of("CLOUDSDK_CONFIG", "/y");
        assertThat(GoogleApplicationDefault.credentials().testing("http://127.0.0.1/t", config::get, Clock.systemUTC()).file())
                .isEqualTo(Path.of("/y", "application_default_credentials.json"));
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... s) {
        return new ArrayList<>(List.of(s));
    }
}
