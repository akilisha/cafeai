package io.cafeai.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Signing in from a terminal through the browser on the same machine, as {@code gh auth login}
 * and {@code az login} do on a desktop (OAuth 2.0 for Native Apps, RFC 8252): the program opens
 * the browser at the issuer's sign-in page, the user signs in there, and the issuer sends the
 * browser back to the program, which is listening on the loopback interface. No code to type.
 *
 * <pre>{@code
 *   var login = LoopbackLogin.of(Issuer.discover("https://issuer.example.com/realms/acme"), "orders-cli")
 *       .scope("openid", "orders:read");
 *   String token = login.accessToken();   // opens the browser only when it must
 * }</pre>
 *
 * <p>The authorization code flow with PKCE ({@code S256}) and {@code state}, as a public client:
 * register {@code http://127.0.0.1/callback} at the issuer, which must accept any port on it
 * (RFC 8252 7.3); the program listens on {@code 127.0.0.1} on a port of its own choosing, only
 * until the sign-in completes. Where no browser can be opened (over SSH, in a container), use
 * {@link DeviceLogin}. Tokens are cached and renewed as {@link DeviceLogin}'s are, in the same
 * file for the same issuer, client and scope.
 */
public final class LoopbackLogin {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CALLBACK = "/callback";

    private final Issuer issuer;
    private final String clientId;
    private final TokenEndpoint tokens;
    private final URI authorizationEndpoint;
    private String scope;
    private Path cacheFile;
    private boolean caching = true;
    private Consumer<URI> onOpen = LoopbackLogin::openBrowser;
    private Duration timeout = Duration.ofMinutes(5);
    private Clock clock = Clock.systemUTC();

    private LoopbackLogin(Issuer issuer, String clientId) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clientId = clientId;
        this.tokens = TokenEndpoint.publicClient(issuer, clientId);
        this.authorizationEndpoint = issuer.endpoint("authorization_endpoint").orElseThrow(() ->
                new IdentityException("Issuer " + issuer.id() + " publishes no authorization_endpoint"));
    }

    /**
     * Browser sign-in at {@code issuer} as the public client {@code clientId}, which must be
     * registered there with the redirect URI {@code http://127.0.0.1/callback}, any port.
     */
    public static LoopbackLogin of(Issuer issuer, String clientId) {
        return new LoopbackLogin(issuer, clientId);
    }

    /** Scopes to ask for. Include {@code offline_access} if the issuer requires it for refresh tokens. */
    public LoopbackLogin scope(String... scopes) {
        this.scope = String.join(" ", scopes);
        return this;
    }

    /** Where to cache tokens, instead of the default under {@code ~/.cafeai/tokens/}. */
    public LoopbackLogin cache(Path file) {
        this.cacheFile = Objects.requireNonNull(file, "file");
        this.caching = true;
        return this;
    }

    /** Never cache tokens on disk: every run signs in again. */
    public LoopbackLogin noCache() {
        this.caching = false;
        return this;
    }

    /**
     * What to do with the sign-in link (default: open it in the system browser, and print it
     * in case that doesn't work).
     */
    public LoopbackLogin onOpen(Consumer<URI> onOpen) {
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
        return this;
    }

    /** How long to wait for the user to sign in (default five minutes). */
    public LoopbackLogin timeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        this.timeout = timeout;
        return this;
    }

    LoopbackLogin clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    /**
     * A current access token: the cached one, renewed if it nears expiry, or a new sign-in in
     * the browser when there is neither. Blocks while the user signs in.
     *
     * @throws IdentityException if sign-in is denied or times out, or the issuer can't be used
     */
    public String accessToken() {
        return cache().accessToken(this::signIn);
    }

    /**
     * Signs this program out: revokes the cached refresh token at the issuer (RFC 7009) and
     * deletes the cache file. The browser stays signed in to the issuer, as with
     * {@link DeviceLogin#signOut()}.
     */
    public SignedOut signOut() {
        return cache().signOut();
    }

    private TokenCache cache() {
        Path file = !caching ? null : cacheFile != null ? cacheFile : TokenCache.defaultFile(issuer, clientId, scope);
        return new TokenCache(tokens, file, clock);
    }

    /** Listen on the loopback interface, send the browser to sign in, take the code it brings back. */
    private TokenEndpoint.Token signIn() {
        String state = random();
        String verifier = random();
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        } catch (IOException e) {
            throw new IdentityException("Could not listen on 127.0.0.1 for the sign-in to come back", e);
        }
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK;
        server.createContext(CALLBACK, exchange -> callback(exchange, state, code));
        server.start();
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("response_type", "code");
            params.put("client_id", clientId);
            params.put("redirect_uri", redirectUri);
            if (scope != null) params.put("scope", scope);
            params.put("state", state);
            params.put("code_challenge", challenge(verifier));
            params.put("code_challenge_method", "S256");
            onOpen.accept(URI.create(withQuery(authorizationEndpoint.toString(), params)));

            String authorizationCode;
            try {
                authorizationCode = code.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IdentityException("Sign-in timed out: nothing came back from the browser within " + timeout);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IdentityException ie ? ie
                        : new IdentityException("Sign-in failed: " + e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdentityException("Interrupted while waiting for sign-in", e);
            }

            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", authorizationCode);
            form.put("redirect_uri", redirectUri);
            form.put("code_verifier", verifier);
            return tokens.request(form, clock.instant());
        } finally {
            server.stop(0);
        }
    }

    /**
     * The browser, back from the issuer. Only a request carrying this sign-in's {@code state}
     * counts: anything else that reaches the port is answered {@code 400} and changes nothing.
     */
    private static void callback(HttpExchange exchange, String state, CompletableFuture<String> code) throws IOException {
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
        if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                q.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) {
            page(exchange, 400, "This isn't the sign-in this program is waiting for.");
            return;
        }
        if (q.get("error") != null || q.get("code") == null) {
            page(exchange, 400, "Sign-in was not completed. You can close this window.");
            code.completeExceptionally(new IdentityException("Sign-in was denied at the issuer: "
                    + q.getOrDefault("error", "no code")));
            return;
        }
        page(exchange, 200, "Signed in. You can close this window and go back to the terminal.");
        code.complete(q.get("code"));
    }

    private static void page(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Sign-in</title></head>"
                + "<body style=\"font: 16px system-ui, sans-serif; margin: 3rem\"><p>" + message + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void openBrowser(URI uri) {
        System.err.println("Opening your browser to sign in. If it doesn't open, go to:");
        System.err.println("  " + uri);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (IOException | RuntimeException e) {
            // The link is printed above: the user can open it by hand.
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String withQuery(String base, Map<String, String> params) {
        StringBuilder url = new StringBuilder(base).append(base.contains("?") ? '&' : '?');
        params.forEach((k, v) -> url.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        url.setLength(url.length() - 1);
        return url.toString();
    }
}
