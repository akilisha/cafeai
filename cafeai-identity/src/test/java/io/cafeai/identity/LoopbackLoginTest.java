package io.cafeai.identity;

import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoopbackLogin: signing in from a terminal through the local browser (RFC 8252)")
class LoopbackLoginTest {

    private static final String CLIENT = "orders-cli";
    private static final HttpClient BROWSER = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static FakeIssuer fake;

    @TempDir
    Path dir;
    private Path cache;
    private final AtomicReference<URI> opened = new AtomicReference<>();
    private final AtomicReference<HttpResponse<String>> landed = new AtomicReference<>();

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().publicClient(CLIENT);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void setUp() {
        cache = dir.resolve("tokens.json");
        fake.signInAs(null);
    }

    /** The browser: open the link, sign in at the issuer (the fake signs in at once), follow it back. */
    private void browse(URI link) {
        opened.set(link);
        try {
            var atIssuer = BROWSER.send(HttpRequest.newBuilder(link).build(), HttpResponse.BodyHandlers.discarding());
            String back = atIssuer.headers().firstValue("Location").orElseThrow();
            landed.set(BROWSER.send(HttpRequest.newBuilder(URI.create(back)).build(), HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private LoopbackLogin login() {
        return LoopbackLogin.of(fake.issuer(), CLIENT).scope("openid", "orders:read").cache(cache).onOpen(this::browse);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("opens the issuer's sign-in page, takes the code back on 127.0.0.1, and returns the user's token")
    void signsIn() {
        fake.signInAs("alice");
        String token = login().accessToken();

        assertThat(claims(token)).contains("\"sub\":\"alice\"");
        Map<String, String> q = query(opened.get());
        assertThat(opened.get().toString()).startsWith(fake.id() + "/authorize");
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", CLIENT)
                .containsEntry("code_challenge_method", "S256").containsKeys("state", "code_challenge");
        assertThat(q.get("redirect_uri")).matches("http://127\\.0\\.0\\.1:\\d+/callback");
        assertThat(landed.get().statusCode()).isEqualTo(200);
        assertThat(landed.get().body()).contains("You can close this window");
    }

    @Test @DisplayName("signs in once: the next run uses the cached token without opening the browser")
    void cached() {
        fake.signInAs("bob");
        String first = login().accessToken();
        var again = LoopbackLogin.of(fake.issuer(), CLIENT).scope("openid", "orders:read").cache(cache)
                .onOpen(link -> { throw new AssertionError("opened the browser again"); });
        assertThat(again.accessToken()).isEqualTo(first);
    }

    @Test @DisplayName("a request to the port without this sign-in's state is turned away, and the sign-in still completes")
    void wrongStateIgnored() {
        fake.signInAs("carol");
        var login = LoopbackLogin.of(fake.issuer(), CLIENT).noCache().onOpen(link -> {
            String callback = query(link).get("redirect_uri");
            try {
                var stray = BROWSER.send(HttpRequest.newBuilder(URI.create(callback + "?code=planted&state=guessed")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(stray.statusCode()).isEqualTo(400);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            browse(link);
        });
        assertThat(claims(login.accessToken())).contains("\"sub\":\"carol\"");
    }

    @Test @DisplayName("a sign-in refused at the issuer fails, and nothing is cached")
    void denied() {
        fake.signInAs(null);   // the fake refuses: error=access_denied
        assertThatThrownBy(() -> login().accessToken()).isInstanceOf(IdentityException.class).hasMessageContaining("denied");
        assertThat(cache).doesNotExist();
    }

    @Test @DisplayName("nothing back from the browser: the sign-in times out")
    void timesOut() {
        var login = LoopbackLogin.of(fake.issuer(), CLIENT).noCache().timeout(Duration.ofMillis(300)).onOpen(link -> { });
        assertThatThrownBy(login::accessToken).isInstanceOf(IdentityException.class).hasMessageContaining("timed out");
    }

    @Test @DisplayName("signOut revokes the refresh token at the issuer and forgets the sign-in")
    void signOut() {
        fake.signInAs("dave");
        var login = login();
        login.accessToken();
        int before = fake.revocations();
        assertThat(login.signOut()).isEqualTo(SignedOut.REVOKED);
        assertThat(cache).doesNotExist();
        assertThat(fake.revocations() - before).isEqualTo(1);
    }
}
