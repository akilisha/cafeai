package io.cafeai.identity;

import io.helidon.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Signing in from a terminal: the OAuth 2.0 device authorization grant (RFC 8628). The program
 * shows a short code and a link; the user opens the link on any device, phone or laptop, signs in
 * there and enters the code; the program waits, then has a token. It works over SSH and in
 * containers, where no browser can open.
 *
 * <pre>{@code
 *   var login = DeviceLogin.of(Issuer.discover("https://issuer.example.com/realms/acme"), "orders-cli")
 *       .scope("openid", "orders:read");
 *   String token = login.accessToken();   // asks the user to sign in only when it must
 *   // call a CafeAI service with "Authorization: Bearer " + token
 * }</pre>
 *
 * <p>Tokens are cached in a file only the user can read ({@code ~/.cafeai/tokens/}, one per
 * issuer, client and scope) and renewed with the refresh token, so the user signs in once, not on
 * every run. {@link #signOut()} revokes the sign-in at the issuer and deletes the file. The
 * client is a public one: a program on someone's machine can't keep a secret.
 */
public final class DeviceLogin {

    private static final Logger log = LoggerFactory.getLogger(DeviceLogin.class);

    private static final String GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final Duration SLOW_DOWN = Duration.ofSeconds(5);

    /**
     * What the user is asked to do: open {@code verificationUri} and enter {@code userCode}.
     * {@code verificationUriComplete}, when the issuer gives one, already carries the code (a QR
     * code of it saves typing). {@code expiresIn} is how long the code is good for.
     */
    public record Prompt(String userCode, URI verificationUri, URI verificationUriComplete, Duration expiresIn) { }

    /** Waits between polls; replaceable in tests. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Issuer issuer;
    private final String clientId;
    private final TokenEndpoint tokens;
    private String scope;
    private Path cacheFile;
    private boolean caching = true;
    private Consumer<Prompt> onPrompt = DeviceLogin::printPrompt;
    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = d -> Thread.sleep(d.toMillis());

    private DeviceLogin(Issuer issuer, String clientId) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clientId = clientId;
        this.tokens = TokenEndpoint.publicClient(issuer, clientId);
    }

    /**
     * Terminal sign-in at {@code issuer} as the public client {@code clientId}, which must be
     * registered there with the device grant enabled.
     */
    public static DeviceLogin of(Issuer issuer, String clientId) {
        return new DeviceLogin(issuer, clientId);
    }

    /** Scopes to ask for. Include {@code offline_access} if the issuer requires it for refresh tokens. */
    public DeviceLogin scope(String... scopes) {
        this.scope = String.join(" ", scopes);
        return this;
    }

    /** Where to cache tokens, instead of the default under {@code ~/.cafeai/tokens/}. */
    public DeviceLogin cache(Path file) {
        this.cacheFile = Objects.requireNonNull(file, "file");
        this.caching = true;
        return this;
    }

    /** Never cache tokens on disk: every run signs in again. */
    public DeviceLogin noCache() {
        this.caching = false;
        return this;
    }

    /** How to show the prompt (default: two lines on standard error). */
    public DeviceLogin onPrompt(Consumer<Prompt> onPrompt) {
        this.onPrompt = Objects.requireNonNull(onPrompt, "onPrompt");
        return this;
    }

    DeviceLogin clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    DeviceLogin sleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
        return this;
    }

    /**
     * A current access token: the cached one, renewed if it nears expiry, or a new sign-in when
     * there is neither. Blocks while the user signs in.
     *
     * @throws IdentityException if sign-in is denied, the code expires, or the issuer can't be used
     */
    public String accessToken() {
        return cache().accessToken(this::signIn);
    }

    /**
     * Signs this program out: revokes the cached refresh token at the issuer (RFC 7009), so a copy
     * of it can't renew anything, then deletes the cache file. The file is deleted even when the
     * issuer can't be told.
     *
     * <p>It doesn't sign the user out of their browser. The issuer's session there is the user's,
     * shared by every app they use, and the next {@link #accessToken()} may be approved by it
     * without a password. To sign in as someone else, open the link in a private window.
     */
    public SignedOut signOut() {
        return cache().signOut();
    }

    private TokenCache cache() {
        Path file = !caching ? null : cacheFile != null ? cacheFile : TokenCache.defaultFile(issuer, clientId, scope);
        return new TokenCache(tokens, file, clock);
    }

    /** The device flow: ask for a code, show it, poll until the user has signed in (RFC 8628 3.4, 3.5). */
    private TokenEndpoint.Token signIn() {
        Map<String, String> start = new LinkedHashMap<>();
        if (scope != null) start.put("scope", scope);
        JsonObject device = tokens.deviceAuthorization(start);

        String deviceCode = device.stringValue("device_code").orElseThrow(() ->
                new IdentityException("The issuer's device authorization returned no device_code"));
        String userCode = device.stringValue("user_code").orElseThrow(() ->
                new IdentityException("The issuer's device authorization returned no user_code"));
        URI verification = URI.create(device.stringValue("verification_uri").orElseThrow(() ->
                new IdentityException("The issuer's device authorization returned no verification_uri")));
        URI complete = device.stringValue("verification_uri_complete").map(URI::create).orElse(null);
        Duration lifetime = Duration.ofSeconds(device.longValue("expires_in").orElse(600L));
        Duration interval = Duration.ofSeconds(device.longValue("interval").orElse(5L));
        Instant deadline = clock.instant().plus(lifetime);

        onPrompt.accept(new Prompt(userCode, verification, complete, lifetime));

        Map<String, String> poll = new LinkedHashMap<>();
        poll.put("grant_type", GRANT);
        poll.put("device_code", deviceCode);
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw new IdentityException("Sign-in timed out: the code " + userCode + " expired before it was used");
            }
            try {
                sleeper.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdentityException("Interrupted while waiting for sign-in", e);
            }
            try {
                return tokens.request(poll, clock.instant());
            } catch (TokenEndpoint.Refused e) {
                switch (e.error) {
                    case "authorization_pending" -> { }                       // keep waiting
                    case "slow_down" -> interval = interval.plus(SLOW_DOWN);  // RFC 8628 3.5
                    case "access_denied" -> throw new IdentityException("Sign-in was denied");
                    case "expired_token" -> throw new IdentityException(
                            "Sign-in timed out: the code " + userCode + " expired before it was used");
                    default -> throw e;
                }
            }
        }
    }

    private static void printPrompt(Prompt prompt) {
        System.err.println("To sign in, open " + prompt.verificationUri() + " and enter the code " + prompt.userCode());
        if (prompt.verificationUriComplete() != null) {
            System.err.println("(or open " + prompt.verificationUriComplete() + ")");
        }
    }
}
