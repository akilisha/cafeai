package io.cafeai.identity;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An issuer's OAuth 2.0 token endpoint, called as a confidential client (RFC 6749 3.2), with the
 * client authenticating by HTTP Basic ({@code client_secret_basic}, RFC 6749 2.3.1).
 */
final class TokenEndpoint {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * What the endpoint issued: the access token and when to stop using it, plus a refresh token
     * and an ID token when the grant returns them ({@code null} otherwise).
     */
    record Token(String value, Instant expiresAt, String refreshToken, String idToken) {
        Token(String value, Instant expiresAt) {
            this(value, expiresAt, null, null);
        }

        @Override public String toString() { return "Token[expiresAt=" + expiresAt + "]"; }
    }

    /**
     * The endpoint refused, with an OAuth error code ({@code invalid_grant},
     * {@code authorization_pending}, ...: RFC 6749 5.2, RFC 8628 3.5).
     */
    static final class Refused extends IdentityException {
        final String error;

        Refused(String message, String error) {
            super(message);
            this.error = error;
        }
    }

    private final URI uri;
    private final URI deviceUri;
    private final URI revocationUri;
    private final URI introspectionUri;
    private final String clientId;
    private final String clientSecret;

    /** {@code clientSecret} is {@code null} for a public client. */
    private TokenEndpoint(Issuer issuer, String clientId, String clientSecret) {
        Objects.requireNonNull(issuer, "issuer");
        this.uri = issuer.endpoint("token_endpoint").orElseThrow(() -> new IdentityException(
                "Issuer " + issuer.id() + " publishes no token_endpoint in its metadata"));
        this.deviceUri = issuer.endpoint("device_authorization_endpoint").orElse(null);
        this.revocationUri = issuer.endpoint("revocation_endpoint").orElse(null);
        this.introspectionUri = issuer.endpoint("introspection_endpoint").orElse(null);
        this.clientId = requireText(clientId, "clientId");
        this.clientSecret = clientSecret;
    }

    /**
     * A public client, with no secret to keep (a CLI on someone's machine can't keep one): it
     * names itself with {@code client_id} in each request (RFC 6749 2.3.1, RFC 8628 3.1).
     */
    static TokenEndpoint publicClient(Issuer issuer, String clientId) {
        return new TokenEndpoint(issuer, clientId, null);
    }

    /** A confidential client, authenticating with its secret (HTTP Basic). */
    static TokenEndpoint confidential(Issuer issuer, String clientId, String clientSecret) {
        return new TokenEndpoint(issuer, clientId, requireText(clientSecret, "clientSecret"));
    }

    /**
     * Requests a token with {@code form} (which names the grant).
     *
     * @throws Refused if the endpoint refuses, naming its OAuth error
     * @throws IdentityException if the endpoint can't be reached or answers nonsense
     */
    Token request(Map<String, String> form, Instant now) {
        JsonObject json = post(uri, form, "a token");
        if (json.stringValue("access_token").isEmpty()) {
            throw new IdentityException("The token endpoint " + uri + " returned no access_token");
        }
        long expiresIn = json.longValue("expires_in").orElse(300L);
        return new Token(json.stringValue("access_token").get(), now.plusSeconds(expiresIn),
                json.stringValue("refresh_token").orElse(null), json.stringValue("id_token").orElse(null));
    }

    /**
     * Starts a device authorization (RFC 8628 3.1): the device code, the code the user enters,
     * and where they enter it.
     *
     * @throws IdentityException if the issuer has no device authorization endpoint, or refuses
     */
    JsonObject deviceAuthorization(Map<String, String> form) {
        if (deviceUri == null) {
            throw new IdentityException("The issuer publishes no device_authorization_endpoint: "
                    + "it doesn't support signing in from a terminal (RFC 8628)");
        }
        return post(deviceUri, form, "a device code");
    }

    /**
     * Revokes {@code token} at the issuer (RFC 7009): a revoked refresh token can't renew anything,
     * even if a copy of it survives. Returns {@code false}, without trying, when the issuer
     * publishes no {@code revocation_endpoint}.
     *
     * @param hint {@code "refresh_token"} or {@code "access_token"}
     * @throws IdentityException if the issuer can't be reached or refuses
     */
    boolean revoke(String token, String hint) {
        if (revocationUri == null) return false;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("token_type_hint", hint);
        // RFC 7009 2.2: a 200, whose body is empty or ignorable, even when the token was unknown.
        HttpResponse<String> response = send(revocationUri, form, "revocation");
        if (response.statusCode() != 200) {
            JsonObject json = parse(response.body());
            String error = json == null ? "HTTP " + response.statusCode()
                    : json.stringValue("error").orElse("HTTP " + response.statusCode());
            throw new Refused(revocationUri + " refused to revoke the token: " + error, error);
        }
        return true;
    }

    /** Whether the issuer publishes an {@code introspection_endpoint}. */
    boolean canIntrospect() {
        return introspectionUri != null;
    }

    /**
     * Asks the issuer about an access token (RFC 7662): its answer, whose {@code active} says
     * whether the token may be used now, and, when it may, what the token says.
     *
     * @throws IdentityException if the issuer can't be reached, refuses this client, or has no
     *         introspection endpoint
     */
    JsonObject introspect(String token) {
        if (introspectionUri == null) {
            throw new IdentityException("The issuer publishes no introspection_endpoint (RFC 7662)");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("token_type_hint", "access_token");
        return post(introspectionUri, form, "token introspection");
    }

    /** POSTs {@code form} as this client and returns the JSON answer of a {@code 200}. */
    private JsonObject post(URI target, Map<String, String> form, String what) {
        HttpResponse<String> response = send(target, form, what);
        JsonObject json = parse(response.body());
        if (response.statusCode() != 200) {
            // RFC 6749 5.2: "error" names what went wrong; the description is the issuer's own.
            String error = json == null ? "HTTP " + response.statusCode()
                    : json.stringValue("error").orElse("HTTP " + response.statusCode());
            throw new Refused(target + " refused the request: " + error, error);
        }
        if (json == null) throw new IdentityException(target + " answered with something other than a JSON object");
        return json;
    }

    /** POSTs {@code form} as this client: HTTP Basic for a confidential one, {@code client_id} for a public one. */
    private HttpResponse<String> send(URI target, Map<String, String> form, String what) {
        Map<String, String> fields = new LinkedHashMap<>(form);
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (clientSecret != null) {
            String basic = Base64.getEncoder().encodeToString(
                    (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        } else {
            fields.put("client_id", clientId);
        }
        String body = fields.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

        try {
            return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while requesting " + what + " from " + target, e);
        } catch (Exception e) {
            throw new IdentityException("Could not request " + what + " from " + target + ": " + e.getMessage(), e);
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

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
        return value;
    }

    @Override
    public String toString() {
        return "TokenEndpoint[" + uri + ", client=" + clientId + "]";
    }
}
