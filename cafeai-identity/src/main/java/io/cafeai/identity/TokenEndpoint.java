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

    private final URI uri;
    private final String clientId;
    private final String clientSecret;

    TokenEndpoint(Issuer issuer, String clientId, String clientSecret) {
        Objects.requireNonNull(issuer, "issuer");
        this.uri = issuer.endpoint("token_endpoint").orElseThrow(() -> new IdentityException(
                "Issuer " + issuer.id() + " publishes no token_endpoint in its metadata"));
        this.clientId = requireText(clientId, "clientId");
        this.clientSecret = requireText(clientSecret, "clientSecret");
    }

    /**
     * Requests a token with {@code form} (which names the grant).
     *
     * @throws IdentityException if the endpoint can't be reached or refuses
     */
    Token request(Map<String, String> form, Instant now) {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String basic = Base64.getEncoder().encodeToString(
                (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .header("Authorization", "Basic " + basic)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while requesting a token from " + uri, e);
        } catch (Exception e) {
            throw new IdentityException("Could not request a token from " + uri + ": " + e.getMessage(), e);
        }

        JsonObject json = parse(response.body());
        if (response.statusCode() != 200) {
            // RFC 6749 5.2: "error" names what went wrong; the description is the issuer's own.
            String error = json == null ? "HTTP " + response.statusCode()
                    : json.stringValue("error").orElse("HTTP " + response.statusCode());
            throw new IdentityException("The token endpoint " + uri + " refused the request: " + error);
        }
        if (json == null || json.stringValue("access_token").isEmpty()) {
            throw new IdentityException("The token endpoint " + uri + " returned no access_token");
        }
        long expiresIn = json.longValue("expires_in").orElse(300L);
        return new Token(json.stringValue("access_token").get(), now.plusSeconds(expiresIn),
                json.stringValue("refresh_token").orElse(null), json.stringValue("id_token").orElse(null));
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
