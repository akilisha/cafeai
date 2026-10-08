package io.cafeai.core.identity;

import io.cafeai.core.CafeAI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Identity")
class IdentityTest {

    private static Identity alice() {
        return Identity.builder("https://issuer.example.com", "alice")
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .scopes(List.of("orders:read"))
                .claims(Map.of("email", "alice@example.com"))
                .build();
    }

    @Test @DisplayName("is keyed by issuer and subject together")
    void key() {
        var other = Identity.builder("https://other.example.com", "alice")
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z")).build();
        assertThat(alice().key()).isEqualTo(alice().key());
        assertThat(alice().key()).isNotEqualTo(other.key());
    }

    @Test @DisplayName("toString names the issuer and subject only, never the claims")
    void toStringHidesClaims() {
        assertThat(alice().toString()).contains("alice").doesNotContain("alice@example.com");
    }

    @Test @DisplayName("requires an issuer, a subject and an expiry; claims are unmodifiable")
    void validation() {
        assertThatThrownBy(() -> Identity.builder("", "alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identity.builder("https://issuer.example.com", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identity.builder("https://issuer.example.com", "alice").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> alice().claims().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("expired() compares against the token's expiry")
    void expired() {
        assertThat(alice().expired(Instant.parse("2029-12-31T23:59:59Z"))).isFalse();
        assertThat(alice().expired(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();
    }

    @Test @DisplayName("current() and req.identity() are empty without identity middleware")
    void emptyWithoutMiddleware() throws Exception {
        assertThat(Identity.current()).isEmpty();
        var app = CafeAI.create();
        app.get("/", (req, res, next) ->
                res.send(req.identity().isEmpty() + "," + Identity.current().isEmpty()));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(body).isEqualTo("true,true");
        } finally {
            app.stop();
        }
    }
}
