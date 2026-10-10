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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Several issuers at once: employees through one, partners through another")
class SeveralIssuersTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static FakeIssuer staff;      // e.g. the company's own identity provider
    private static FakeIssuer partners;   // e.g. a partner's
    private CafeAI app;
    private String base;

    @BeforeAll
    static void startIssuers() {
        staff = FakeIssuer.start().client("orders-api", "staff-secret").client("orders-web", "staff-web");
        partners = FakeIssuer.start().client("partner-api", "partner-secret").client("partner-web", "partner-web-secret");
    }

    @AfterAll
    static void stopIssuers() {
        staff.close();
        partners.close();
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    private void serve(Consumer<CafeAI> configure) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://localhost:" + port;
        app = CafeAI.create();
        configure.accept(app);
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void whoRoute(CafeAI a) {
        a.get("/me", (req, res, next) -> res.send(req.identity()
                .map(who -> (who.issuer().equals(staff.id()) ? "staff:" : "partners:") + who.subject())
                .orElse("anonymous")));
    }

    // -- APIs ------------------------------------------------------------------------------

    @Test @DisplayName("each issuer's tokens are accepted for its own audience, and the same subject stays two callers")
    void eachIssuerItsOwnAudience() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(staff.issuer(), "orders-api").or(partners.issuer(), "partner-api"));
            whoRoute(a);
        });
        assertThat(get("/me", staff.token().subject("alice").audience("orders-api").sign()).body()).isEqualTo("staff:alice");
        assertThat(get("/me", partners.token().subject("alice").audience("partner-api").sign()).body()).isEqualTo("partners:alice");

        var crossed = get("/me", partners.token().subject("alice").audience("orders-api").sign());
        assertThat(crossed.statusCode()).as("the partners' issuer naming the staff audience").isEqualTo(401);
        assertThat(crossed.headers().firstValue("WWW-Authenticate").orElse("")).contains("not intended for this service");
    }

    @Test @DisplayName("a token from an issuer not trusted, or claiming one issuer but signed by another, is refused")
    void untrustedOrForged() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(staff.issuer(), "orders-api").or(partners.issuer(), "partner-api"));
            whoRoute(a);
        });
        try (var stranger = FakeIssuer.start()) {
            var refused = get("/me", stranger.token().subject("mallory").audience("orders-api").sign());
            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.headers().firstValue("WWW-Authenticate").orElse("")).contains("different issuer");
        }
        // Signed with the partners' key, claiming to be from the staff issuer: checked against the staff keys.
        String forged = partners.token().issuer(staff.id()).subject("mallory").audience("orders-api").sign();
        assertThat(get("/me", forged).statusCode()).isEqualTo(401);
    }

    @Test @DisplayName("an opaque token goes to the issuers asked about tokens, and to no other")
    void opaqueToTheIntrospectingIssuer() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(staff.issuer(), "orders-api")
                    .or(partners.issuer(), "partner-api").introspect("partner-api", "partner-secret"));
            whoRoute(a);
        });
        assertThat(get("/me", partners.opaqueToken("bob", "partner-api")).body()).isEqualTo("partners:bob");
        assertThat(get("/me", staff.opaqueToken("carol", "orders-api")).statusCode())
                .as("the staff issuer isn't asked about tokens here").isEqualTo(401);
    }

    @Test @DisplayName("the resource metadata names every issuer; trusting one twice is a mistake")
    void metadataAndDuplicates() throws Exception {
        serve(a -> {
            a.filter(Auth.bearer(staff.issuer(), "orders-api").or(partners.issuer(), "partner-api")
                    .resourceMetadata(a, "http://localhost:1"));
            whoRoute(a);
        });
        assertThat(get("/.well-known/oauth-protected-resource", null).body())
                .contains("\"authorization_servers\":[\"" + staff.id() + "\",\"" + partners.id() + "\"]");
        assertThatThrownBy(() -> Auth.bearer(staff.issuer(), "orders-api").or(staff.issuer(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- model calls on the caller's behalf ----------------------------------------------------

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("byIssuer: each caller's token is exchanged at the issuer that issued it")
    void exchangeAtTheCallersIssuer() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(a -> {
                a.filter(Auth.bearer(staff.issuer(), "orders-api").or(partners.issuer(), "partner-api"));
                a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(OAuthCredentials.byIssuer(
                        OAuthCredentials.tokenExchange(staff.issuer(), "orders-api", "staff-secret", "model-server"),
                        OAuthCredentials.tokenExchange(partners.issuer(), "partner-api", "partner-secret", "model-server"))));
                a.get("/ask", (req, res, next) -> res.send(a.prompt("hi").call().text()));
            });
            assertThat(get("/ask", staff.token().subject("alice").audience("orders-api").sign()).body()).isEqualTo("ok");
            assertThat(get("/ask", partners.token().subject("bob").audience("partner-api").sign()).body()).isEqualTo("ok");

            assertThat(claims(model.calls.get(0).bearer())).contains("\"sub\":\"alice\"").contains("\"iss\":\"" + staff.id() + "\"");
            assertThat(claims(model.calls.get(1).bearer())).contains("\"sub\":\"bob\"").contains("\"iss\":\"" + partners.id() + "\"");
        }
    }

    @Test @DisplayName("a token exchange at one issuer refuses a caller from another: the token never goes to the wrong issuer")
    void exchangeRefusesAnotherIssuersCaller() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(a -> {
                a.filter(Auth.bearer(staff.issuer(), "orders-api").or(partners.issuer(), "partner-api"));
                a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl()).withCredentials(
                        OAuthCredentials.tokenExchange(staff.issuer(), "orders-api", "staff-secret", "model-server")));
                a.get("/ask", (req, res, next) -> res.send(a.prompt("hi").call().text()));
            });
            int atStaff = staff.tokenRequests();
            var refused = get("/ask", partners.token().subject("bob").audience("partner-api").sign());
            assertThat(refused.statusCode()).isEqualTo(500);
            assertThat(model.calls).isEmpty();
            assertThat(staff.tokenRequests()).as("the partner's token was never sent to the staff issuer").isEqualTo(atStaff);
        }
    }

    // -- browser sign-in -------------------------------------------------------------------------

    @Test @DisplayName("two sign-ins side by side: a partner signs in with theirs, and the staff one leaves the session alone")
    void twoBrowserSignIns() throws Exception {
        serve(a -> {
            a.filter(Middleware.session(SessionStore.inMemory()));
            a.filter(Auth.login(partners.issuer(), "partner-web", "partner-web-secret", base + "/auth/callback/partner")
                    .loginPath("/auth/login/partner").logoutPath("/auth/logout/partner"));
            // The one that requires sign-in goes last, so the other's own paths are handled before it.
            a.filter(Auth.login(staff.issuer(), "orders-web", "staff-web", base + "/auth/callback").signInRequired());
            whoRoute(a);
            a.get("/csrf", (req, res, next) -> res.send(Auth.csrfToken(req).orElse("")));
        });
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        partners.signInAs("pat");
        // Tokens this short are renewed on every request: by the partners' sign-in, never the staff one.
        partners.issuedLifetime(java.time.Duration.ofSeconds(20));
        String toIssuer = browser.send(HttpRequest.newBuilder(URI.create(base + "/auth/login/partner?return=/me")).build(),
                HttpResponse.BodyHandlers.discarding()).headers().firstValue("Location").orElseThrow();
        assertThat(toIssuer).startsWith(partners.id());
        String back = browser.send(HttpRequest.newBuilder(URI.create(toIssuer)).build(), HttpResponse.BodyHandlers.discarding())
                .headers().firstValue("Location").orElseThrow();
        browser.send(HttpRequest.newBuilder(URI.create(back)).build(), HttpResponse.BodyHandlers.discarding());

        // The staff sign-in requires sign-in, but this browser is signed in already, with the partners'.
        var me = browser.send(HttpRequest.newBuilder(URI.create(base + "/me")).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualTo("partners:pat");

        String csrf = browser.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        var out = browser.send(HttpRequest.newBuilder(URI.create(base + "/auth/logout/partner")).header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(out.headers().firstValue("Location").orElseThrow()).startsWith(partners.id() + "/logout");
        assertThat(browser.send(HttpRequest.newBuilder(URI.create(base + "/me")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).as("signed out: the staff sign-in now requires it")
                .isEqualTo(401);
        partners.issuedLifetime(java.time.Duration.ofHours(1));
    }
}
