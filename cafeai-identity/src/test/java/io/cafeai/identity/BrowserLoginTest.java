package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.session.SessionStore;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auth.login: browser sign-in with tokens kept server-side")
class BrowserLoginTest {

    private static final String CLIENT = "orders-web";
    private static final String SECRET = "web-secret";

    private static FakeIssuer fake;
    private CafeAI app;
    private int port;
    private BrowserLogin login;
    private final BearerAuthTest.MutableClock clock = new BearerAuthTest.MutableClock(Instant.now());
    private CookieManager cookies;
    private HttpClient browser;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().client(CLIENT, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void newBrowser() {
        cookies = new CookieManager();
        browser = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        fake.signInAs(null).issuedLifetime(Duration.ofHours(3));
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** An app with a session store, browser sign-in, and a few routes. */
    private void serve(Consumer<BrowserLogin> configure, Consumer<CafeAI> routes) throws Exception {
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        app = CafeAI.create();
        app.filter(Middleware.session(SessionStore.inMemory()));
        login = Auth.login(fake.issuer(), CLIENT, SECRET, base() + "/auth/callback").clock(clock);
        configure.accept(login);
        app.filter(login);
        app.get("/me", (req, res, next) -> res.send(req.identity().map(Identity::subject).orElse("anonymous")));
        app.get("/csrf", (req, res, next) -> res.send(Auth.csrfToken(req).orElse("")));
        app.post("/orders", (req, res, next) -> res.send("ordered"));
        routes.accept(app);
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void serve() throws Exception {
        serve(l -> { }, a -> { });
    }

    private HttpResponse<String> get(String url, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url.startsWith("http") ? url : base() + url)).GET();
        if (headers.length > 0) request.headers(headers);
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).POST(HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) request.headers(headers);
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<?> response) {
        assertThat(response.statusCode()).as("a redirect").isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    /** The browser's whole sign-in: app, issuer, back to the app. Returns where it lands. */
    private String signIn(String subject, String returnTo) throws Exception {
        fake.signInAs(subject);
        String toIssuer = location(get("/auth/login?return=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8)));
        String toCallback = location(get(toIssuer));
        return location(get(toCallback));
    }

    private String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("cafeai.sid") || c.getName().toLowerCase().contains("sid")
                        || c.getName().toLowerCase().contains("session"))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private static Map<String, String> query(String url) {
        String q = URI.create(url).getRawQuery();
        var out = new java.util.HashMap<String, String>();
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    // -- signing in ------------------------------------------------------------------------

    @Test @DisplayName("sign-in: PKCE and state to the issuer, back with the caller signed in, at the return path")
    void signInRoundTrip() throws Exception {
        serve();
        assertThat(get("/me").body()).isEqualTo("anonymous");

        fake.signInAs("alice");
        String toIssuer = location(get("/auth/login?return=%2Fme"));
        Map<String, String> params = query(toIssuer);
        assertThat(toIssuer).startsWith(fake.id() + "/authorize");
        assertThat(params).containsEntry("response_type", "code").containsEntry("client_id", CLIENT)
                .containsEntry("code_challenge_method", "S256").containsKeys("state", "nonce", "code_challenge");
        assertThat(params.get("scope")).contains("openid");

        String landed = location(get(location(get(toIssuer))));
        assertThat(landed).isEqualTo("/me");
        assertThat(get("/me").body()).isEqualTo("alice");
    }

    @Test @DisplayName("tokens never reach the browser: its cookie is only a session id")
    void tokensStayServerSide() throws Exception {
        serve();
        signIn("alice", "/me");
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            assertThat(cookie.getValue()).as(cookie.getName()).hasSizeLessThan(100)
                    .doesNotContain("eyJ");   // the start of every base64url-encoded JWT
        }
    }

    @Test @DisplayName("sign-in starts a new session: an id planted before sign-in stays signed out")
    void sessionFixation() throws Exception {
        serve();
        get("/me");
        String before = sessionCookie();
        assertThat(before).isNotNull();

        signIn("alice", "/me");
        String after = sessionCookie();
        assertThat(after).isNotEqualTo(before);

        var attacker = HttpClient.newHttpClient();
        String asAttacker = attacker.send(HttpRequest.newBuilder(URI.create(base() + "/me"))
                .header("Cookie", cookieName() + "=" + before).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(asAttacker).isEqualTo("anonymous");
    }

    private String cookieName() {
        return cookies.getCookieStore().getCookies().stream().map(HttpCookie::getName).findFirst().orElseThrow();
    }

    @Test @DisplayName("the return path must be on this site: anything else lands on /")
    void noOpenRedirect() throws Exception {
        serve();
        assertThat(signIn("alice", "https://evil.example/steal")).isEqualTo("/");
        assertThat(signIn("alice", "//evil.example")).isEqualTo("/");
    }

    @Test @DisplayName("a callback with the wrong state is refused, and the attempt is used up")
    void wrongState() throws Exception {
        serve();
        fake.signInAs("alice");
        String toCallback = location(get(location(get("/auth/login"))));
        String tampered = toCallback.replaceAll("state=[^&]*", "state=forged");
        assertThat(get(tampered).statusCode()).isEqualTo(400);
        assertThat(get(toCallback).statusCode()).isEqualTo(400);   // the pending sign-in is gone
        assertThat(get("/me").body()).isEqualTo("anonymous");
    }

    @Test @DisplayName("a code can't be replayed into another sign-in attempt")
    void codeReplay() throws Exception {
        serve();
        fake.signInAs("alice");
        String firstCallback = location(get(location(get("/auth/login"))));
        get(firstCallback);
        String code = query(firstCallback).get("code");

        String secondState = query(location(get("/auth/login"))).get("state");
        var replay = get("/auth/callback?code=" + code + "&state=" + secondState);
        assertThat(replay.statusCode()).isEqualTo(401);
    }

    @Test @DisplayName("sign-in refused at the issuer: 401, still anonymous")
    void refusedAtTheIssuer() throws Exception {
        serve();
        fake.signInAs(null);
        var callback = get(location(get(location(get("/auth/login")))));
        assertThat(callback.statusCode()).isEqualTo(401);
        assertThat(get("/me").body()).isEqualTo("anonymous");
    }

    // -- staying signed in -----------------------------------------------------------------

    @Test @DisplayName("near expiry the tokens are renewed with the refresh token, and the caller stays signed in")
    void renewal() throws Exception {
        serve();
        signIn("alice", "/me");
        int before = fake.tokenRequests();

        clock.advance(Duration.ofHours(3).minusSeconds(10));
        assertThat(get("/me").body()).isEqualTo("alice");
        assertThat(fake.tokenRequests() - before).isEqualTo(1);
        assertThat(get("/me").body()).isEqualTo("alice");
        assertThat(fake.tokenRequests() - before).isEqualTo(1);   // renewed once, then current again
    }

    @Test @DisplayName("tokens that can't be renewed sign the caller out")
    void renewalRefused() throws Exception {
        serve();
        signIn("alice", "/me");
        fake.revokeRefreshTokens();
        clock.advance(Duration.ofHours(3).minusSeconds(10));
        assertThat(get("/me").body()).isEqualTo("anonymous");
    }

    // -- CSRF and signing out ----------------------------------------------------------------

    @Test @DisplayName("a signed-in session's state-changing request needs its CSRF token")
    void csrf() throws Exception {
        serve();
        assertThat(post("/orders").body()).isEqualTo("ordered");   // anonymous: nothing to forge

        signIn("alice", "/me");
        assertThat(post("/orders").statusCode()).isEqualTo(403);
        assertThat(post("/orders", "X-CSRF-Token", "wrong").statusCode()).isEqualTo(403);
        String token = get("/csrf").body();
        assertThat(token).isNotBlank();
        assertThat(post("/orders", "X-CSRF-Token", token).body()).isEqualTo("ordered");
    }

    @Test @DisplayName("a request carrying a bearer token isn't cookie-authenticated, so needs no CSRF token")
    void bearerRequestsSkipCsrf() throws Exception {
        serve();
        signIn("alice", "/me");
        assertThat(post("/orders", "Authorization", "Bearer something").body()).isEqualTo("ordered");
    }

    @Test @DisplayName("sign-out: POST with the CSRF token ends the session here, revokes its refresh token, and ends the issuer's session")
    void signOut() throws Exception {
        serve(l -> l.afterSignOut("https://orders.example.com/bye"), a -> { });
        signIn("alice", "/me");
        String token = get("/csrf").body();
        int signOutsBefore = fake.signOuts().size();
        int revokedBefore = fake.revocations();

        assertThat(get("/auth/logout").statusCode()).isEqualTo(405);
        assertThat(post("/auth/logout").statusCode()).isEqualTo(403);

        String toIssuer = location(post("/auth/logout", "X-CSRF-Token", token));
        assertThat(fake.revocations() - revokedBefore).as("refresh token revoked").isEqualTo(1);
        assertThat(toIssuer).startsWith(fake.id() + "/logout");
        assertThat(query(toIssuer)).containsKey("id_token_hint")
                .containsEntry("post_logout_redirect_uri", "https://orders.example.com/bye");
        assertThat(location(get(toIssuer))).isEqualTo("https://orders.example.com/bye");
        assertThat(fake.signOuts()).hasSize(signOutsBefore + 1);
        assertThat(get("/me").body()).isEqualTo("anonymous");
    }

    // -- options ----------------------------------------------------------------------------

    @Test @DisplayName("signInRequired: a browser is sent to sign in and back; an API call gets 401")
    void signInRequired() throws Exception {
        serve(BrowserLogin::signInRequired, a -> { });
        String toLogin = location(get("/me", "Accept", "text/html"));
        assertThat(toLogin).isEqualTo("/auth/login?return=%2Fme");
        assertThat(get("/me", "Accept", "application/json").statusCode()).isEqualTo(401);

        assertThat(signIn("bob", "/me")).isEqualTo("/me");
        assertThat(get("/me", "Accept", "text/html").body()).isEqualTo("bob");
    }

    @Test @DisplayName("without a server-side session store, sign-in refuses to start")
    void needsASessionStore() throws Exception {
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        app = CafeAI.create();
        app.filter(Auth.login(fake.issuer(), CLIENT, SECRET, base() + "/auth/callback"));
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(get("/auth/login").statusCode()).isEqualTo(500);
    }

    @Test @DisplayName("a model call from a signed-in browser is made on that caller's behalf")
    void tokenExchangeFromABrowserSession() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(l -> { }, a -> {
                a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl())
                        .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server")));
                a.get("/ask", (req, res, next) -> res.send(a.prompt("hi").call().text()));
            });
            signIn("carol", "/ask");
            assertThat(get("/ask").body()).isEqualTo("ok");
            String claims = new String(java.util.Base64.getUrlDecoder()
                    .decode(model.calls.get(0).bearer().split("\\.")[1]), StandardCharsets.UTF_8);
            assertThat(claims).contains("\"sub\":\"carol\"").contains("model-server");
        }
    }
}
