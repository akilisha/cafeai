package io.cafeai.identity;

import io.cafeai.core.ai.Credentials;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The Claude API with no API key: Anthropic's Workload Identity Federation. The app presents a
 * token from the organisation's own identity provider, and Anthropic, checking it against the
 * federation rule set up in the Claude Console, hands back a short-lived token acting as one of
 * the organisation's service accounts.
 *
 * <pre>{@code
 *   app.ai(Anthropic.of("claude-opus-5-5").withCredentials(AnthropicFederation.rule("fdrl_...")
 *           .organization("00000000-0000-0000-0000-000000000000")
 *           .serviceAccount("svac_...")
 *           .workspace("wrkspc_...")
 *           .identityToken(IdentityToken.clientCredentials(issuer, "orders-api", secret))));
 * }</pre>
 *
 * <p>The exchange is an RFC 7523 JWT bearer grant at {@code POST /v1/oauth/token}. Anthropic's
 * token is cached and renewed {@link #RENEW_BEFORE} before it expires, each time with a new
 * identity token: Anthropic refuses a token whose {@code jti} it has already seen. The token acts
 * as the service account, so every call is the app's, not a person's; the audit records
 * ({@code app.audit}) say whom each call served.
 *
 * <p>Anthropic must be able to verify the identity provider's tokens: an issuer reachable at a
 * public {@code https} address, or its keys uploaded to the Claude Console ({@code inline}), for
 * one that isn't (a Keycloak inside the network).
 */
public final class AnthropicFederation implements Credentials {

    /** Renewed this long before it expires, as Anthropic's own SDKs do. */
    static final Duration RENEW_BEFORE = Duration.ofSeconds(120);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private final String ruleId;
    private String organizationId;
    private String serviceAccountId;
    private String workspaceId;
    private IdentityToken identityToken;
    private URI baseUrl = URI.create("https://api.anthropic.com");
    private Clock clock = Clock.systemUTC();
    private volatile TokenEndpoint.Token current;

    private AnthropicFederation(String ruleId) {
        this.ruleId = requireTagged(ruleId, "fdrl_", "federation rule id");
    }

    /** Federation under rule {@code ruleId} ({@code fdrl_...}), from the Claude Console. */
    public static AnthropicFederation rule(String ruleId) {
        return new AnthropicFederation(ruleId);
    }

    /**
     * From the environment variables Anthropic's SDKs read: {@code ANTHROPIC_FEDERATION_RULE_ID},
     * {@code ANTHROPIC_ORGANIZATION_ID}, {@code ANTHROPIC_SERVICE_ACCOUNT_ID},
     * {@code ANTHROPIC_WORKSPACE_ID} (when the rule covers several workspaces), and
     * {@code ANTHROPIC_IDENTITY_TOKEN_FILE} or {@code ANTHROPIC_IDENTITY_TOKEN}.
     *
     * @throws IllegalStateException if one that is required is unset
     */
    public static AnthropicFederation fromEnv() {
        AnthropicFederation f = rule(required("ANTHROPIC_FEDERATION_RULE_ID"))
                .organization(required("ANTHROPIC_ORGANIZATION_ID"))
                .serviceAccount(required("ANTHROPIC_SERVICE_ACCOUNT_ID"));
        String workspace = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (workspace != null && !workspace.isBlank()) f.workspace(workspace);
        String file = System.getenv("ANTHROPIC_IDENTITY_TOKEN_FILE");
        if (file != null && !file.isBlank()) return f.identityToken(IdentityToken.file(Path.of(file)));
        if (System.getenv("ANTHROPIC_IDENTITY_TOKEN") != null) return f.identityToken(IdentityToken.env("ANTHROPIC_IDENTITY_TOKEN"));
        throw new IllegalStateException("Set ANTHROPIC_IDENTITY_TOKEN_FILE or ANTHROPIC_IDENTITY_TOKEN");
    }

    /** The organisation's id (a UUID), from the Claude Console under Settings, Organization. */
    public AnthropicFederation organization(String organizationId) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        return this;
    }

    /** The service account ({@code svac_...}) the token acts as. */
    public AnthropicFederation serviceAccount(String serviceAccountId) {
        this.serviceAccountId = requireTagged(serviceAccountId, "svac_", "service account id");
        return this;
    }

    /** The workspace ({@code wrkspc_...}); required when the rule covers more than one. */
    public AnthropicFederation workspace(String workspaceId) {
        this.workspaceId = requireTagged(workspaceId, "wrkspc_", "workspace id");
        return this;
    }

    /** Where each exchange's identity token comes from. */
    public AnthropicFederation identityToken(IdentityToken identityToken) {
        this.identityToken = Objects.requireNonNull(identityToken, "identityToken");
        return this;
    }

    /** Another address for the Claude API (default {@code https://api.anthropic.com}). */
    public AnthropicFederation baseUrl(String baseUrl) {
        String b = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        return this;
    }

    AnthropicFederation clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    @Override
    public String token() {
        Instant now = clock.instant();
        TokenEndpoint.Token t = current;
        if (t != null && now.isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
        synchronized (this) {
            t = current;
            if (t != null && now.isBefore(t.expiresAt().minus(RENEW_BEFORE))) return t.value();
            current = exchange(now);
            return current.value();
        }
    }

    /** One exchange, with a new identity token. */
    private TokenEndpoint.Token exchange(Instant now) {
        if (organizationId == null || serviceAccountId == null || identityToken == null) {
            throw new IllegalStateException("Anthropic federation needs organization(...), serviceAccount(...) "
                    + "and identityToken(...)");
        }
        var body = JsonObject.builder()
                .set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .set("assertion", identityToken.get())
                .set("federation_rule_id", ruleId)
                .set("organization_id", organizationId)
                .set("service_account_id", serviceAccountId);
        if (workspaceId != null) body.set("workspace_id", workspaceId);
        URI endpoint = baseUrl.resolve("/v1/oauth/token");
        HttpResponse<String> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.build().toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted during the Anthropic token exchange", e);
        } catch (Exception e) {
            throw new IdentityException("Could not reach " + endpoint + ": " + e.getMessage(), e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() != 200 || json == null || json.stringValue("access_token").isEmpty()) {
            // Anthropic answers every refused assertion with the same opaque 401; the reason is in
            // the Claude Console's authentication history.
            throw new IdentityException("Anthropic refused the token exchange (HTTP " + response.statusCode()
                    + "). See Settings, Workload identity, History in the Claude Console for the reason.");
        }
        long expiresIn = json.longValue("expires_in").orElse(600L);
        return new TokenEndpoint.Token(json.stringValue("access_token").get(), now.plusSeconds(expiresIn));
    }

    private static JsonObject parse(String body) {
        try {
            var value = JsonParser.create(body).readJsonValue();
            return value.type() == JsonValueType.OBJECT ? value.asObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name);
        return value;
    }

    private static String requireTagged(String value, String prefix, String what) {
        Objects.requireNonNull(value, what);
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("A " + what + " starts with " + prefix + ": " + value);
        return value;
    }

    @Override
    public String toString() {
        return "AnthropicFederation(" + ruleId + ", " + serviceAccountId + ")";
    }
}
