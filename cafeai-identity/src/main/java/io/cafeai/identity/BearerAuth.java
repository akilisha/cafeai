package io.cafeai.identity;

import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.IdentityMode;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import io.helidon.security.jwt.SignedJwt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Makes the app an OAuth 2.0 resource server: each request must carry a valid access token from
 * the issuer, in an {@code Authorization: Bearer} header (RFC 6750). A valid token puts the
 * caller's {@link io.cafeai.core.identity.Identity} on the request; anything else is answered
 * here and goes no further:
 * <ul>
 *   <li>no token: {@code 401} with {@code WWW-Authenticate: Bearer};</li>
 *   <li>a token that fails validation: {@code 401} with {@code error="invalid_token"} and a short
 *       reason;</li>
 *   <li>a malformed {@code Authorization} header: {@code 400} with
 *       {@code error="invalid_request"};</li>
 *   <li>the issuer's keys can't be read: {@code 503}, since the caller did nothing wrong.</li>
 * </ul>
 *
 * <p>Created by {@link Auth#bearer(Issuer, String...)}; configure it before the app starts.
 * Tokens in query parameters or form bodies are never read. With
 * {@link #resourceMetadata(CafeAI, String, String...)}, every refusal also names where a client
 * finds the issuer to get a token from (RFC 9728). With {@link #introspect(String, String)}, the
 * issuer is asked about each token too (RFC 7662): revoked tokens stop working at once, and
 * opaque (non-JWT) tokens are accepted.
 */
public final class BearerAuth implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(BearerAuth.class);

    /**
     * The request attribute holding the caller's validated access token, for exchanging it on
     * the caller's behalf ({@link OAuthCredentials#tokenExchange}). The same token the caller
     * sent in its {@code Authorization} header; never logged.
     */
    static final String ACCESS_TOKEN = "cafeai.identity.access_token";

    /** One issuer this API trusts: the audiences it may name here, and how to ask it about tokens. */
    private static final class Trusted {
        final Issuer issuer;
        final Set<String> audiences;
        TokenEndpoint introspectionClient;
        volatile TokenValidator validator;
        volatile Introspection introspection;

        Trusted(Issuer issuer, Set<String> audiences) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            if (audiences.isEmpty() || audiences.stream().anyMatch(a -> a == null || a.isBlank())) {
                throw new IllegalArgumentException(
                        "Name at least one audience: the identifier this service has at the issuer. "
                        + "Without it, a token issued for any other service would be accepted here.");
            }
            this.audiences = Set.copyOf(audiences);
        }
    }

    private final List<Trusted> trusted = new CopyOnWriteArrayList<>();
    private final Set<String> algorithms = new LinkedHashSet<>(TokenValidator.DEFAULT_ALGORITHMS);
    private Duration clockSkew = Duration.ofSeconds(60);
    private boolean optional;
    private boolean requireAccessTokenType;
    private ResourceMetadata metadata;
    private Duration introspectionCache = Introspection.DEFAULT_CACHE;
    private Clock clock = Clock.systemUTC();

    BearerAuth(Issuer issuer, Set<String> audiences) {
        trusted.add(new Trusted(issuer, audiences));
        // This app serves verified callers: caller-scoped work with no caller is refused from now on.
        IdentityMode.enable();
    }

    /**
     * Trusts another issuer too, with its own audiences: employees through one identity
     * provider and partners through another, or the old and the new one during a move.
     *
     * <pre>{@code
     *   app.filter(Auth.bearer(entra, "api://orders").or(okta, "orders-api"));
     * }</pre>
     *
     * <p>A JWT is checked against the issuer its {@code iss} names, and only that one; a token
     * naming none of them is refused. Callers stay apart: an identity is its issuer and subject
     * together. {@link #introspect(String, String)} after this applies to this issuer.
     */
    public BearerAuth or(Issuer issuer, String... audiences) {
        Trusted added = new Trusted(issuer, Set.of(audiences));
        if (trusted.stream().anyMatch(t -> t.issuer.id().equals(added.issuer.id()))) {
            throw new IllegalArgumentException("Issuer " + added.issuer.id() + " is trusted already");
        }
        trusted.add(added);
        return this;
    }

    /**
     * Lets requests without a token through, anonymous ({@code req.identity()} empty). A token
     * that is present must still be valid. Use it for routes that work for everyone but
     * personalise for a signed-in caller.
     */
    public BearerAuth optional() {
        this.optional = true;
        return reset();
    }

    /**
     * The tolerance for clock differences when checking {@code exp} and {@code nbf}
     * (default 60 seconds).
     */
    public BearerAuth clockSkew(Duration skew) {
        Objects.requireNonNull(skew, "skew");
        if (skew.isNegative()) throw new IllegalArgumentException("clockSkew must not be negative");
        this.clockSkew = skew;
        return reset();
    }

    /**
     * Narrows the accepted signing algorithms (by default RS, PS and ES at 256, 384 and 512).
     * Only these asymmetric algorithms can be named; {@code none} and the shared-secret
     * {@code HS*} algorithms are never accepted.
     */
    public BearerAuth algorithms(String... algorithms) {
        Set<String> chosen = Set.of(algorithms);
        if (chosen.isEmpty()) throw new IllegalArgumentException("Name at least one algorithm");
        for (String alg : chosen) {
            if (!TokenValidator.DEFAULT_ALGORITHMS.contains(alg)) {
                throw new IllegalArgumentException("Not an accepted signing algorithm: " + alg
                        + " (accepted: " + TokenValidator.DEFAULT_ALGORITHMS + ")");
            }
        }
        this.algorithms.clear();
        this.algorithms.addAll(chosen);
        return reset();
    }

    /**
     * Requires the RFC 9068 access-token type ({@code typ: at+jwt}), so an ID token can never be
     * used as an access token. Off by default because not every issuer sets it; turn it on when
     * yours does.
     */
    public BearerAuth requireAccessTokenType() {
        this.requireAccessTokenType = true;
        return reset();
    }

    /**
     * Publishes this API's Protected Resource Metadata (RFC 9728), so a client refused with a
     * {@code 401} can find out where to sign in by itself, as MCP clients do:
     *
     * <pre>{@code
     *   app.filter(Auth.bearer(issuer, "orders-api")
     *       .resourceMetadata(app, "https://orders.example.com", "orders:read"));
     * }</pre>
     *
     * <p>{@code GET /.well-known/oauth-protected-resource} (plus the resource's path, if it has
     * one) serves the resource, its issuers and {@code scopes}, with no token needed; and every
     * {@code 401} and {@code 403} challenge, here and from {@code Auth.require} and
     * {@code Auth.signedIn}, carries {@code resource_metadata="..."} pointing at it.
     *
     * @param resourceUri this API's identifier: its absolute URL, as clients reach it
     * @param scopes      the scopes to publish as supported (optional)
     */
    public BearerAuth resourceMetadata(CafeAI app, String resourceUri, String... scopes) {
        Objects.requireNonNull(app, "app");
        Set<String> published = new LinkedHashSet<>();
        for (String s : scopes) {
            Requirement.scope(s);   // validates it as an RFC 6749 scope token
            published.add(s);
        }
        this.metadata = ResourceMetadata.of(resourceUri, "resourceUri");
        metadata.serve(app, () -> trusted.stream().map(t -> t.issuer).toList(), published);
        return this;
    }

    /**
     * Also asks the issuer about each token (OAuth 2.0 Token Introspection, RFC 7662), at its
     * {@code introspection_endpoint}, as the confidential client {@code clientId}. With several
     * issuers ({@link #or(Issuer, String...)}), it applies to the last one named:
     * <ul>
     *   <li>a JWT that passes the local checks is still refused once the issuer says it is no
     *       longer active, so a revoked token, or one whose holder was disabled, stops working at
     *       once rather than at its expiry;</li>
     *   <li>an opaque token (not a JWT) is accepted when the issuer says it is active, for this
     *       service, with a subject and an expiry ahead; the caller is built from its answer.</li>
     * </ul>
     * Answers are cached briefly ({@link #introspectionCache(Duration)}), never past a token's
     * expiry. An issuer that can't be reached gets {@code 503}, as unreadable keys do.
     *
     * @throws IdentityException if the issuer publishes no {@code introspection_endpoint}
     */
    public BearerAuth introspect(String clientId, String clientSecret) {
        Trusted last = trusted.getLast();
        TokenEndpoint client = TokenEndpoint.confidential(last.issuer, clientId, clientSecret);
        if (!client.canIntrospect()) {
            throw new IdentityException("Issuer " + last.issuer.id() + " publishes no introspection_endpoint (RFC 7662)");
        }
        last.introspectionClient = client;
        return reset();
    }

    /**
     * How long the issuer's answer about a token is reused (default 30 seconds): the most a
     * revocation can go unnoticed. {@link Duration#ZERO} asks on every request.
     */
    public BearerAuth introspectionCache(Duration cacheFor) {
        Objects.requireNonNull(cacheFor, "cacheFor");
        if (cacheFor.isNegative()) throw new IllegalArgumentException("introspectionCache must not be negative");
        this.introspectionCache = cacheFor;
        return reset();
    }

    /** For tests: the clock that decides whether a token has expired. */
    BearerAuth clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return reset();
    }

    private BearerAuth reset() {
        for (Trusted t : trusted) {
            t.validator = null;
            t.introspection = null;
        }
        return this;
    }

    /** {@code t}'s word on tokens, or {@code null} when it isn't asked. */
    private Introspection introspection(Trusted t) {
        if (t.introspectionClient == null) return null;
        Introspection i = t.introspection;
        if (i == null) {
            i = new Introspection(t.issuer, t.audiences, t.introspectionClient, introspectionCache, clockSkew, clock);
            t.introspection = i;
        }
        return i;
    }

    private TokenValidator validator(Trusted t) {
        TokenValidator v = t.validator;
        if (v == null) {
            v = new TokenValidator(t.issuer, t.audiences, algorithms, clockSkew, requireAccessTokenType, clock);
            t.validator = v;
        }
        return v;
    }

    /**
     * The outcome for {@code token}. With one issuer, it is checked against that one. With
     * several, a JWT goes to the issuer its {@code iss} names (read before any check, only to
     * choose; that issuer then checks everything), and an opaque token to each issuer that is
     * asked about tokens, until one says it is active.
     */
    private TokenValidator.Result validate(String token) {
        if (trusted.size() == 1) return check(trusted.getFirst(), token);
        String iss = unverifiedIssuer(token);
        if (iss != null) {
            for (Trusted t : trusted) {
                if (t.issuer.id().equals(iss)) return check(t, token);
            }
            return TokenValidator.Result.refused(TokenValidator.Failure.ISSUER);
        }
        TokenValidator.Result last = TokenValidator.Result.refused(TokenValidator.Failure.MALFORMED);
        for (Trusted t : trusted) {
            if (t.introspectionClient == null) continue;
            last = check(t, token);
            if (last.valid()) return last;
        }
        return last;
    }

    private TokenValidator.Result check(Trusted t, String token) {
        TokenValidator.Result result = validator(t).validate(token);
        Introspection asking = introspection(t);
        return asking == null ? result : asking.check(token, result);
    }

    /** The {@code iss} a JWT claims, unverified, to choose which issuer checks it; {@code null} if it isn't a JWT. */
    private static String unverifiedIssuer(String token) {
        if (token.chars().filter(c -> c == '.').count() != 2) return null;
        try {
            return SignedJwt.parseToken(token).getJwt().issuer().orElse("");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void handle(Request req, Response res, Next next) {
        String metadataUrl = metadata == null ? null : metadata.url;
        if (metadataUrl != null) req.setAttribute(ResourceMetadata.ATTRIBUTE, metadataUrl);
        String header = req.header("Authorization");
        if (header == null) {
            if (optional) {
                next.run();
            } else {
                res.status(401).set("WWW-Authenticate", ResourceMetadata.challenge("Bearer", metadataUrl)).end();
            }
            return;
        }

        String token = bearerToken(header);
        if (token == null) {
            res.status(400).set("WWW-Authenticate", ResourceMetadata.challenge(
                    challenge("invalid_request", "The Authorization header is not a Bearer token"), metadataUrl)).end();
            return;
        }

        TokenValidator.Result result;
        try {
            result = validate(token);
        } catch (IdentityException e) {
            log.error("Can't validate tokens: {}", e.getMessage());
            res.status(503).end();
            return;
        }

        if (!result.valid()) {
            log.debug("Refused a token: {}", result.failure());
            res.status(401).set("WWW-Authenticate",
                    ResourceMetadata.challenge(challenge("invalid_token", result.failure().description), metadataUrl)).end();
            return;
        }

        req.setAttribute(Attributes.IDENTITY, result.identity());
        req.setAttribute(ACCESS_TOKEN, token);
        next.run();
    }

    /** The token from {@code Bearer <token>}; the scheme name is case-insensitive (RFC 7235). */
    private static String bearerToken(String header) {
        String h = header.trim();
        if (h.length() < 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = h.substring(7).trim();
        // RFC 6750 b64token: one token, no spaces.
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) return null;
        return token;
    }

    private static String challenge(String error, String description) {
        return "Bearer error=\"" + error + "\", error_description=\"" + description + "\"";
    }
}
