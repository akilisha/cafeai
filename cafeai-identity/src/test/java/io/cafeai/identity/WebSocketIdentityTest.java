package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebSockets: the caller who opened the connection, until their identity expires")
class WebSocketIdentityTest {

    private static final String AUDIENCE = "chat-api";
    private static final String CLIENT = "chat-api";
    private static final String SECRET = "s3cret";

    private static FakeIssuer fake;
    private CafeAI app;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start().client(CLIENT, SECRET);
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    /** A chat socket that answers with who the session says opened it, and who Identity.current() says. */
    private void serve(BearerAuth auth, Consumer<CafeAI> more) throws Exception {
        app = CafeAI.create();
        more.accept(app);
        app.filter(auth);
        app.ws("/chat", new WsHandler() {
            @Override public void onMessage(WsSession session, String message) {
                if (message.equals("ask")) {
                    session.send(app.prompt("hi").call().text());
                    return;
                }
                session.send(session.identity().map(Identity::subject).orElse("anonymous") + "|"
                        + Identity.current().map(Identity::subject).orElse("anonymous"));
            }
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    /** A client socket that queues what it receives and records how it was closed. */
    private static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closedWith = new CompletableFuture<>();

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            received.add(data.toString());
            ws.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closedWith.complete(statusCode);
            return null;
        }
    }

    private WebSocket connect(String token, Client client) throws Exception {
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.buildAsync(URI.create("ws://localhost:" + app.port() + "/chat"), client).get(5, TimeUnit.SECONDS);
    }

    private static String reply(WebSocket ws, Client client, String message) throws Exception {
        ws.sendText(message, true);
        String reply = client.received.poll(5, TimeUnit.SECONDS);
        assertThat(reply).as("a reply to " + message).isNotNull();
        return reply;
    }

    @Test @DisplayName("the session and every callback see the caller the upgrade request was verified as")
    void callerInCallbacks() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE), a -> { });
        var client = new Client();
        var ws = connect(fake.token().subject("alice").audience(AUDIENCE).sign(), client);
        assertThat(reply(ws, client, "who")).isEqualTo("alice|alice");
        assertThat(reply(ws, client, "who")).isEqualTo("alice|alice");
    }

    @Test @DisplayName("the upgrade goes through the app's filters: no token, no connection")
    void upgradeNeedsAToken() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE), a -> { });
        assertThatThrownBy(() -> connect(null, new Client())).isInstanceOf(ExecutionException.class);
    }

    @Test @DisplayName("an anonymous connection, where the filters allow one, has no identity")
    void anonymous() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE).optional(), a -> { });
        var client = new Client();
        var ws = connect(null, client);
        assertThat(reply(ws, client, "who")).isEqualTo("anonymous|anonymous");
    }

    @Test @DisplayName("each connection keeps its own caller")
    void separateConnections() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE), a -> { });
        var a = new Client();
        var b = new Client();
        var wsA = connect(fake.token().subject("alice").audience(AUDIENCE).sign(), a);
        var wsB = connect(fake.token().subject("bob").audience(AUDIENCE).sign(), b);
        assertThat(reply(wsA, a, "who")).isEqualTo("alice|alice");
        assertThat(reply(wsB, b, "who")).isEqualTo("bob|bob");
        assertThat(reply(wsA, a, "who")).isEqualTo("alice|alice");
    }

    @Test @DisplayName("when the identity expires, the connection is closed with 1008")
    void closedAtExpiry() throws Exception {
        serve(Auth.bearer(fake.issuer(), AUDIENCE), a -> { });
        var client = new Client();
        var ws = connect(fake.token().subject("carol").audience(AUDIENCE)
                .lifetime(Duration.ofMillis(1500)).sign(), client);
        assertThat(reply(ws, client, "who")).isEqualTo("carol|carol");
        assertThat(client.closedWith.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    @Test @DisplayName("a model call from a WebSocket is made on the caller's behalf")
    void modelCallAsTheCaller() throws Exception {
        try (var model = new FakeModelServer()) {
            serve(Auth.bearer(fake.issuer(), AUDIENCE), a -> a.ai(OpenAI.of("m").withBaseUrl(model.baseUrl())
                    .withCredentials(OAuthCredentials.tokenExchange(fake.issuer(), CLIENT, SECRET, "model-server"))));
            var client = new Client();
            var ws = connect(fake.token().subject("dave").audience(AUDIENCE).sign(), client);
            assertThat(reply(ws, client, "ask")).isEqualTo("ok");
            String claims = new String(Base64.getUrlDecoder().decode(model.calls.get(0).bearer().split("\\.")[1]),
                    StandardCharsets.UTF_8);
            assertThat(claims).contains("\"sub\":\"dave\"");
        }
    }
}
