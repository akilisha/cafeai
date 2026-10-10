package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.session.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every flow against a real issuer: Keycloak, an open-source, OpenID-certified identity provider,
 * configured by {@code keycloak/cafeai-realm.json}. Skipped where Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Against Keycloak: every flow with a real issuer")
class KeycloakIntegrationTest {

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/cafeai-realm.json"),
                    "/opt/keycloak/data/import/cafeai-realm.json")
            .withExposedPorts(8080)
            .withAccessToHost(true)   // so Keycloak can call the app back (back-channel logout)
            .waitingFor(Wait.forHttp("/realms/cafeai/.well-known/openid-configuration").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static String issuerId;
    private static Issuer issuer;
    private CafeAI app;
    private int port;

    @BeforeAll
    static void discover() {
        issuerId = "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/realms/cafeai";
        issuer = Issuer.discover(issuerId);
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    private void serve(Consumer<CafeAI> configure) throws Exception {
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        app = CafeAI.create();
        configure.accept(app);
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** A user's access token, straight from Keycloak (the password grant, for tests only). */
    private static String userToken(String user, String password) throws Exception {
        String body = "grant_type=password&scope=openid&username=" + user + "&password=" + password;
        String basic = Base64.getEncoder().encodeToString("test-direct:direct-secret".getBytes(StandardCharsets.UTF_8));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(issuerId + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return field(response.body(), "access_token");
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as(name + " in " + json).isTrue();
        return m.group(1);
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static String get(HttpClient client, String url, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    // -- APIs: bearer tokens, require, token exchange, client credentials --------------------------

    @Test @DisplayName("bearer: a Keycloak token reaches the handler as the caller, with groups and name")
    void bearer() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(issuer, "orders-api"));
            a.get("/me", (req, res, next) -> {
                Identity who = req.identity().orElseThrow();
                res.send(who.name().orElse("?") + "|" + who.inGroup("finance") + "|" + who.issuer().equals(issuerId));
            });
            a.get("/finance", Auth.require(Auth.group("finance")), (req, res, next) -> res.send("ledger"));
        });
        var http = HttpClient.newHttpClient();
        String alice = userToken("alice", "alice-pw");
        assertThat(get(http, base() + "/me", "Authorization", "Bearer " + alice)).isEqualTo("Alice Liddell|true|true");
        assertThat(get(http, base() + "/finance", "Authorization", "Bearer " + alice)).isEqualTo("ledger");

        var bobFinance = http.send(HttpRequest.newBuilder(URI.create(base() + "/finance"))
                .header("Authorization", "Bearer " + userToken("bob", "bob-pw")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bobFinance.statusCode()).isEqualTo(403);
    }

    @Test @DisplayName("introspection (RFC 7662): a token Keycloak revoked is refused at once, before it expires")
    void introspection() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(issuer, "orders-api").introspect("orders-api", "api-secret")
                    .introspectionCache(Duration.ZERO));
            a.get("/me", (req, res, next) -> res.send(req.identity().flatMap(Identity::name).orElse("?")));
        });
        var http = HttpClient.newHttpClient();
        String alice = userToken("alice", "alice-pw");
        assertThat(get(http, base() + "/me", "Authorization", "Bearer " + alice)).isEqualTo("Alice Liddell");

        // The client it was issued to revokes it (RFC 7009): still signed and unexpired, no longer active.
        assertThat(TokenEndpoint.confidential(issuer, "test-direct", "direct-secret").revoke(alice, "access_token")).isTrue();
        var refused = http.send(HttpRequest.newBuilder(URI.create(base() + "/me"))
                .header("Authorization", "Bearer " + alice).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h ->
                assertThat(h).contains("no longer active"));
    }

    @Test @DisplayName("token exchange (RFC 8693): the model endpoint gets a Keycloak token for the caller, issued for it")
    void tokenExchange() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(a -> {
                a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(
                        OAuthCredentials.tokenExchange(issuer, "orders-api", "api-secret", "model-server")));
                a.filter(Auth.bearer(issuer, "orders-api"));
                a.get("/ask", (req, res, next) -> res.send(a.prompt("hi").call().text()));
            });
            String alice = userToken("alice", "alice-pw");
            assertThat(get(HttpClient.newHttpClient(), base() + "/ask", "Authorization", "Bearer " + alice)).isEqualTo("ok");

            String exchanged = claims(model.calls.get(0).bearer());
            assertThat(exchanged).contains("\"preferred_username\":\"alice\"").contains("model-server")
                    .contains("\"azp\":\"orders-api\"");
            assertThat(field(exchanged, "sub")).isEqualTo(field(claims(alice), "sub"));
        }
    }

    @Test @DisplayName("client credentials: the model endpoint gets the app's own Keycloak token")
    void clientCredentials() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(a -> {
                a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(
                        OAuthCredentials.clientCredentials(issuer, "orders-api", "api-secret")));
                a.get("/ask", (req, res, next) -> res.send(a.prompt("hi").call().text()));
            });
            assertThat(get(HttpClient.newHttpClient(), base() + "/ask")).isEqualTo("ok");
            assertThat(claims(model.calls.get(0).bearer())).contains("\"azp\":\"orders-api\"")
                    .contains("service-account-orders-api");
        }
    }

    // -- Browser sign-in through Keycloak's login page --------------------------------------------

    /** Submits Keycloak's login form on {@code page} and returns where Keycloak sends the browser. */
    private static String submitLogin(HttpClient browser, String page, String user, String password) throws Exception {
        String html = browser.send(HttpRequest.newBuilder(URI.create(page)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher action = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"").matcher(html);
        if (!action.find()) action = Pattern.compile("action=\"([^\"]+)\"").matcher(html);
        assertThat(action.find(0)).as("a login form in " + html).isTrue();
        String target = action.group(1).replace("&amp;", "&");
        var response = browser.send(HttpRequest.newBuilder(URI.create(target))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + password
                        + "&credentialId=")).build(), HttpResponse.BodyHandlers.ofString());
        return response.headers().firstValue("Location").orElse(response.body());
    }

    private static String location(HttpResponse<?> response) {
        assertThat(response.statusCode()).as("a redirect").isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    /** An admin token for Keycloak's own admin API (the bootstrap admin, in the master realm). */
    private static String adminToken() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=admin&password=admin"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return field(response.body(), "access_token");
    }

    private static HttpResponse<String> admin(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + KEYCLOAK.getMappedPort(8080)
                        + "/admin/realms/cafeai" + path))
                .header("Authorization", "Bearer " + adminToken())
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @DisplayName("back-channel logout: an administrator ends alice's Keycloak session, and Keycloak ends the app's")
    void backChannelLogout() throws Exception {
        serve(a -> {
            a.filter(Middleware.session(SessionStore.inMemory()));
            a.filter(Auth.login(issuer, "orders-web", "web-secret", "http://localhost:" + port + "/auth/callback")
                    .backChannelLogout(a));
            a.get("/me", (req, res, next) -> res.send(req.identity().flatMap(Identity::name).orElse("anonymous")));
        });
        // Where Keycloak, in its container, reaches this app: registered as the client's back-channel URL.
        org.testcontainers.Testcontainers.exposeHostPorts(port);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var client = json.readTree(admin("GET", "/clients?clientId=orders-web", null).body()).get(0);
        var attributes = (com.fasterxml.jackson.databind.node.ObjectNode) client.get("attributes");
        attributes.put("backchannel.logout.url", "http://host.testcontainers.internal:" + port + "/auth/backchannel-logout");
        attributes.put("backchannel.logout.session.required", "true");
        assertThat(admin("PUT", "/clients/" + client.get("id").asText(), json.writeValueAsString(client)).statusCode())
                .isEqualTo(204);

        var browser = HttpClient.newBuilder().cookieHandler(new LocalhostCookieJar())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String toKeycloak = location(browser.send(HttpRequest.newBuilder(URI.create(base() + "/auth/login?return=/me"))
                .build(), HttpResponse.BodyHandlers.discarding()));
        String toCallback = submitLogin(browser, toKeycloak, "alice", "alice-pw");
        browser.send(HttpRequest.newBuilder(URI.create(toCallback)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(get(browser, base() + "/me")).isEqualTo("Alice Liddell");

        // An administrator signs alice out of Keycloak; Keycloak tells every client she used.
        String aliceId = json.readTree(admin("GET", "/users?username=alice&exact=true", null).body()).get(0).get("id").asText();
        assertThat(admin("POST", "/users/" + aliceId + "/logout", null).statusCode()).isEqualTo(204);

        long deadline = System.currentTimeMillis() + 10_000;
        String who = get(browser, base() + "/me");
        while (!who.equals("anonymous") && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            who = get(browser, base() + "/me");
        }
        assertThat(who).as("signed out by Keycloak's back-channel logout").isEqualTo("anonymous");
    }

    @Test @DisplayName("browser sign-in: Keycloak's login page, back signed in; renewal; sign-out at Keycloak")
    void browserSignIn() throws Exception {
        var clock = new BearerAuthTest.MutableClock(java.time.Instant.now());
        serve(a -> {
            a.filter(Middleware.session(SessionStore.inMemory()));
            a.filter(Auth.login(issuer, "orders-web", "web-secret", "http://localhost:" + port + "/auth/callback")
                    .scope("openid", "profile").afterSignOut("http://localhost:" + port + "/bye").clock(clock));
            a.get("/me", (req, res, next) -> res.send(req.identity().flatMap(Identity::name).orElse("anonymous")));
            a.get("/csrf", (req, res, next) -> res.send(Auth.csrfToken(req).orElse("")));
        });
        var browser = HttpClient.newBuilder().cookieHandler(new LocalhostCookieJar())
                .followRedirects(HttpClient.Redirect.NEVER).build();

        String toKeycloak = location(browser.send(HttpRequest.newBuilder(URI.create(base() + "/auth/login?return="
                + URLEncoder.encode("/me", StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.discarding()));
        assertThat(toKeycloak).startsWith(issuerId + "/protocol/openid-connect/auth");
        String toCallback = submitLogin(browser, toKeycloak, "alice", "alice-pw");
        assertThat(toCallback).startsWith(base() + "/auth/callback");
        String landed = location(browser.send(HttpRequest.newBuilder(URI.create(toCallback)).build(),
                HttpResponse.BodyHandlers.discarding()));
        assertThat(landed).isEqualTo("/me");
        assertThat(get(browser, base() + "/me")).isEqualTo("Alice Liddell");

        // Keycloak's access tokens last five minutes: near the end, the session renews them.
        clock.advance(Duration.ofMinutes(5).minusSeconds(10));
        assertThat(get(browser, base() + "/me")).isEqualTo("Alice Liddell");

        String csrf = get(browser, base() + "/csrf");
        String toEndSession = location(browser.send(HttpRequest.newBuilder(URI.create(base() + "/auth/logout"))
                .header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()));
        assertThat(toEndSession).startsWith(issuerId + "/protocol/openid-connect/logout");
        var atKeycloak = browser.send(HttpRequest.newBuilder(URI.create(toEndSession)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(atKeycloak.headers().firstValue("Location")).hasValue("http://localhost:" + port + "/bye");
        assertThat(get(browser, base() + "/me")).isEqualTo("anonymous");
    }

    // -- Terminal sign-in with the device grant ---------------------------------------------------

    @Test @DisplayName("device grant (RFC 8628): a code, Keycloak's device page, and the CLI gets the user's token")
    void deviceGrant() throws Exception {
        var browser = HttpClient.newBuilder().cookieHandler(new LocalhostCookieJar())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var lastPage = new java.util.concurrent.atomic.AtomicReference<String>("");
        java.nio.file.Path cache = java.nio.file.Files.createTempDirectory("cafeai-device").resolve("tokens.json");
        var login = DeviceLogin.of(issuer, "orders-cli").scope("openid").cache(cache)
                .onPrompt(prompt -> {
                    // The user, on another device: open the link, sign in, approve.
                    try {
                        lastPage.set(approveOnDevicePage(browser, prompt.verificationUriComplete().toString(),
                                "bob", "bob-pw"));
                    } catch (Exception e) {
                        throw new AssertionError("could not approve on Keycloak's device page", e);
                    }
                })
                .sleeper(d -> Thread.sleep(200));
        // Bounded, so a page this test doesn't understand fails fast instead of polling to expiry.
        String token;
        try {
            token = java.util.concurrent.CompletableFuture.supplyAsync(login::accessToken).get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("no sign-in within 60s; Keycloak's last page was:\n" + lastPage.get(), e);
        }
        assertThat(claims(token)).contains("\"preferred_username\":\"bob\"").contains("\"azp\":\"orders-cli\"");

        // Sign-out revokes the refresh token at Keycloak (RFC 7009): a copy kept from before can't renew.
        String refreshToken = new String(java.nio.file.Files.readAllBytes(cache), StandardCharsets.UTF_8)
                .replaceAll("(?s).*\"refresh_token\":\"([^\"]+)\".*", "$1");
        assertThat(login.signOut()).isEqualTo(DeviceLogin.SignedOut.REVOKED);
        assertThat(cache).doesNotExist();
        Map<String, String> renew = new java.util.LinkedHashMap<>();
        renew.put("grant_type", "refresh_token");
        renew.put("refresh_token", refreshToken);
        assertThatThrownBy(() -> TokenEndpoint.publicClient(issuer, "orders-cli").request(renew, java.time.Instant.now()))
                .isInstanceOf(TokenEndpoint.Refused.class).hasMessageContaining("invalid_grant");
    }

    /**
     * Walks Keycloak's device pages as a person would: the code (when asked), the login form, the
     * consent form. Returns the final page, which says whether the device was approved.
     */
    private static String approveOnDevicePage(HttpClient browser, String url, String user, String password)
            throws Exception {
        String current = url;
        HttpResponse<String> response = browser.send(HttpRequest.newBuilder(URI.create(current)).build(),
                HttpResponse.BodyHandlers.ofString());
        for (int step = 0; step < 12; step++) {
            if (response.statusCode() == 302 || response.statusCode() == 303) {
                current = absolute(current, response.headers().firstValue("Location").orElseThrow());
                response = browser.send(HttpRequest.newBuilder(URI.create(current)).build(),
                        HttpResponse.BodyHandlers.ofString());
                continue;
            }
            String html = response.body();
            Matcher action = Pattern.compile("<form[^>]*action=\"([^\"]+)\"").matcher(html);
            String form;
            if (html.contains("name=\"password\"")) {
                form = "username=" + user + "&password=" + password + "&credentialId=";
            } else if (html.contains("name=\"accept\"")) {
                form = "accept=Yes";
            } else {
                return html;   // no form to fill: the final page
            }
            assertThat(action.find()).as("a form on " + html).isTrue();
            current = absolute(current, action.group(1).replace("&amp;", "&"));
            response = browser.send(HttpRequest.newBuilder(URI.create(current))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        }
        return response.body();
    }

    private static String absolute(String base, String location) {
        return URI.create(base).resolve(location).toString();
    }
}
