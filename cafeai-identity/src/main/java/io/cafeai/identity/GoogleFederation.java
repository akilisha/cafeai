package io.cafeai.identity;

import io.cafeai.core.ai.Credentials;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.identity.IdentityRequiredException;
import io.cafeai.core.internal.CurrentRequest;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Cloud access tokens with no service-account key: Google's Security Token Service
 * exchanges a token from the organisation's own identity provider (RFC 8693) for a Google token,
 * optionally then used to act as a service account. For Claude on Vertex AI
 * ({@code Anthropic.onVertex(...)}).
 *
 * <pre>{@code
 *   // As the app: a workload identity pool provider, acting as a service account
 *   app.ai(Anthropic.onVertex("claude-opus-5-5", "my-project", "global")
 *           .withCredentials(GoogleFederation.workload(
 *                   "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/apps/providers/acme",
 *                   IdentityToken.clientCredentials(issuer, "orders-api", secret))
 *               .serviceAccount("claude-caller@my-project.iam.gserviceaccount.com")));
 * }</pre>
 *
 * <p>{@link #workforce(String)} exchanges the signed-in caller's own token at a workforce pool
 * provider instead, per person. Google must be able to fetch the issuer's discovery document, so
 * the issuer must be reachable from the internet. Tokens are cached until
 * {@link #RENEW_BEFORE} before they expire; a person's never past their own token.
 */
public final class GoogleFederation implements Credentials {

    /** Renewed this long before it expires. */
    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final int MAX_CACHED = 10_000;

    private record Token(String value, Instant expires) { }

    private final String provider;
    private final IdentityToken identityToken;   // null: the caller's own token
    private String serviceAccount;
    private List<String> scopes = List.of(CLOUD_PLATFORM);
    private URI sts = URI.create("https://sts.googleapis.com/v1/token");
    private URI iamCredentials = URI.create("https://iamcredentials.googleapis.com");
    private Clock clock = Clock.systemUTC();
    private volatile Token appToken;
    private final Map<String, Token> callerTokens = new ConcurrentHashMap<>();

    private GoogleFederation(String provider, IdentityToken identityToken) {
        Objects.requireNonNull(provider, "provider");
        if (!provider.startsWith("//iam.googleapis.com/")) {
            throw new IllegalArgumentException("Name the pool provider in full, e.g. //iam.googleapis.com/projects/123/"
                    + "locations/global/workloadIdentityPools/<pool>/providers/<provider>: " + provider);
        }
        this.provider = provider;
        this.identityToken = identityToken;
    }

    /** As the app: the app's token, exchanged at a workload identity pool provider. */
    public static GoogleFederation workload(String provider, IdentityToken identityToken) {
        return new GoogleFederation(provider, Objects.requireNonNull(identityToken, "identityToken"));
    }

    /**
     * As the signed-in caller: their own token, exchanged at a workforce pool provider, per person
     * (its audience must be the provider's client id). A call with no verified caller is refused.
     */
    public static GoogleFederation workforce(String provider) {
        return new GoogleFederation(provider, null);
    }

    /** After the exchange, act as this service account ({@code generateAccessToken}). */
    public GoogleFederation serviceAccount(String email) {
        this.serviceAccount = Objects.requireNonNull(email, "email");
        return this;
    }

    /** The OAuth scopes asked for (default {@code cloud-platform}). */
    public GoogleFederation scope(String... scopes) {
        if (scopes.length == 0) throw new IllegalArgumentException("Name at least one scope");
        this.scopes = List.of(scopes);
        return this;
    }

    /** Other addresses for Google's STS and IAM Credentials APIs (for tests). */
    GoogleFederation endpoints(String sts, String iamCredentials) {
        this.sts = URI.create(sts);
        this.iamCredentials = URI.create(iamCredentials);
        return this;
    }

    GoogleFederation clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public boolean perCaller() {
        return identityToken == null;
    }

    @Override
    public String token() {
        Instant now = clock.instant();
        if (identityToken != null) {
            Token t = appToken;
            if (t != null && now.isBefore(t.expires().minus(RENEW_BEFORE))) return t.value();
            synchronized (this) {
                t = appToken;
                if (t == null || !now.isBefore(t.expires().minus(RENEW_BEFORE))) {
                    t = obtain(identityToken.get(), now);
                    appToken = t;
                }
                return t.value();
            }
        }
        Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                "A model call as the caller (Google workforce pool) was made with no verified caller. "
                + "Require sign-in on this route, or carry the request to this thread with RequestScope."));
        String subject = CurrentRequest.get().map(r -> r.attribute(BearerAuth.ACCESS_TOKEN))
                .filter(String.class::isInstance).map(String.class::cast)
                .orElseThrow(() -> new IdentityRequiredException("The caller " + caller + " has no token to present to Google"));
        String key = HexFormat.of().formatHex(AwsSigV4.sha256(subject.getBytes(StandardCharsets.UTF_8)));
        Token cached = callerTokens.get(key);
        if (cached != null && now.isBefore(cached.expires().minus(RENEW_BEFORE))) return cached.value();
        Token issued = obtain(subject, now);
        Token kept = issued.expires().isAfter(caller.expiresAt()) ? new Token(issued.value(), caller.expiresAt()) : issued;
        if (callerTokens.size() >= MAX_CACHED) callerTokens.values().removeIf(x -> !now.isBefore(x.expires()));
        if (callerTokens.size() < MAX_CACHED) callerTokens.put(key, kept);
        return kept.value();
    }

    /** STS, then, if asked, impersonation. */
    private Token obtain(String subjectToken, Instant now) {
        var exchange = JsonObject.builder()
                .set("grantType", "urn:ietf:params:oauth:grant-type:token-exchange")
                .set("audience", provider)
                .set("scope", String.join(" ", scopes))
                .set("requestedTokenType", "urn:ietf:params:oauth:token-type:access_token")
                .set("subjectToken", subjectToken)
                .set("subjectTokenType", "urn:ietf:params:oauth:token-type:jwt")
                .build();
        JsonObject federated = post(sts, null, exchange.toString(), "Google STS");
        String token = federated.stringValue("access_token").orElseThrow(() ->
                new IdentityException("Google STS answered without an access_token"));
        Instant expires = now.plusSeconds(federated.longValue("expires_in").orElse(3600L));
        if (serviceAccount == null) return new Token(token, expires);

        var arr = JsonArray.create(scopes.stream().map(s -> (JsonValue) io.helidon.json.JsonString.create(s)).toList());
        var impersonate = JsonObject.builder().set("scope", arr).set("lifetime", "3600s").build();
        URI url = iamCredentials.resolve("/v1/projects/-/serviceAccounts/"
                + URLEncoder.encode(serviceAccount, StandardCharsets.UTF_8) + ":generateAccessToken");
        JsonObject sa = post(url, token, impersonate.toString(), "Google IAM Credentials");
        String saToken = sa.stringValue("accessToken").orElseThrow(() ->
                new IdentityException("generateAccessToken answered without an accessToken"));
        Instant saExpires = sa.stringValue("expireTime").map(t -> OffsetDateTime.parse(t).toInstant()).orElse(now.plusSeconds(3600));
        return new Token(saToken, saExpires);
    }

    private static JsonObject post(URI url, String bearer, String body, String what) {
        var request = HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while asking " + what, e);
        } catch (Exception e) {
            throw new IdentityException("Could not reach " + what + " at " + url + ": " + e.getMessage(), e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() != 200 || json == null) {
            String error = json == null ? "HTTP " + response.statusCode()
                    : json.stringValue("error_description").or(() -> json.stringValue("error"))
                            .orElse("HTTP " + response.statusCode());
            throw new IdentityException(what + " refused: " + error);
        }
        return json;
    }

    private static JsonObject parse(String body) {
        try {
            var value = JsonParser.create(body).readJsonValue();
            return value.type() == JsonValueType.OBJECT ? value.asObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "GoogleFederation(" + provider + (serviceAccount == null ? "" : ", as " + serviceAccount) + ")";
    }
}
