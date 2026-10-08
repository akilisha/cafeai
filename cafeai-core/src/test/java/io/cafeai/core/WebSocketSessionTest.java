package io.cafeai.core;

import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WsSession")
class WebSocketSessionTest {

    @Test @DisplayName("isOpen() is true while open and false once closed; identity() is empty without sign-in")
    void openState() throws Exception {
        var states = new CompletableFuture<String>();
        var app = CafeAI.create();
        app.ws("/ws", new WsHandler() {
            @Override public void onMessage(WsSession session, String message) {
                boolean before = session.isOpen();
                boolean anonymous = session.identity().isEmpty();
                session.close();
                states.complete(before + "," + session.isOpen() + "," + anonymous);
            }
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var closed = new CompletableFuture<Integer>();
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + app.port() + "/ws"), new WebSocket.Listener() {
                        @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                            closed.complete(code);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
            ws.sendText("close me", true);
            assertThat(states.get(5, TimeUnit.SECONDS)).isEqualTo("true,false,true");
            assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        } finally {
            app.stop();
        }
    }
}
