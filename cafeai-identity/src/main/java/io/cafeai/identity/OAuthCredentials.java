package io.cafeai.identity;

import io.cafeai.core.ai.Credentials;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.identity.IdentityRequiredException;
import io.cafeai.core.internal.CurrentRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model credentials obtained from an OAuth 2.0 issuer, per call, instead of a long-lived API key.
 *
 * <pre>{@code
 *   // As the app itself:
 *   app.ai(OpenAI.of("<model-id>").withBaseUrl(gateway)
 *           .withCredentials(OAuthCredentials.clientCredentials(issuer, "orders-api", secret)));
 *
 *   // On behalf of the signed-in caller (RFC 8693 token exchange):
 *   app.ai(OpenAI.of("<model-id>").withBaseUrl(gateway)
 *           .withCredentials(OAuthCredentials.tokenExchange(issuer, "orders-api", secret, "model-gateway")));
 *
 *   // On behalf of the signed-in caller, at Microsoft Entra ID (its on-behalf-of flow):
 *   app.ai(Anthropic.of("claude-opus-5-5").withBaseUrl(foundry)
 *           .withCredentials(OAuthCredentials.onBehalfOf(entra, "orders-api", secret, "https://ai.azure.com/.default")));
 * }</pre>
 *
 * <p>Tokens are cached until shortly before they expire, so most calls make no request to the
 * issuer. The provider's client stays one shared object; only the token changes per call.
 */
public final class OAuthCredentials {

    /** Tokens are renewed this long before they expire, so none is used at the edge. */
    static final Duration MARGIN = Duration.ofSeconds(30);

    private OAuthCredentials() {}

    /**
     * The app's own token (client credentials grant, RFC 6749 4.4): every call is made as the
     * app. For work that is the app's, not a caller's, including work with no request behind it.
     * Combine with the audit trail ({@code app.audit}) to tie each call to the caller it served.
     */
    public static ClientCredentials clientCredentials(Issuer issuer, String clientId, String clientSecret) {
        return new ClientCredentials(TokenEndpoint.confidential(issuer, clientId, clientSecret), Clock.systemUTC());
    }

    /**
     * A token on behalf of the signed-in caller (token exchange, RFC 8693): the caller's own access
     * token is exchanged for one issued for {@code audience} (the model endpoint), naming the same
     * caller and marking this app as acting for them. The model endpoint sees who the call is for.
     *
     * <p>Requires a verified caller: a call with none is refused with
     * {@link IdentityRequiredException}, never made with some other credential. Token exchange is
     * a standard but optional grant; the issuer must support it. Microsoft Entra ID doesn't: use
     * {@link #onBehalfOf} there.
     *
     * @param audience the identifier of the service the token is for, at the issuer
     */
    public static TokenExchange tokenExchange(Issuer issuer, String clientId, String clientSecret,
                                              String audience) {
        return new TokenExchange(issuer.id(), TokenEndpoint.confidential(issuer, clientId, clientSecret), audience,
                Clock.systemUTC());
    }

    /**
     * A token on behalf of the signed-in caller at <b>Microsoft Entra ID</b>, which has no RFC
     * 8693 token exchange but its own on-behalf-of flow: the caller's token, issued for this app,
     * goes as a JWT bearer assertion (RFC 7523) with {@code requested_token_use=on_behalf_of}, and
     * comes back as a token for {@code scopes} (for Claude in Microsoft Foundry,
     * {@code https://ai.azure.com/.default}) naming the same person. The model endpoint decides,
     * with Azure RBAC, what that person may do.
     *
     * <p>The caller's token must have been issued for this app ({@code aud} is {@code clientId}),
     * as Entra requires: put {@code clientId} among {@code Auth.bearer}'s audiences. When Entra
     * says the person must sign in again (multi-factor, a changed policy:
     * {@code interaction_required}), the call is refused with {@code 401}. Like token exchange, it
     * needs a verified caller, refuses one from another issuer, and caches per caller.
     *
     * @param scopes what the token is for, e.g. {@code "https://ai.azure.com/.default"}
     */
    public static OnBehalfOf onBehalfOf(Issuer issuer, String clientId, String clientSecret, String... scopes) {
        return new OnBehalfOf(issuer.id(), TokenEndpoint.confidential(issuer, clientId, clientSecret),
                String.join(" ", scopes), Clock.systemUTC());
    }

