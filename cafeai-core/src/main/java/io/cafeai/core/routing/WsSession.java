package io.cafeai.core.routing;

import java.util.concurrent.Flow;

/**
 * A live WebSocket session between the server and a single client.
 *
 * <p>Obtained in {@link WsHandler#onOpen(WsSession)} and valid for the
 * lifetime of the connection. All methods are thread-safe -- you may call
 * {@link #send(String)} from any thread.
 *
 * <pre>{@code
 *   app.ws("/chat", new WsHandler() {
 *       public void onOpen(WsSession session) {
 *           session.send("Connected!");
 *       }
 *       public void onMessage(WsSession session, String message) {
 *           session.send("Echo: " + message);
 *       }
 *   });
 * }</pre>
 */
public interface WsSession {

    /**
     * Sends a text message to the client.
     *
     * @param message the text to send
     * @throws IllegalStateException if the session is already closed
     */
    void send(String message);

    /**
     * Sends a binary message to the client.
     *
     * @param data the bytes to send
     */
    void send(byte[] data);

    /**
     * Closes the WebSocket connection with the given status code and reason.
     *
     * @param code   WebSocket close code (e.g. 1000 for normal closure)
     * @param reason human-readable reason (max 123 bytes)
     */
    void close(int code, String reason);

    /** Closes with normal closure (1000). */
    default void close() {
        close(1000, "Normal Closure");
    }

    /**
     * Returns a unique identifier for this session.
     * Useful for session management in multi-client scenarios.
     */
    String id();

    /**
     * Returns {@code true} if the connection is currently open.
     */
    boolean isOpen();

    // -- Token streaming ------------------------------------------------------

    /**
     * Streams LLM tokens over this socket: each token is sent as its own text
     * frame, then a {@code "[DONE]"} sentinel frame on completion. On a stream
     * error the sentinel is still sent (the client sees the stream end) — for
     * error visibility, consume {@code PromptRequest.stream()} directly instead.
     *
     * <p>Pipe {@code app.prompt(...).stream()} straight to the client:
     * <pre>{@code
     *   app.ws("/chat/:sessionId", new WsHandler() {
     *       public void onMessage(WsSession s, String msg) {
     *           s.streamTokens(app.prompt(msg).session(s.id()).stream());
     *       }
     *   });
     * }</pre>
     *
     * <p>Backpressure: this requests all tokens up front (the upstream
     * publisher paces itself against the model). If the client disconnects
     * mid-stream the subscription is cancelled on the next token.
     *
     * @param tokens the token publisher (e.g. from {@code PromptRequest.stream()})
     */
    default void streamTokens(Flow.Publisher<String> tokens) {
        streamTokens(tokens, "[DONE]");
    }

    /**
     * {@link #streamTokens(Flow.Publisher)} with a custom completion sentinel.
     * Pass {@code null} to send no sentinel frame.
     */
    default void streamTokens(Flow.Publisher<String> tokens, String doneSentinel) {
        tokens.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override public void onNext(String token) {
                if (isOpen()) {
                    send(token);
                } else {
                    subscription.cancel();
                }
            }

            @Override public void onError(Throwable t) {
                if (doneSentinel != null && isOpen()) send(doneSentinel);
            }

            @Override public void onComplete() {
                if (doneSentinel != null && isOpen()) send(doneSentinel);
            }
        });
    }
}
