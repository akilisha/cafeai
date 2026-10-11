package io.cafeai.identity;

import io.cafeai.core.ai.SignedCredentials;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.identity.IdentityRequiredException;
import io.cafeai.core.internal.Cli;
import io.cafeai.core.internal.CurrentRequest;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AWS credentials for a model on AWS (Claude in Amazon Bedrock): each request is signed with
 * Signature Version 4, so the secret key never leaves the app. The key comes from AWS STS in
 * exchange for a token from the organisation's own identity provider
 * ({@code AssumeRoleWithWebIdentity}): no long-lived AWS key anywhere.
 *
 * <pre>{@code
 *   // As the signed-in person: each gets temporary credentials of their own, named after them in CloudTrail
 *   app.ai(Anthropic.of("anthropic.claude-opus-5-5")
 *           .withBaseUrl("https://bedrock-mantle.us-east-1.api.aws/anthropic")
 *           .withCredentials(AwsCredentials.assumeRoleAsCaller("arn:aws:iam::123456789012:role/claude-users")
 *                   .region("us-east-1")));
 *
 *   // As the app
 *   .withCredentials(AwsCredentials.assumeRole("arn:aws:iam::123456789012:role/orders-api",
 *           IdentityToken.clientCredentials(issuer, "orders-api", secret)).region("us-east-1"))
 * }</pre>
 *
 * <p>On a developer's machine, {@link #fromCli()} signs with the AWS CLI's own sign-in
 * ({@code aws sso login}, or {@code cafeai login aws}), through the company's IAM Identity Center.
 *
 * <p>The role's trust policy must name the identity provider (an IAM OIDC provider for the
 * issuer, with the token's audience among its client ids). Temporary credentials are cached until
 * {@link #RENEW_BEFORE} before they expire; a person's never past their own token. Signs for the
 * service {@code bedrock-mantle} by default ({@link #service(String)}).
 */
public final class AwsCredentials implements SignedCredentials {

    /** Temporary credentials are renewed this long before they expire. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final int MAX_CACHED = 10_000;

    private enum Source { KEYS, APP, CALLER, CLI }

    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(60);
    /** How long long-lived keys from the AWS CLI are kept before asking it again. */
    private static final Duration CLI_RECHECK = Duration.ofMinutes(15);
    static final String INSTALL = "The AWS CLI (aws) isn't installed. Install version 2 from "
            + "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html, then sign in: `cafeai login aws`.";

    /** Temporary credentials and when they stop working. */
    private record Session(AwsSigV4.Key key, Instant expires) { }

    private final Source source;
    private final String roleArn;
    private final IdentityToken identityToken;
    private final AwsSigV4.Key fixed;
    private String region = System.getenv("AWS_REGION");
    private String service = "bedrock-mantle";
    private URI stsEndpoint;
    private Duration duration = Duration.ofHours(1);
    private Clock clock = Clock.systemUTC();
    private volatile Session appSession;
    private final Map<String, Session> callerSessions = new ConcurrentHashMap<>();
    private String cliProfile;
    private Supplier<Optional<Path>> locate = () -> Cli.find("aws", System::getenv);
    private Function<List<String>, Cli.Result> runner = command -> Cli.run(command, CLI_TIMEOUT);

    private AwsCredentials(Source source, String roleArn, IdentityToken identityToken, AwsSigV4.Key fixed) {
        this.source = source;
        this.roleArn = roleArn;
        this.identityToken = identityToken;
        this.fixed = fixed;
    }

    /**
     * Temporary credentials for {@code roleArn}, in exchange for the signed-in caller's own token:
     * each person's calls are signed with credentials of their own, and their subject names the
     * session, so AWS's records say who acted. A call with no verified caller is refused.
     */
    public static AwsCredentials assumeRoleAsCaller(String roleArn) {
        return new AwsCredentials(Source.CALLER, requireArn(roleArn), null, null);
    }

    /** Temporary credentials for {@code roleArn}, in exchange for the app's own token: calls are the app's. */
    public static AwsCredentials assumeRole(String roleArn, IdentityToken identityToken) {
        return new AwsCredentials(Source.APP, requireArn(roleArn), Objects.requireNonNull(identityToken, "identityToken"), null);
    }

    /**
     * The AWS CLI's own credentials, for the default profile: on a developer's machine, the
     * company's IAM Identity Center sign-in ({@code aws sso login}). Asked of
     * {@code aws configure export-credentials} and kept until shortly before they expire; the
     * region, unless given, is the profile's.
     */
    public static AwsCredentials fromCli() {
        return new AwsCredentials(Source.CLI, null, null, null);
    }

    /** {@link #fromCli()} for a named AWS CLI profile. */
    public static AwsCredentials fromCli(String profile) {
        AwsCredentials credentials = fromCli();
        credentials.cliProfile = Objects.requireNonNull(profile, "profile");
        return credentials;
    }

    AwsCredentials cli(Supplier<Optional<Path>> locate, Function<List<String>, Cli.Result> runner) {
        this.locate = locate;
        this.runner = runner;
        return this;
    }

    /** Fixed keys, for development; prefer {@link #assumeRole} or {@link #assumeRoleAsCaller} anywhere else. */
    public static AwsCredentials keys(String accessKeyId, String secretAccessKey, String sessionToken) {
        return new AwsCredentials(Source.KEYS, null, null, new AwsSigV4.Key(
                Objects.requireNonNull(accessKeyId, "accessKeyId"), Objects.requireNonNull(secretAccessKey, "secretAccessKey"),
                sessionToken));
    }

    /** The AWS region (default {@code $AWS_REGION}). */
    public AwsCredentials region(String region) {
        this.region = Objects.requireNonNull(region, "region");
        return this;
    }

    /** The service signed for (default {@code bedrock-mantle}, Claude in Amazon Bedrock's Messages API). */
    public AwsCredentials service(String service) {
        this.service = Objects.requireNonNull(service, "service");
        return this;
    }

    /** How long temporary credentials are asked for (default one hour; the role may allow less). */
    public AwsCredentials duration(Duration duration) {
        this.duration = Objects.requireNonNull(duration, "duration");
        return this;
    }

    /** Another STS address (default {@code https://sts.<region>.amazonaws.com}). */
    public AwsCredentials stsEndpoint(String endpoint) {
        this.stsEndpoint = URI.create(Objects.requireNonNull(endpoint, "endpoint"));
        return this;
    }

    AwsCredentials clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public boolean perCaller() {
        return source == Source.CALLER;
    }

    @Override
    public Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body) {
        if (region == null && source == Source.CLI) region = cliRegion();
        if (region == null) throw new IllegalStateException("Name the AWS region: AwsCredentials...region(\"us-east-1\")");
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", headers.get("host"));
        return AwsSigV4.sign(method, uri.getRawPath(), uri.getRawQuery(), signed, body, key(), region, service,
                clock.instant(), true, false).headers();
    }

    /** The key to sign this call with. */
    private AwsSigV4.Key key() {
        Instant now = clock.instant();
        return switch (source) {
            case KEYS -> fixed;
            case APP -> {
                Session s = appSession;
                if (s != null && now.isBefore(s.expires().minus(RENEW_BEFORE))) yield s.key();
                synchronized (this) {
                    s = appSession;
                    if (s == null || !now.isBefore(s.expires().minus(RENEW_BEFORE))) {
                        s = assumeRole(identityToken.get(), "app-" + now.getEpochSecond(), now);
                        appSession = s;
                    }
                    yield s.key();
                }
            }
            case CALLER -> callerKey(now);
            case CLI -> {
                synchronized (this) {
                    Session s = appSession;
                    if (s == null || !now.isBefore(s.expires().minus(RENEW_BEFORE))) {
                        s = cliSession(now);
                        appSession = s;
                    }
                    yield s.key();
                }
            }
        };
    }

    /** {@code aws configure export-credentials}: the credential_process JSON the AWS SDKs read. */
    private Session cliSession(Instant now) {
        var command = cliCommand("configure", "export-credentials", "--format", "process");
        Cli.Result result = runner.apply(command);
        if (!result.ok()) {
            String said = firstLine(result.err());
            String lower = result.err().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("sso") || lower.contains("expired") || lower.contains("unable to locate credentials")) {
                throw new IdentityException("Not signed in to AWS, or the sign-in has expired. Sign in: `cafeai login aws"
                        + (cliProfile == null ? "" : " --profile " + cliProfile) + "` (aws sso login). AWS CLI said: " + said);
            }
            throw new IdentityException("The AWS CLI couldn't export credentials: " + said);
        }
        JsonObject json;
        try {
            var value = JsonParser.create(result.out()).readJsonValue();
            json = value.type() == JsonValueType.OBJECT ? value.asObject() : null;
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null) throw new IdentityException("The AWS CLI's credentials weren't JSON: " + firstLine(result.out()));
        String id = json.stringValue("AccessKeyId").orElseThrow(() -> new IdentityException("The AWS CLI gave no AccessKeyId"));
        String secret = json.stringValue("SecretAccessKey").orElseThrow(() -> new IdentityException("The AWS CLI gave no SecretAccessKey"));
        String sessionToken = json.stringValue("SessionToken").orElse(null);
        // Long-lived keys have no Expiration: ask again now and then, in case they are rotated.
        Instant expires = json.stringValue("Expiration").map(t -> OffsetDateTime.parse(t).toInstant())
                .orElse(now.plus(CLI_RECHECK).plus(RENEW_BEFORE));
        return new Session(new AwsSigV4.Key(id, secret, sessionToken), expires);
    }

    private String cliRegion() {
        Cli.Result result = runner.apply(cliCommand("configure", "get", "region"));
        String region = result.ok() ? result.out().strip() : "";
        return region.isEmpty() ? null : region;
    }

    private List<String> cliCommand(String... args) {
        Path aws = locate.get().orElseThrow(() -> new IdentityException(INSTALL));
        var command = new ArrayList<String>();
        command.add(aws.toString());
        command.addAll(List.of(args));
        if (cliProfile != null) command.addAll(List.of("--profile", cliProfile));
        return command;
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    private AwsSigV4.Key callerKey(Instant now) {
        Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                "A model call signed as the caller (AWS role " + roleArn + ") was made with no verified caller. "
                + "Require sign-in on this route, or carry the request to this thread with RequestScope."));
        String token = CurrentRequest.get().map(r -> r.attribute(BearerAuth.ACCESS_TOKEN))
                .filter(String.class::isInstance).map(String.class::cast)
                .orElseThrow(() -> new IdentityRequiredException("The caller " + caller + " has no access token to present to AWS"));
        String cacheKey = HexFormat.of().formatHex(AwsSigV4.sha256(token.getBytes(StandardCharsets.UTF_8)));
        Session s = callerSessions.get(cacheKey);
        if (s != null && now.isBefore(s.expires().minus(RENEW_BEFORE))) return s.key();
        Session issued = assumeRole(token, sessionName(caller.subject()), now);
        // Never used past the caller's own token: STS vouched for that token only.
        Session kept = issued.expires().isAfter(caller.expiresAt()) ? new Session(issued.key(), caller.expiresAt()) : issued;
        if (callerSessions.size() >= MAX_CACHED) callerSessions.values().removeIf(x -> !now.isBefore(x.expires()));
        if (callerSessions.size() < MAX_CACHED) callerSessions.put(cacheKey, kept);
        return kept.key();
    }

    /** {@code AssumeRoleWithWebIdentity}: no AWS credentials needed to ask, only the token. */
    private Session assumeRole(String webIdentityToken, String sessionName, Instant now) {
        URI endpoint = stsEndpoint != null ? stsEndpoint : URI.create("https://sts." + region + ".amazonaws.com/");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("Action", "AssumeRoleWithWebIdentity");
        form.put("Version", "2011-06-15");
        form.put("RoleArn", roleArn);
        form.put("RoleSessionName", sessionName);
        form.put("WebIdentityToken", webIdentityToken);
        form.put("DurationSeconds", Long.toString(duration.toSeconds()));
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while asking AWS STS for credentials", e);
        } catch (Exception e) {
            throw new IdentityException("Could not reach AWS STS at " + endpoint + ": " + e.getMessage(), e);
        }
        Document xml = parse(response.body());
        if (response.statusCode() != 200 || xml == null) {
            String code = xml == null ? "HTTP " + response.statusCode() : text(xml, "Code");
            if ("ExpiredTokenException".equals(code)) {
                throw new IdentityRequiredException("AWS STS refused an expired token: the caller must sign in again");
            }
            throw new IdentityException("AWS STS refused AssumeRoleWithWebIdentity for " + roleArn + ": " + code
                    + (xml == null ? "" : " (" + text(xml, "Message") + ")"));
        }
        String accessKeyId = text(xml, "AccessKeyId");
        String secret = text(xml, "SecretAccessKey");
        String sessionToken = text(xml, "SessionToken");
        String expiration = text(xml, "Expiration");
        if (accessKeyId == null || secret == null || sessionToken == null) {
            throw new IdentityException("AWS STS answered without credentials");
        }
        Instant expires = expiration == null ? now.plus(duration) : Instant.parse(expiration);
        return new Session(new AwsSigV4.Key(accessKeyId, secret, sessionToken), expires);
    }

    /** STS's XML, parsed with no external entities or doctypes. */
    private static Document parse(byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Document xml, String element) {
        var nodes = xml.getElementsByTagName(element);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    /** The subject, as STS takes a session name: {@code [\w+=,.@-]}, 2 to 64 characters. */
    static String sessionName(String subject) {
        String name = subject.replaceAll("[^\\w+=,.@-]", "-");
        if (name.length() > 64) name = name.substring(0, 64);
        return name.length() < 2 ? (name + "--").substring(0, 2) : name;
    }

    private static String requireArn(String roleArn) {
        Objects.requireNonNull(roleArn, "roleArn");
        if (!roleArn.startsWith("arn:aws") || !roleArn.contains(":role/")) {
            throw new IllegalArgumentException("Not an IAM role ARN: " + roleArn);
        }
        return roleArn;
    }

    @Override
    public String toString() {
        return "AwsCredentials(" + source + (roleArn == null ? "" : ", " + roleArn) + ")";
    }
}