    /**
     * The caller's token exchanged at whichever issuer it came from, for an app that trusts
     * several ({@code Auth.bearer(a, ...).or(b, ...)}): a caller's token can only be exchanged by
     * the issuer that issued it.
     *
     * <pre>{@code
     *   .withCredentials(OAuthCredentials.byIssuer(
     *       OAuthCredentials.onBehalfOf(entra, "orders-api", secretA, "https://ai.azure.com/.default"),
     *       OAuthCredentials.tokenExchange(okta, "orders-api", secretB, "model-gateway")))
     * }</pre>
     *
     * A caller from an issuer none of them exchange at is refused, never sent to another issuer.
     */
    public static Credentials byIssuer(CallerCredentials... exchanges) {
        Map<String, CallerCredentials> byIssuer = new LinkedHashMap<>();
        for (CallerCredentials e : exchanges) {
            if (byIssuer.putIfAbsent(e.issuerId, e) != null) {
                throw new IllegalArgumentException("Two exchanges at " + e.issuerId);
            }
        }
        if (byIssuer.isEmpty()) throw new IllegalArgumentException("Name at least one exchange");
        return new Credentials() {
            @Override
            public String token() {
                Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                        "A model call on behalf of the caller was made with no verified caller. Require "
                        + "sign-in on this route, or carry the request to this thread with RequestScope."));
                CallerCredentials exchange = byIssuer.get(caller.issuer());
                if (exchange == null) {
                    throw new IllegalStateException("No exchange for callers from " + caller.issuer()
                            + " (configured for " + byIssuer.keySet() + ")");
                }
                return exchange.token();
            }

            @Override public boolean perCaller() { return true; }

