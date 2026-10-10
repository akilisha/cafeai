package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DeviceLogin: signing in from a terminal (RFC 8628)")
class DeviceLoginTest {

    private static final String CLIENT = "orders-cli";

    private static FakeIssuer fake;

    @TempDir
    Path dir;
    private Path cache;
    private final BearerAuthTest.MutableClock clock = new BearerAuthTest.MutableClock(Instant.now());
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicReference<DeviceLogin.Prompt> prompt = new AtomicReference<>();

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
        fake.deviceCodeLifetime(Duration.ofMinutes(10)).issuedLifetime(Duration.ofHours(1));
    }

    /** A login whose user approves as {@code subject} while the CLI waits its {@code onPoll}th wait. */
    private DeviceLogin login(String subject, int onPoll) {
        return DeviceLogin.of(fake.issuer(), CLIENT).scope("openid", "orders:read").cache(cache).clock(clock)
                .onPrompt(prompt::set)
                .sleeper(d -> {
                    sleeps.add(d);
                    if (sleeps.size() == onPoll && subject != null) fake.approveDevice(prompt.get().userCode(), subject);
                });
    }

    private static String subjectOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("shows a code and a link, waits for the user, and returns their token")
    void signsIn() {
        String token = login("alice", 1).accessToken();

        assertThat(prompt.get().userCode()).matches("[A-Z]{4}-[A-Z]{4}");
        assertThat(prompt.get().verificationUri().toString()).isEqualTo(fake.id() + "/device");
        assertThat(prompt.get().verificationUriComplete().toString()).contains(prompt.get().userCode());
        assertThat(subjectOf(token)).contains("\"sub\":\"alice\"");
    }

    @Test @DisplayName("keeps polling while sign-in is pending, at the interval the issuer asked for")
    void pollsWhilePending() {
        login("bob", 3).accessToken();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThat(fake.devicePolls(prompt.get().userCode())).isEqualTo(3);
    }

    @Test @DisplayName("slow_down: every later poll waits five seconds longer")
    void slowsDown() {
        fake.slowDownNextPoll();
        login("carol", 3).accessToken();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10));
    }

    @Test @DisplayName("a refused sign-in fails, and nothing is cached")
    void denied() {
        var login = DeviceLogin.of(fake.issuer(), CLIENT).cache(cache).clock(clock).onPrompt(prompt::set)
                .sleeper(d -> fake.denyDevice(prompt.get().userCode()));
        assertThatThrownBy(login::accessToken).isInstanceOf(IdentityException.class).hasMessageContaining("denied");
        assertThat(cache).doesNotExist();
    }

    @Test @DisplayName("a code that expires before it is used fails")
    void expired() {
        fake.deviceCodeLifetime(Duration.ZERO);
        assertThatThrownBy(() -> login(null, 1).accessToken())
                .isInstanceOf(IdentityException.class).hasMessageContaining("expired");
    }

    @Test @DisplayName("signs in once: the next run uses the cached token without asking")
    void cached() {
        String first = login("dave", 1).accessToken();
        int before = fake.tokenRequests();

        var again = DeviceLogin.of(fake.issuer(), CLIENT).scope("openid", "orders:read").cache(cache).clock(clock)
                .onPrompt(p -> { throw new AssertionError("asked to sign in again"); });
        assertThat(again.accessToken()).isEqualTo(first);
        assertThat(fake.tokenRequests()).isEqualTo(before);
    }

    @Test @DisplayName("an expired token is renewed with the refresh token, without asking the user")
    void renews() {
        String first = login("erin", 1).accessToken();
        int before = fake.tokenRequests();
        clock.advance(Duration.ofHours(2));

        var later = DeviceLogin.of(fake.issuer(), CLIENT).scope("openid", "orders:read").cache(cache).clock(clock)
                .onPrompt(p -> { throw new AssertionError("asked to sign in again"); });
        String renewed = later.accessToken();
        assertThat(renewed).isNotEqualTo(first);
        assertThat(subjectOf(renewed)).contains("\"sub\":\"erin\"");
        assertThat(fake.tokenRequests() - before).isEqualTo(1);
    }

    @Test @DisplayName("a sign-in that can't be renewed asks the user again")
    void renewalRefused() {
        login("frank", 1).accessToken();
        fake.revokeRefreshTokens();
        clock.advance(Duration.ofHours(2));
        prompt.set(null);
        sleeps.clear();

        assertThat(subjectOf(login("frank", 1).accessToken())).contains("\"sub\":\"frank\"");
        assertThat(prompt.get()).as("asked to sign in again").isNotNull();
    }

    @Test @DisplayName("signOut revokes the refresh token at the issuer and forgets the cached sign-in")
    void signOut() throws Exception {
        var login = login("grace", 1);
        login.accessToken();
        assertThat(cache).exists();
        Path copy = dir.resolve("stolen.json");
        Files.copy(cache, copy);
        int revokedBefore = fake.revocations();

        assertThat(login.signOut()).isEqualTo(DeviceLogin.SignedOut.REVOKED);
        assertThat(cache).doesNotExist();
        assertThat(fake.revocations() - revokedBefore).isEqualTo(1);

        // A copy of the cache made before sign-out can't renew: the user is asked to sign in again.
        Files.copy(copy, cache);
        clock.advance(Duration.ofHours(2));
        prompt.set(null);
        sleeps.clear();
        assertThat(subjectOf(login("grace", 1).accessToken())).contains("\"sub\":\"grace\"");
        assertThat(prompt.get()).as("asked to sign in again").isNotNull();
    }

    @Test @DisplayName("signOut with nothing cached says so")
    void signOutNotSignedIn() {
        assertThat(login("heidi", 1).signOut()).isEqualTo(DeviceLogin.SignedOut.NOT_SIGNED_IN);
    }

    @Test @DisplayName("signOut while the issuer is unreachable still forgets the sign-in here")
    void signOutIssuerDown() {
        var gone = FakeIssuer.start().publicClient(CLIENT);
        var login = DeviceLogin.of(gone.issuer(), CLIENT).cache(cache).clock(clock).onPrompt(prompt::set)
                .sleeper(d -> gone.approveDevice(prompt.get().userCode(), "ivan"));
        login.accessToken();
        gone.close();

        assertThat(login.signOut()).isEqualTo(DeviceLogin.SignedOut.FORGOTTEN);
        assertThat(cache).doesNotExist();
    }

    @Test @DisplayName("the cache file is readable by its owner only, where the file system has permissions")
    void ownerOnly() throws Exception {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "no POSIX permissions on this file system");
        login("heidi", 1).accessToken();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(cache))).isEqualTo("rw-------");
    }

    @Test @DisplayName("the token is accepted by a CafeAI service that trusts the issuer")
    void callsACafeAiService() throws Exception {
        var app = CafeAI.create();
        app.filter(Auth.bearer(fake.issuer(), CLIENT));
        app.get("/me", (req, res, next) -> res.send(req.identity().map(Identity::subject).orElse("anonymous")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            String token = login("ivan", 1).accessToken();
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/me"))
                            .header("Authorization", "Bearer " + token).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(body).isEqualTo("ivan");
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("a client the issuer doesn't know is refused, before anyone is asked to sign in")
    void unknownClient() {
        var unknown = DeviceLogin.of(fake.issuer(), "not-registered").noCache()
                .onPrompt(p -> { throw new AssertionError("prompted for an unknown client"); });
        assertThatThrownBy(unknown::accessToken).isInstanceOf(IdentityException.class)
                .hasMessageContaining("invalid_client");
    }
}
