package io.cafeai.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.internal.AwsSigV4;
import io.cafeai.core.internal.Cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The developer's own AWS sign-in, from the AWS CLI: on a work machine, the company's IAM Identity
 * Center ({@code aws sso login}, or {@code cafeai login aws --profile work}). Each request is
 * signed with Signature Version 4; the credentials are asked of
 * {@code aws configure export-credentials} and kept until shortly before they expire.
 *
 * <p>A provider pointed at Claude in Amazon Bedrock with no credentials of its own uses
 * {@link #active()} by itself. To name a profile:
 *
 * <pre>{@code
 *   app.ai(Anthropic.of("anthropic.claude-opus-5-5")
 *           .withBaseUrl("https://bedrock-mantle.us-east-1.api.aws/anthropic")
 *           .withCredentials(AwsCliCredentials.profile("work")));
 * }</pre>
 *
 * <p>The region, unless given, is found as the AWS CLI finds it: {@code AWS_REGION},
 * {@code AWS_DEFAULT_REGION}, then the profile's. Signs for {@code bedrock-mantle} by default.
 * For servers, use {@code cafeai-identity}'s {@code AwsCredentials} (a role, in exchange for the
 * app's or the caller's own token).
 */
public final class AwsCliCredentials implements SignedCredentials {

    /** Credentials are renewed this long before they expire. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    /** How long long-lived keys from the AWS CLI are kept before asking it again. */
    private static final Duration RECHECK = Duration.ofMinutes(15);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String INSTALL = "The AWS CLI (aws) isn't installed. Install version 2 from "
            + "https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html, then sign in: `cafeai login aws`.";

    private record Session(AwsSigV4.Key key, Instant expires) { }

    private final String profile;
    private String region;
    private String service = "bedrock-mantle";
    private Supplier<Optional<Path>> locate = () -> Cli.find("aws", System::getenv);
    private Function<List<String>, Cli.Result> runner = command -> Cli.run(command, TIMEOUT);
    private UnaryOperator<String> environment = System::getenv;
    private Clock clock = Clock.systemUTC();
    private Session session;

    private AwsCliCredentials(String profile) {
        this.profile = profile;
    }

    /** The profile the AWS CLI uses by itself: {@code AWS_PROFILE}, else {@code default}. */
    public static AwsCliCredentials active() {
        return new AwsCliCredentials(null);
    }

    /** A named AWS CLI profile, as in {@code cafeai login aws --profile work}. */
    public static AwsCliCredentials profile(String name) {
        return new AwsCliCredentials(Objects.requireNonNull(name, "name"));
    }

    /** The AWS region (default: {@code AWS_REGION}, {@code AWS_DEFAULT_REGION}, then the profile's). */
    public AwsCliCredentials region(String region) {
        this.region = Objects.requireNonNull(region, "region");
        return this;
    }

    /** The service signed for (default {@code bedrock-mantle}, Claude in Amazon Bedrock's Messages API). */
    public AwsCliCredentials service(String service) {
        this.service = Objects.requireNonNull(service, "service");
        return this;
    }

    AwsCliCredentials cli(Supplier<Optional<Path>> locate, Function<List<String>, Cli.Result> runner) {
        this.locate = locate;
        this.runner = runner;
        return this;
    }

    AwsCliCredentials environment(UnaryOperator<String> environment) {
        this.environment = environment;
        return this;
    }

    AwsCliCredentials clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public Map<String, String> sign(String method, URI uri, Map<String, String> headers, byte[] body) {
        String signingRegion = region();
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", headers.get("host"));
        return AwsSigV4.sign(method, uri.getRawPath(), uri.getRawQuery(), signed, body, key(), signingRegion, service,
                clock.instant(), true, false).headers();
    }

    private synchronized String region() {
        if (region != null) return region;
        for (String variable : List.of("AWS_REGION", "AWS_DEFAULT_REGION")) {
            String value = environment.apply(variable);
            if (value != null && !value.isBlank()) return region = value;
        }
        Cli.Result result = runner.apply(command("configure", "get", "region"));
        String fromProfile = result.ok() ? result.out().strip() : "";
        if (fromProfile.isEmpty()) {
            String name = profileName();
            throw new IllegalStateException("The AWS CLI profile '" + name + "' names no region. Set one: `aws configure"
                    + " set region us-east-1 --profile " + name + "`, or AWS_REGION, or AwsCliCredentials...region(\"us-east-1\")");
        }
        return region = fromProfile;
    }

    private synchronized AwsSigV4.Key key() {
        Instant now = clock.instant();
        if (session == null || !now.isBefore(session.expires().minus(RENEW_BEFORE))) session = export(now);
        return session.key();
    }

    /** {@code aws configure export-credentials}: the credential_process JSON the AWS SDKs read. */
    private Session export(Instant now) {
        Cli.Result result = runner.apply(command("configure", "export-credentials", "--format", "process"));
        if (!result.ok()) {
            String said = firstLine(result.err());
            String lower = result.err().toLowerCase(Locale.ROOT);
            if (lower.contains("sso") || lower.contains("expired") || lower.contains("unable to locate credentials")) {
                throw new IllegalStateException("Not signed in to AWS, or the sign-in has expired. Sign in: `cafeai login aws"
                        + (profile == null ? "" : " --profile " + profile) + "` (aws sso login). AWS CLI said: " + said);
            }
            throw new IllegalStateException("The AWS CLI couldn't export credentials: " + said);
        }
        JsonNode json;
        try {
            json = JSON.readTree(result.out());
        } catch (IOException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("The AWS CLI's credentials weren't JSON: " + firstLine(result.out()));
        }
        String id = text(json, "AccessKeyId");
        String secret = text(json, "SecretAccessKey");
        if (id == null) throw new IllegalStateException("The AWS CLI gave no AccessKeyId");
        if (secret == null) throw new IllegalStateException("The AWS CLI gave no SecretAccessKey");
        // Long-lived keys have no Expiration: ask again now and then, in case they are rotated.
        String expiration = text(json, "Expiration");
        Instant expires = expiration != null ? OffsetDateTime.parse(expiration).toInstant()
                : now.plus(RECHECK).plus(RENEW_BEFORE);
        return new Session(new AwsSigV4.Key(id, secret, text(json, "SessionToken")), expires);
    }

    private List<String> command(String... args) {
        Path aws = locate.get().orElseThrow(() -> new IllegalStateException(INSTALL));
        var command = new ArrayList<String>();
        command.add(aws.toString());
        command.addAll(List.of(args));
        if (profile != null) command.addAll(List.of("--profile", profile));
        return command;
    }

    private String profileName() {
        if (profile != null) return profile;
        String fromEnv = environment.apply("AWS_PROFILE");
        return fromEnv == null || fromEnv.isBlank() ? "default" : fromEnv;
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    @Override
    public String toString() {
        return "AwsCliCredentials(" + profileName() + ")";
    }
}