            @Override public String toString() { return "OAuthCredentials.byIssuer(" + byIssuer.values() + ")"; }
        };
    }

    /** Client credentials: one token for the app, renewed before it expires. */
    public static final class ClientCredentials implements Credentials {
        private final TokenEndpoint endpoint;
        private final Clock clock;
        private String scope;
        private String audience;
        private volatile TokenEndpoint.Token current;

        ClientCredentials(TokenEndpoint endpoint, Clock clock) {
            this.endpoint = endpoint;
            this.clock = clock;
        }

        /** Scopes to request, space-separated in the request. */
        public ClientCredentials scope(String... scopes) {
            this.scope = String.join(" ", scopes);
            return this;
        }

        /** The service the token is for, when the issuer expects one ({@code audience}). */
        public ClientCredentials audience(String audience) {
            this.audience = audience;
            return this;
        }

        @Override
        public String token() {
            Instant now = clock.instant();
            TokenEndpoint.Token t = current;
            if (t != null && now.isBefore(t.expiresAt().minus(MARGIN))) return t.value();
            synchronized (this) {
                t = current;
                if (t != null && now.isBefore(t.expiresAt().minus(MARGIN))) return t.value();
                Map<String, String> form = new LinkedHashMap<>();
                form.put("grant_type", "client_credentials");
                if (scope != null) form.put("scope", scope);
                if (audience != null) form.put("audience", audience);
                current = endpoint.request(form, now);
                return current.value();
            }
        }

        @Override
        public String toString() {
            return "OAuthCredentials.clientCredentials(" + endpoint + ")";
        }
    }

    /**
     * A token for the signed-in caller, from the issuer that issued theirs, in exchange for it:
     * per caller, cached, never used past the caller's own token. Subclasses say how the issuer is
     * asked ({@link TokenExchange}, {@link OnBehalfOf}).
     */
    public abstract static sealed class CallerCredentials implements Credentials permits TokenExchange, OnBehalfOf {
        private static final int MAX_CACHED = 10_000;

        final String issuerId;
        final TokenEndpoint endpoint;
        private final Clock clock;
        private final Map<String, TokenEndpoint.Token> cache = new ConcurrentHashMap<>();

        CallerCredentials(String issuerId, TokenEndpoint endpoint, Clock clock) {
            this.issuerId = issuerId;
            this.endpoint = endpoint;
            this.clock = clock;
        }

        /** What this call's subject token is for, in words, for errors. */
        abstract String purpose();

        /** The token request for {@code subjectToken}. */
        abstract Map<String, String> form(String subjectToken);

        @Override
        public final String token() {
            Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                    "A model call on behalf of the caller (" + purpose() + ") was made with no verified caller. "
                    + "Require sign-in on this route, or carry the request to this thread with RequestScope."));
            // Only the issuer that issued the caller's token can exchange it: never send it to another.
            if (!caller.issuer().equals(issuerId)) {
                throw new IllegalStateException("The caller is from " + caller.issuer() + ", but this exchange is at "
                        + issuerId + ". With several issuers, use OAuthCredentials.byIssuer(...).");
            }
            String subjectToken = CurrentRequest.get()
                    .map(r -> r.attribute(BearerAuth.ACCESS_TOKEN))
                    .filter(String.class::isInstance).map(String.class::cast)
                    .orElseThrow(() -> new IdentityRequiredException(
                            "The caller " + caller + " has no access token to exchange on this request"));

            Instant now = clock.instant();
            String key = hash(subjectToken);
            TokenEndpoint.Token cached = cache.get(key);
            if (cached != null && now.isBefore(cached.expiresAt().minus(MARGIN))) return cached.value();

            TokenEndpoint.Token issued;
            try {
                issued = endpoint.request(form(subjectToken), now);
            } catch (TokenEndpoint.Refused e) {
                // The person must act (sign in again, a second factor): 401, so the client takes them back.
                if ("interaction_required".equals(e.error) || "consent_required".equals(e.error)) {
                    throw new IdentityRequiredException("The issuer needs the caller to sign in again (" + e.error
                            + ") before acting for them");
                }
                throw e;
            }
            // Never use it past the caller's own token: the exchange vouched for that token only.
            Instant expires = issued.expiresAt().isAfter(caller.expiresAt()) ? caller.expiresAt() : issued.expiresAt();
            TokenEndpoint.Token kept = new TokenEndpoint.Token(issued.value(), expires);
            if (cache.size() >= MAX_CACHED) cache.values().removeIf(t -> !now.isBefore(t.expiresAt()));
            if (cache.size() < MAX_CACHED) cache.put(key, kept);
            return kept.value();
        }

        /** The caller's own credential: calls never use the shared semantic cache. */
        @Override
        public boolean perCaller() {
            return true;
        }

        /** Tokens currently cached, for tests. */
        int cached() {
            return cache.size();
        }

        private static String hash(String token) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Token exchange (RFC 8693): a token per caller and audience, renewed before it expires. */
    public static final class TokenExchange extends CallerCredentials {
        private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
        private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

        private final String audience;
        private String scope;

        TokenExchange(String issuerId, TokenEndpoint endpoint, String audience, Clock clock) {
            super(issuerId, endpoint, clock);
            if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience must not be blank");
            this.audience = audience;
        }

        /** Scopes to request for the exchanged token. */
        public TokenExchange scope(String... scopes) {
            this.scope = String.join(" ", scopes);
            return this;
        }

        @Override String purpose() { return "token exchange for '" + audience + "'"; }

        @Override
        Map<String, String> form(String subjectToken) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", GRANT);
            form.put("subject_token", subjectToken);
            form.put("subject_token_type", ACCESS_TOKEN_TYPE);
            form.put("requested_token_type", ACCESS_TOKEN_TYPE);
            form.put("audience", audience);
            if (scope != null) form.put("scope", scope);
            return form;
        }

        @Override
        public String toString() {
            return "OAuthCredentials.tokenExchange(" + endpoint + ", audience=" + audience + ")";
        }
    }

    /** Microsoft Entra ID's on-behalf-of flow: a JWT bearer grant (RFC 7523) with {@code requested_token_use=on_behalf_of}. */
    public static final class OnBehalfOf extends CallerCredentials {
        private static final String GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

        private final String scope;

        OnBehalfOf(String issuerId, TokenEndpoint endpoint, String scope, Clock clock) {
            super(issuerId, endpoint, clock);
            if (scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("Name the scope the token is for, e.g. https://ai.azure.com/.default");
            }
            this.scope = scope;
        }

        @Override String purpose() { return "on-behalf-of for '" + scope + "'"; }

        @Override
        Map<String, String> form(String subjectToken) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", GRANT);
            form.put("assertion", subjectToken);
            form.put("scope", scope);
            form.put("requested_token_use", "on_behalf_of");
            return form;
        }

        @Override
        public String toString() {
            return "OAuthCredentials.onBehalfOf(" + endpoint + ", scope=" + scope + ")";
        }
    }
}
