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
     * a standard but optional grant; the issuer must support it.
     *
     * @param audience the identifier of the service the token is for, at the issuer
     */
    public static TokenExchange tokenExchange(Issuer issuer, String clientId, String clientSecret,
                                              String audience) {
        return new TokenExchange(issuer.id(), TokenEndpoint.confidential(issuer, clientId, clientSecret), audience,
                Clock.systemUTC());
    }

    /**
     * Token exchange at whichever issuer the caller came from, for an app that trusts several
     * ({@code Auth.bearer(a, ...).or(b, ...)}): a caller's token can only be exchanged by the
     * issuer that issued it.
     *
     * <pre>{@code
     *   .withCredentials(OAuthCredentials.byIssuer(
     *       OAuthCredentials.tokenExchange(entra, "orders-api", secretA, "api://model-gateway"),
     *       OAuthCredentials.tokenExchange(okta,  "orders-api", secretB, "model-gateway")))
     * }</pre>
     *
     * A caller from an issuer none of them exchange at is refused, never sent to another issuer.
     */
    public static Credentials byIssuer(TokenExchange... exchanges) {
        Map<String, TokenExchange> byIssuer = new LinkedHashMap<>();
        for (TokenExchange e : exchanges) {
            if (byIssuer.putIfAbsent(e.issuerId, e) != null) {
                throw new IllegalArgumentException("Two token exchanges at " + e.issuerId);
            }
        }
        if (byIssuer.isEmpty()) throw new IllegalArgumentException("Name at least one token exchange");
        return new Credentials() {
            @Override
            public String token() {
                Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                        "A model call on behalf of the caller was made with no verified caller. Require "
                        + "sign-in on this route, or carry the request to this thread with RequestScope."));
                TokenExchange exchange = byIssuer.get(caller.issuer());
                if (exchange == null) {
                    throw new IllegalStateException("No token exchange for callers from " + caller.issuer()
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

    /** Token exchange: a token per caller and audience, renewed before it expires. */
    public static final class TokenExchange implements Credentials {
        private static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
        private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
        private static final int MAX_CACHED = 10_000;

        private final String issuerId;
        private final TokenEndpoint endpoint;
        private final String audience;
        private final Clock clock;
        private String scope;
        private final Map<String, TokenEndpoint.Token> cache = new ConcurrentHashMap<>();

        TokenExchange(String issuerId, TokenEndpoint endpoint, String audience, Clock clock) {
            if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience must not be blank");
            this.issuerId = issuerId;
            this.endpoint = endpoint;
            this.audience = audience;
            this.clock = clock;
        }

        /** Scopes to request for the exchanged token. */
        public TokenExchange scope(String... scopes) {
            this.scope = String.join(" ", scopes);
            return this;
        }

        @Override
        public String token() {
            Identity caller = Identity.current().orElseThrow(() -> new IdentityRequiredException(
                    "A model call on behalf of the caller (token exchange for '" + audience
                    + "') was made with no verified caller. Require sign-in on this route, or carry "
                    + "the request to this thread with RequestScope."));
            // Only the issuer that issued the caller's token can exchange it: never send it to another.
            if (!caller.issuer().equals(issuerId)) {
                throw new IllegalStateException("The caller is from " + caller.issuer() + ", but this token exchange is at "
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

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", GRANT);
            form.put("subject_token", subjectToken);
            form.put("subject_token_type", ACCESS_TOKEN_TYPE);
            form.put("requested_token_type", ACCESS_TOKEN_TYPE);
            form.put("audience", audience);
            if (scope != null) form.put("scope", scope);
            TokenEndpoint.Token issued = endpoint.request(form, now);
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

        /** Exchanged tokens currently cached, for tests. */
        int cached() {
            return cache.size();
        }

        @Override
        public String toString() {
            return "OAuthCredentials.tokenExchange(" + endpoint + ", audience=" + audience + ")";
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
}
