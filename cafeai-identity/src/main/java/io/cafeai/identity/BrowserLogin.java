package io.cafeai.identity;

import io.cafeai.core.Attributes;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.identity.IdentityMode;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import io.cafeai.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Browser sign-in with OpenID Connect: the authorization code flow with PKCE, {@code state} and
 * {@code nonce}. Created by {@link Auth#login(Issuer, String, String, String)}; needs a server-side
 * session ({@code Middleware.session(store)}) in front of it.
 *
 * <p><b>Tokens stay on the server.</b> The access, refresh and ID tokens are kept in the session;
 * the browser holds only the session cookie. Signing out deletes them for good, and they are at
 * hand when a model call is made on the caller's behalf ({@link OAuthCredentials#tokenExchange}).
 *
 * <p>It serves three paths of its own:
 * <ul>
 *   <li>{@code GET /auth/login?return=/path}: starts sign-in at the issuer;</li>
 *   <li>the redirect URI's path ({@code /auth/callback}): finishes it, then starts a new session
 *       (a new id, so a session id planted before sign-in is worthless) and returns to
 *       {@code return}, which must be a path on this site;</li>
 *   <li>{@code POST /auth/logout}: signs out, here and, when the issuer supports it, at the
 *       issuer.</li>
 * </ul>
 *
 * <p>On every other request, a signed-in session puts the caller's {@link Identity} on the
 * request, renewing the access token with the refresh token when it nears expiry; if it can't be
 * renewed, the caller is signed out. Requests that change state ({@code POST}, {@code PUT},
 * {@code PATCH}, {@code DELETE}) from a signed-in session must carry its CSRF token, in an
 * {@code X-CSRF-Token} header or a {@code _csrf} form field ({@link Auth#csrfToken(Request)}),
 * or are refused with {@code 403}. A request with an {@code Authorization} header is left to
 * {@link Auth#bearer}: it isn't authenticated by cookie, so it needs no CSRF token.
 *
 * <p>Set the session cookie {@code Secure} in production
 * ({@code SessionOptions.builder().cookieOptions(CookieOptions.builder().secure(true).build())}).
 */
public final class BrowserLogin implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(BrowserLogin.class);

    /** Session attribute: the signed-in caller's tokens. */
    static final String SIGNED_IN = "cafeai.login";
    /** Session attribute: a sign-in in progress (state, nonce, PKCE verifier, where to return). */
    static final String PENDING = "cafeai.login.pending";

    private static final Duration PENDING_LIFETIME = Duration.ofMinutes(10);
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(30);
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Issuer issuer;
    private final String clientId;
    private final URI redirectUri;
    private final String callbackPath;
    private final URI authorizationEndpoint;
    private final TokenEndpoint tokens;
    private String loginPath = "/auth/login";
    private String logoutPath = "/auth/logout";
    private String scope = "openid profile";
    private String afterSignOut;
    private boolean signInRequired;
    private Clock clock = Clock.systemUTC();
    private volatile TokenValidator idTokens;

    BrowserLogin(Issuer issuer, String clientId, String clientSecret, String redirectUri) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clientId = clientId;
        this.tokens = TokenEndpoint.confidential(issuer, clientId, clientSecret);
        this.authorizationEndpoint = issuer.endpoint("authorization_endpoint").orElseThrow(() ->
                new IdentityException("Issuer " + issuer.id() + " publishes no authorization_endpoint"));
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(redirectUri, "redirectUri"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("redirectUri is not a valid URI: " + redirectUri, e);
        }
        if (!uri.isAbsolute() || uri.getPath() == null || uri.getPath().isEmpty()) {
            throw new IllegalArgumentException("redirectUri must be the absolute URL registered at the issuer, "
                    + "e.g. https://app.example.com/auth/callback: " + redirectUri);
        }
        this.redirectUri = uri;
        this.callbackPath = uri.getPath();
        IdentityMode.enable();
    }

    /** The path that starts sign-in (default {@code /auth/login}). */
    public BrowserLogin loginPath(String path) {
        this.loginPath = requirePath(path);
        return this;
    }

    /** The path that signs out (default {@code /auth/logout}); {@code POST} only. */
    public BrowserLogin logoutPath(String path) {
        this.logoutPath = requirePath(path);
        return this;
    }

    /** The scopes to ask for (default {@code openid profile}); {@code openid} is always included. */
    public BrowserLogin scope(String... scopes) {
        String joined = String.join(" ", scopes);
        this.scope = (" " + joined + " ").contains(" openid ") ? joined : "openid " + joined;
        return this;
    }

    /**
     * Where the issuer sends the browser after signing out there: an absolute URL registered at
     * the issuer. Without it the issuer shows its own page.
     */
    public BrowserLogin afterSignOut(String url) {
        this.afterSignOut = Objects.requireNonNull(url, "url");
        return this;
    }

    /**
     * Requires sign-in on every request it covers: a browser navigating there is sent to sign in
     * and brought back afterwards; any other request gets {@code 401}.
     */
    public BrowserLogin signInRequired() {
        this.signInRequired = true;
        return this;
    }

    /** For tests: the clock that decides when tokens are renewed. */
    BrowserLogin clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idTokens = null;
        return this;
    }

    private TokenValidator idTokens() {
        TokenValidator v = idTokens;
        if (v == null) {
            v = new TokenValidator(issuer, Set.of(clientId), TokenValidator.DEFAULT_ALGORITHMS,
                    Duration.ofSeconds(60), false, clock);
            idTokens = v;
        }
        return v;
    }

    // -- the middleware ---------------------------------------------------------------

    @Override
    public void handle(Request req, Response res, Next next) {
        String path = req.path();
        if (path.equals(callbackPath)) { callback(req, res); return; }
        if (path.equals(loginPath))    { start(req, res); return; }
        if (path.equals(logoutPath))   { logout(req, res); return; }

        // A bearer token is Auth.bearer's, and a request not authenticated by cookie needs no CSRF check.
        if (req.header("Authorization") != null) {
            next.run();
            return;
        }

        Session session = session(req);
        Map<String, Object> login = session == null ? null : current(session);
        if (login != null) {
            if (UNSAFE.contains(req.method()) && !csrfValid(req, login)) {
                res.status(403).json(Map.of("error", "Missing or invalid CSRF token"));
                return;
            }
            req.setAttribute(Attributes.IDENTITY, TokenValidator.identityOf(
                    string(login, "id_token"), Instant.ofEpochSecond(number(login, "expires_at"))));
            req.setAttribute(BearerAuth.ACCESS_TOKEN, string(login, "access_token"));
            next.run();
            return;
        }

        if (signInRequired) {
            String accept = Optional.ofNullable(req.header("Accept")).orElse("");
            if (req.method().equals("GET") && accept.contains("text/html")) {
                res.redirect(302, loginPath + "?return=" + encode(req.originalUrl()));
            } else {
                res.status(401).json(Map.of("error", "Sign-in required"));
            }
            return;
        }
        next.run();
    }

    /** Starts sign-in: a fresh state, nonce and PKCE verifier, then off to the issuer. */
    private void start(Request req, Response res) {
        if (!req.method().equals("GET")) {
            res.status(405).end();
            return;
        }
        Session session = requireSession(req);
        String state = random();
        String nonce = random();
        String verifier = random();
        Map<String, Object> pending = new HashMap<>();
        pending.put("state", state);
        pending.put("nonce", nonce);
        pending.put("verifier", verifier);
        pending.put("return", safeReturn(req.query("return")));
        pending.put("started", clock.instant().getEpochSecond());
        session.set(PENDING, pending);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri.toString());
        params.put("scope", scope);
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", challenge(verifier));
        params.put("code_challenge_method", "S256");
        res.set("Cache-Control", "no-store");
        res.redirect(302, withQuery(authorizationEndpoint.toString(), params));
    }

    /** Finishes sign-in: checks state, exchanges the code, validates the ID token and nonce. */
    private void callback(Request req, Response res) {
        Session session = requireSession(req);
        Object raw = session.get(PENDING);
        session.remove(PENDING);   // one attempt per sign-in
        if (!(raw instanceof Map<?, ?> pending)
                || !constantTimeEquals(String.valueOf(pending.get("state")), req.query("state"))
                || clock.instant().isAfter(Instant.ofEpochSecond(((Number) pending.get("started")).longValue())
                        .plus(PENDING_LIFETIME))) {
            res.status(400).json(Map.of("error", "Sign-in could not be completed"));
            return;
        }
        if (req.query("error") != null || req.query("code") == null) {
            log.info("Sign-in not completed at the issuer: {}", req.query("error"));
            res.status(401).json(Map.of("error", "Sign-in was not completed"));
            return;
        }

        TokenEndpoint.Token issued;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", req.query("code"));
            form.put("redirect_uri", redirectUri.toString());
            form.put("code_verifier", String.valueOf(pending.get("verifier")));
            issued = tokens.request(form, clock.instant());
        } catch (IdentityException e) {
            log.warn("Sign-in failed at the token endpoint: {}", e.getMessage());
            res.status(401).json(Map.of("error", "Sign-in could not be completed"));
            return;
        }
        Identity who = validIdToken(issued.idToken(), String.valueOf(pending.get("nonce")));
        if (who == null) {
            res.status(401).json(Map.of("error", "Sign-in could not be completed"));
            return;
        }

        // A new session under a new id: an id planted or seen before sign-in stays signed out.
        Session signedIn = session.regenerate();
        signedIn.set(SIGNED_IN, record(issued, null, random()));
        log.debug("Signed in {}", who);
        res.set("Cache-Control", "no-store");
        res.redirect(302, String.valueOf(pending.get("return")));
    }

    /** Signs out: here (the session and its tokens go), then at the issuer if it supports it. */
    private void logout(Request req, Response res) {
        if (!req.method().equals("POST")) {
            res.status(405).end();
            return;
        }
        Session session = requireSession(req);
        Map<String, Object> login = signedIn(session);
        if (login != null && !csrfValid(req, login)) {
            res.status(403).json(Map.of("error", "Missing or invalid CSRF token"));
            return;
        }
        session.invalidate();

        Optional<URI> endSession = issuer.endpoint("end_session_endpoint");
        if (login != null && endSession.isPresent()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("id_token_hint", string(login, "id_token"));
            params.put("client_id", clientId);
            if (afterSignOut != null) params.put("post_logout_redirect_uri", afterSignOut);
            res.redirect(302, withQuery(endSession.get().toString(), params));
        } else {
            res.redirect(302, "/");
        }
    }

    // -- the signed-in session ----------------------------------------------------------

    /** The signed-in tokens, renewed if they near expiry; {@code null} when signed out. */
    private Map<String, Object> current(Session session) {
        Map<String, Object> login = signedIn(session);
        if (login == null) return null;
        Instant expires = Instant.ofEpochSecond(number(login, "expires_at"));
        if (clock.instant().isBefore(expires.minus(RENEW_BEFORE))) return login;

        String refreshToken = string(login, "refresh_token");
        if (refreshToken == null) {
            session.remove(SIGNED_IN);
            return null;
        }
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);
            TokenEndpoint.Token renewed = tokens.request(form, clock.instant());
            if (renewed.idToken() != null && validIdToken(renewed.idToken(), null) == null) {
                throw new IdentityException("The renewed ID token is not valid");
            }
            Map<String, Object> updated = record(renewed, login, string(login, "csrf"));
            session.set(SIGNED_IN, updated);
            return updated;
        } catch (IdentityException e) {
            log.info("Could not renew a session's tokens; signing it out: {}", e.getMessage());
            session.remove(SIGNED_IN);
            return null;
        }
    }

    /**
     * What the session keeps after a sign-in or renewal. A renewal may omit a new refresh token
     * or ID token, in which case the previous ones are kept.
     */
    private static Map<String, Object> record(TokenEndpoint.Token t, Map<String, Object> previous, String csrf) {
        Map<String, Object> login = new HashMap<>();
        login.put("access_token", t.value());
        login.put("expires_at", t.expiresAt().getEpochSecond());
        String refresh = t.refreshToken() != null ? t.refreshToken()
                : previous == null ? null : string(previous, "refresh_token");
        if (refresh != null) login.put("refresh_token", refresh);
        login.put("id_token", t.idToken() != null ? t.idToken() : string(previous, "id_token"));
        login.put("csrf", csrf);
        return login;
    }

    /** The identity in a valid ID token whose nonce matches ({@code nonce} null: not checked). */
    private Identity validIdToken(String idToken, String nonce) {
        if (idToken == null) return null;
        TokenValidator.Result result = idTokens().validate(idToken);
        if (!result.valid()) {
            log.warn("Refused an ID token: {}", result.failure());
            return null;
        }
        if (nonce != null && !constantTimeEquals(nonce, result.identity().claim("nonce").map(String::valueOf).orElse(null))) {
            log.warn("Refused an ID token: nonce mismatch");
            return null;
        }
        return result.identity();
    }

    // -- CSRF ---------------------------------------------------------------------------

    /** The CSRF token of a signed-in browser session, or empty. */
    static Optional<String> csrfToken(Request req) {
        Session session = session(req);
        Map<String, Object> login = session == null ? null : signedIn(session);
        return login == null ? Optional.empty() : Optional.ofNullable(string(login, "csrf"));
    }

    private static boolean csrfValid(Request req, Map<String, Object> login) {
        String sent = req.header("X-CSRF-Token");
        if (sent == null) {
            try {
                sent = req.body("_csrf");
            } catch (RuntimeException e) {
                sent = null;   // no parsed form body
            }
        }
        return sent != null && constantTimeEquals(string(login, "csrf"), sent);
    }

    // -- helpers ------------------------------------------------------------------------

    private static Session session(Request req) {
        return req.hasAttribute(Attributes.HTTP_SESSION) ? req.session() : null;
    }

    private static Session requireSession(Request req) {
        Session session = session(req);
        if (session == null || !session.regenerable()) {
            throw new IllegalStateException("Browser sign-in keeps its tokens in a server-side session: "
                    + "register app.filter(Middleware.session(store)) before Auth.login(...)");
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> signedIn(Session session) {
        Object login = session.get(SIGNED_IN);
        return login instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String string(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    /** A path on this site only: anything else (another host, a scheme) returns to {@code /}. */
    static String safeReturn(String target) {
        if (target == null || target.isBlank()) return "/";
        if (!target.startsWith("/") || target.startsWith("//") || target.contains("\\")
                || target.chars().anyMatch(c -> c < 0x20)) {
            return "/";
        }
        return target;
    }

    private static String requirePath(String path) {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("A path must start with /: " + path);
        return path;
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


    /** The PKCE S256 challenge for {@code verifier} (RFC 7636 4.2). */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String withQuery(String base, Map<String, String> params) {
        StringBuilder url = new StringBuilder(base).append(base.contains("?") ? '&' : '?');
        params.forEach((k, v) -> url.append(encode(k)).append('=').append(encode(v)).append('&'));
        url.setLength(url.length() - 1);
        return url.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
