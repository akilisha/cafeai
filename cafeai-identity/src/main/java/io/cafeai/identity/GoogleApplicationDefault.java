package io.cafeai.identity;

import io.cafeai.core.ai.Credentials;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonString;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Google Cloud tokens from the developer's own Application Default Credentials: the file
 * {@code gcloud auth application-default login} (or {@code cafeai login google}) writes, through
 * the company's Google sign-in. No service-account key. For Claude on Vertex AI on a developer's
 * machine:
 *
 * <pre>{@code
 *   app.ai(Anthropic.onVertex("claude-opus-5-5", "my-project", "global")
 *           .withCredentials(GoogleApplicationDefault.credentials()));
 * }</pre>
 *
 * <p>The file is found where Google's libraries look: {@code $GOOGLE_APPLICATION_CREDENTIALS},
 * else {@code $CLOUDSDK_CONFIG/application_default_credentials.json}, else
 * {@code %APPDATA%\gcloud\...} on Windows or {@code ~/.config/gcloud/...} elsewhere. Its
 * {@code authorized_user} refresh token is redeemed at Google's token endpoint, in-process, as
 * Google's libraries do; an {@code impersonated_service_account} file (from {@code --impersonate-
 * service-account}) then acts as that service account. Key files ({@code service_account}) are
 * refused: they are the long-lived keys companies forbid. Tokens are kept until
 * {@link #RENEW_BEFORE} before they expire.
 */
public final class GoogleApplicationDefault implements Credentials {

    static final Duration RENEW_BEFORE = Duration.ofMinutes(5);
    static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";
    private static final String FILE = "application_default_credentials.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private record Token(String value, Instant expires) { }

    private List<String> scopes = List.of(CLOUD_PLATFORM);
    private URI tokenEndpoint = URI.create("https://oauth2.googleapis.com/token");
    private UnaryOperator<String> env = System::getenv;
    private Clock clock = Clock.systemUTC();
    private boolean anyHost;   // tests only: a local fake stands in for googleapis.com
    private Token cached;

    private GoogleApplicationDefault() {}

    /** The Application Default Credentials, found as Google's libraries find them. */
    public static GoogleApplicationDefault credentials() {
        return new GoogleApplicationDefault();
    }

    /** The OAuth scopes asked for (default {@code cloud-platform}). */
    public GoogleApplicationDefault scope(String... scopes) {
        if (scopes.length == 0) throw new IllegalArgumentException("Name at least one scope");
        this.scopes = List.of(scopes);
        return this;
    }

    GoogleApplicationDefault testing(String tokenEndpoint, UnaryOperator<String> env, Clock clock) {
        this.tokenEndpoint = URI.create(tokenEndpoint);
        this.env = env;
        this.clock = clock;
        this.anyHost = true;
        return this;
    }

    /** Where the credentials file is looked for. */
    Path file() {
        String explicit = env.apply("GOOGLE_APPLICATION_CREDENTIALS");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        String config = env.apply("CLOUDSDK_CONFIG");
        if (config != null && !config.isBlank()) return Path.of(config, FILE);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            String appData = env.apply("APPDATA");
            if (appData != null && !appData.isBlank()) return Path.of(appData, "gcloud", FILE);
        }
        return Path.of(System.getProperty("user.home"), ".config", "gcloud", FILE);
    }

    @Override
    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expires().minus(RENEW_BEFORE))) return cached.value();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            throw new IdentityException("No Google Application Default Credentials at " + file
                    + ". Sign in: `cafeai login google` (gcloud auth application-default login).");
        }
        JsonObject adc = read(file);
        String type = adc.stringValue("type", "");
        cached = switch (type) {
            case "authorized_user" -> refresh(adc, now);
            case "impersonated_service_account" -> impersonate(adc, now);
            case "service_account" -> throw new IdentityException("The Google credentials at " + file
                    + " are a service-account key, a long-lived key. Use your own sign-in instead"
                    + " (`cafeai login google`), or workload federation (GoogleFederation).");
            case "external_account" -> throw new IdentityException("The Google credentials at " + file
                    + " are a workload identity federation configuration; use GoogleFederation for that.");
            default -> throw new IdentityException("The Google credentials at " + file + " have a type CafeAI doesn't"
                    + " handle: '" + type + "'");
        };
        return cached.value();
    }

    /** {@code authorized_user}: the refresh token redeemed at Google's token endpoint. */
    private Token refresh(JsonObject user, Instant now) {
        String form = "grant_type=refresh_token"
                + "&client_id=" + encode(required(user, "client_id"))
                + "&client_secret=" + encode(required(user, "client_secret"))
                + "&refresh_token=" + encode(required(user, "refresh_token"));
        JsonObject answer = post(tokenEndpoint, "application/x-www-form-urlencoded", form, null);
        String token = answer.stringValue("access_token").orElseThrow(() ->
                new IdentityException("Google's token endpoint answered without an access_token"));
        return new Token(token, now.plusSeconds(answer.longValue("expires_in", 3600)));
    }

    /** {@code impersonated_service_account}: the person's token, then generateAccessToken for the account. */
    private Token impersonate(JsonObject adc, Instant now) {
        JsonObject source = adc.objectValue("source_credentials").orElseThrow(() ->
                new IdentityException("The impersonation credentials have no source_credentials"));
        if (!"authorized_user".equals(source.stringValue("type", ""))) {
            throw new IdentityException("Impersonation from '" + source.stringValue("type", "") + "' credentials isn't handled");
        }
        Token person = refresh(source, now);
        URI url = URI.create(required(adc, "service_account_impersonation_url"));
        if (!anyHost && (!"https".equals(url.getScheme()) || !url.getHost().endsWith(".googleapis.com"))) {
            throw new IdentityException("Refusing to send a Google token to " + url);
        }
        var body = JsonObject.builder()
                .set("scope", JsonArray.create(scopes.stream().map(s -> (JsonValue) JsonString.create(s)).toList()))
                .set("lifetime", "3600s").build();
        JsonObject answer = post(url, "application/json", body.toString(), person.value());
        String token = answer.stringValue("accessToken").orElseThrow(() ->
                new IdentityException("generateAccessToken answered without an accessToken"));
        Instant expires = answer.stringValue("expireTime").map(t -> OffsetDateTime.parse(t).toInstant())
                .orElse(now.plusSeconds(3600));
        return new Token(token, expires);
    }

    private static JsonObject post(URI url, String contentType, String body, String bearer) {
        var request = HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .header("Content-Type", contentType).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while asking Google for a token", e);
        } catch (IOException e) {
            throw new IdentityException("Could not reach " + url + ": " + e.getMessage(), e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() != 200 || json == null) {
            String error = json == null ? "" : json.stringValue("error_description")
                    .or(() -> json.stringValue("error")).map(e -> ": " + e).orElse("");
            if (response.statusCode() == 400 && json != null && "invalid_grant".equals(json.stringValue("error", ""))) {
                throw new IdentityException("The Google sign-in has expired or was revoked. Sign in again:"
                        + " `cafeai login google` (gcloud auth application-default login)" + error);
            }
            throw new IdentityException("Google refused the token request at " + url + " (HTTP "
                    + response.statusCode() + ")" + error);
        }
        return json;
    }

    private static JsonObject read(Path file) {
        try {
            JsonObject json = parse(Files.readString(file));
            if (json == null) throw new IdentityException("The Google credentials at " + file + " aren't a JSON object");
            return json;
        } catch (IOException e) {
            throw new IdentityException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject parse(String body) {
        try {
            var value = JsonParser.create(body).readJsonValue();
            return value.type() == JsonValueType.OBJECT ? value.asObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String required(JsonObject json, String field) {
        return json.stringValue(field).filter(s -> !s.isBlank()).orElseThrow(() ->
                new IdentityException("The Google credentials have no " + field));
    }

    private static String encode(String s) {
        return URLEncoder.encode(Objects.requireNonNull(s), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "GoogleApplicationDefault(" + scopes + ")";
    }
}
