package io.cafeai.identity;

import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AwsCredentials: Claude in Amazon Bedrock, every request signed (SigV4), keys from STS")
class AwsCredentialsTest {

    private static final String AUDIENCE = "orders-api";
    private static final String SECRET = "s3cret";
    private static final String ROLE = "arn:aws:iam::123456789012:role/claude-users";
    private static final String REGION = "us-east-1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer company;
    private FakeAnthropicServer bedrock;
    private FakeSts sts;
    private CafeAI app;

    /** AWS STS's AssumeRoleWithWebIdentity, as it answers: temporary credentials in XML, or an error. */
    static final class FakeSts implements AutoCloseable {
        record Assumed(String roleArn, String sessionName, String tokenSubject, String accessKeyId) { }

        final HttpServer server;
        final List<Assumed> assumed = new CopyOnWriteArrayList<>();
        final Map<String, String> secrets = new ConcurrentHashMap<>();   // access key id -> secret
        volatile boolean refuse;

        FakeSts() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                Map<String, String> form = new LinkedHashMap<>();
                for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                    int eq = pair.indexOf('=');
                    form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
                String xml;
                int status = 200;
                if (refuse || !"AssumeRoleWithWebIdentity".equals(form.get("Action"))) {
                    status = 403;
                    xml = "<ErrorResponse><Error><Type>Sender</Type><Code>AccessDenied</Code>"
                            + "<Message>Not authorized to perform sts:AssumeRoleWithWebIdentity</Message></Error></ErrorResponse>";
                } else {
                    String n = Integer.toString(assumed.size() + 1);
                    String keyId = "ASIATEST" + n;
                    secrets.put(keyId, "secret-" + n);
                    String subject = new String(Base64.getUrlDecoder().decode(form.get("WebIdentityToken").split("\\.")[1]),
                            StandardCharsets.UTF_8).replaceAll("(?s).*\"sub\":\"([^\"]+)\".*", "$1");
                    assumed.add(new Assumed(form.get("RoleArn"), form.get("RoleSessionName"), subject, keyId));
                    xml = "<AssumeRoleWithWebIdentityResponse><AssumeRoleWithWebIdentityResult><Credentials>"
                            + "<AccessKeyId>" + keyId + "</AccessKeyId><SecretAccessKey>secret-" + n + "</SecretAccessKey>"
                            + "<SessionToken>session-" + n + "</SessionToken>"
                            + "<Expiration>" + Instant.now().plusSeconds(3600) + "</Expiration>"
                            + "</Credentials></AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>";
                }
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/xml");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        @Override public void close() { server.stop(0); }
    }

