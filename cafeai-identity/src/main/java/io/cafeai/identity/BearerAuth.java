package io.cafeai.identity;

import io.cafeai.core.Attributes;
import io.cafeai.core.identity.IdentityMode;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
 * Tokens in query parameters or form bodies are never read.
 */
public final class BearerAuth implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(BearerAuth.class);

    /**
     * The request attribute holding the caller's validated access token, for exchanging it on
     * the caller's behalf ({@link OAuthCredentials#tokenExchange}). The same token the caller
     * sent in its {@code Authorization} header; never logged.
     */
    static final String ACCESS_TOKEN = "cafeai.identity.access_token";

    private final Issuer issuer;
    private final Set<String> audiences;
    private final Set<String> algorithms = new LinkedHashSet<>(TokenValidator.DEFAULT_ALGORITHMS);
    private Duration clockSkew = Duration.ofSeconds(60);
    private boolean optional;
    private boolean requireAccessTokenType;
    private Clock clock = Clock.systemUTC();

    private volatile TokenValidator validator;

    BearerAuth(Issuer issuer, Set<String> audiences) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        if (audiences.isEmpty() || audiences.stream().anyMatch(a -> a == null || a.isBlank())) {
            throw new IllegalArgumentException(
                    "Name at least one audience: the identifier this service has at the issuer. "
                    + "Without it, a token issued for any other service would be accepted here.");
        }
        this.audiences = Set.copyOf(audiences);
        // This app serves verified callers: caller-scoped work with no caller is refused from now on.
        IdentityMode.enable();
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

    /** For tests: the clock that decides whether a token has expired. */
    BearerAuth clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return reset();
    }

    private BearerAuth reset() {
        validator = null;
        return this;
    }

    private TokenValidator validator() {
        TokenValidator v = validator;
        if (v == null) {
            v = new TokenValidator(issuer, audiences, algorithms, clockSkew, requireAccessTokenType, clock);
            validator = v;
        }
        return v;
    }

    @Override
    public void handle(Request req, Response res, Next next) {
        String header = req.header("Authorization");
        if (header == null) {
            if (optional) {
                next.run();
            } else {
                res.status(401).set("WWW-Authenticate", "Bearer").end();
            }
            return;
        }

        String token = bearerToken(header);
        if (token == null) {
            res.status(400).set("WWW-Authenticate",
                    challenge("invalid_request", "The Authorization header is not a Bearer token")).end();
            return;
        }

        TokenValidator.Result result;
        try {
            result = validator().validate(token);
        } catch (IdentityException e) {
            log.error("Can't validate tokens: {}", e.getMessage());
            res.status(503).end();
            return;
        }

        if (!result.valid()) {
            log.debug("Refused a token: {}", result.failure());
            res.status(401).set("WWW-Authenticate",
                    challenge("invalid_token", result.failure().description)).end();
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