    @BeforeAll
    static void startIssuer() {
        company = FakeIssuer.start().client(AUDIENCE, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        company.close();
    }

    @BeforeEach
    void start() throws Exception {
        bedrock = new FakeAnthropicServer();
        sts = new FakeSts();
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        bedrock.close();
        sts.close();
    }

    private void serve(AwsCredentials aws) throws Exception {
        app = CafeAI.create();
        app.ai(Anthropic.of("anthropic.claude-opus-5-5").withBaseUrl(bedrock.baseUrl("/anthropic")).withCredentials(aws));
        app.filter(Auth.bearer(company.issuer(), AUDIENCE).optional());
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("hi")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> get(String path, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (subject != null) request.header("Authorization", "Bearer " + company.token().subject(subject).audience(AUDIENCE).sign());
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * What AWS does on receipt: recompute the signature over the request as it arrived, with the
     * secret STS issued for the key the request names, and compare.
     */
    private void assertSignedByAws(FakeAnthropicServer.Call call) {
        String authorization = call.authorization().getFirst();
        assertThat(authorization).startsWith("AWS4-HMAC-SHA256 Credential=");
        String keyId = authorization.replaceAll(".*Credential=([^/]+)/.*", "$1");
        assertThat(authorization).contains("/" + REGION + "/bedrock-mantle/aws4_request")
                .contains("SignedHeaders=host;x-amz-date;x-amz-security-token");
        Instant at = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                .parse(call.amzDate(), Instant::from);
        var expected = AwsSigV4.sign("POST", call.path(), null, Map.of("host", call.host()),
                call.body().getBytes(StandardCharsets.UTF_8),
                new AwsSigV4.Key(keyId, sts.secrets.get(keyId), call.securityToken()), REGION, "bedrock-mantle", at, true, false);
        assertThat(authorization).endsWith("Signature=" + expected.signature());
        assertThat(call.xApiKey()).as("no placeholder key goes with a signed request").isEmpty();
    }

    @Test @DisplayName("as the caller: each person's calls are signed with their own temporary credentials, named after them")
    void perPerson() throws Exception {
        serve(AwsCredentials.assumeRoleAsCaller(ROLE).region(REGION).stsEndpoint(sts.url()));
        assertThat(get("/ask", "alice").body()).isEqualTo("ok");
        assertThat(get("/ask", "bob").body()).isEqualTo("ok");

        assertThat(sts.assumed).extracting(FakeSts.Assumed::sessionName).containsExactly("alice", "bob");
        assertThat(sts.assumed).extracting(FakeSts.Assumed::roleArn).containsOnly(ROLE);
        assertThat(bedrock.calls).hasSize(2).allSatisfy(this::assertSignedByAws);
        assertThat(bedrock.calls.get(0).authorization().getFirst()).contains("Credential=ASIATEST1/");
        assertThat(bedrock.calls.get(1).authorization().getFirst()).contains("Credential=ASIATEST2/");

        // The same person's token again: their credentials are reused.
        String alice = company.token().subject("alice").audience(AUDIENCE).sign();
        for (int i = 0; i < 2; i++) {
            HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ask"))
                    .header("Authorization", "Bearer " + alice).build(), HttpResponse.BodyHandlers.ofString());
        }
        assertThat(sts.assumed).hasSize(3);
    }

    @Test @DisplayName("a streamed call is signed too, though it is sent from the stream's thread")
    void streamed() throws Exception {
        serve(AwsCredentials.assumeRoleAsCaller(ROLE).region(REGION).stsEndpoint(sts.url()));
        assertThat(get("/sse", "carol").body()).contains("ok");
        var call = bedrock.calls.getFirst();
        assertThat(call.streamed()).isTrue();
        assertSignedByAws(call);
        assertThat(sts.assumed.getFirst().sessionName()).isEqualTo("carol");
    }

    @Test @DisplayName("as the app: its token from the company's issuer is exchanged once, and every call is signed")
    void asTheApp() throws Exception {
        serve(AwsCredentials.assumeRole(ROLE, IdentityToken.clientCredentials(company.issuer(), AUDIENCE, SECRET))
                .region(REGION).stsEndpoint(sts.url()));
        for (int i = 0; i < 3; i++) assertThat(get("/ask", null).body()).isEqualTo("ok");

        assertThat(sts.assumed).singleElement().satisfies(a -> assertThat(a.tokenSubject()).isEqualTo(AUDIENCE));
        assertThat(bedrock.calls).hasSize(3).allSatisfy(this::assertSignedByAws);
    }

    @Test @DisplayName("as the caller, with no caller: 401, and neither STS nor the model is reached")
    void noCaller() throws Exception {
        serve(AwsCredentials.assumeRoleAsCaller(ROLE).region(REGION).stsEndpoint(sts.url()));
        assertThat(get("/ask", null).statusCode()).isEqualTo(401);
        assertThat(sts.assumed).isEmpty();
        assertThat(bedrock.calls).isEmpty();
    }

    @Test @DisplayName("STS refusing the role fails the call before the model is reached")
    void stsRefuses() throws Exception {
        sts.refuse = true;
        serve(AwsCredentials.assumeRoleAsCaller(ROLE).region(REGION).stsEndpoint(sts.url()));
        assertThat(get("/ask", "dave").statusCode()).isEqualTo(500);
        assertThat(bedrock.calls).isEmpty();
    }

    @Test @DisplayName("a subject becomes a session name STS accepts")
    void sessionNames() {
        assertThat(AwsCredentials.sessionName("2f9c1e8a-0b7d-4c55-9f1e-3a1b2c3d4e5f")).isEqualTo("2f9c1e8a-0b7d-4c55-9f1e-3a1b2c3d4e5f");
        assertThat(AwsCredentials.sessionName("user name/with spaces")).isEqualTo("user-name-with-spaces");
        assertThat(AwsCredentials.sessionName("x".repeat(80))).hasSize(64);
        assertThat(AwsCredentials.sessionName("a")).hasSize(2);
    }
}
