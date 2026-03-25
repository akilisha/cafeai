package io.cafeai.core.routing;

/**
 * Handler for WebSocket lifecycle events on a single connection.
 *
 * <p>Register via {@code app.ws(path, handler)}. CafeAI creates one
 * handler instance per registered path — the handler must be thread-safe
 * because multiple connections share the same instance concurrently.
 *
 * <pre>{@code
 *   app.ws("/chat", new WsHandler() {
 *
 *       // A new client connected
 *       public void onOpen(WsSession session) {
 *           System.out.println("Client connected: " + session.id());
 *           session.send("Welcome!");
 *       }
 *
 *       // A text message arrived from the client
 *       public void onMessage(WsSession session, String message) {
 *           session.send("Echo: " + message);
 *       }
 *
 *       // The client disconnected
 *       public void onClose(WsSession session, int statusCode, String reason) {
 *           System.out.println("Client disconnected: " + reason);
 *       }
 *
 *       // An error occurred on the connection
 *       public void onError(WsSession session, Throwable error) {
 *           System.err.println("WS error: " + error.getMessage());
 *       }
 *   });
 * }</pre>
 *
 * <p>All methods have safe default implementations so you only override
 * what you need:
 *
 * <pre>{@code
 *   // Lambda-friendly for simple echo servers
 *   app.ws("/echo", WsHandler.onMessage((session, msg) -> session.send(msg)));
 * }</pre>
 */
public interface WsHandler {

    /**
     * Called when a new WebSocket connection is established.
     * The session is open and ready to send messages.
     *
     * @param session the new client session
     */
    default void onOpen(WsSession session) {}

    /**
     * Called when a text message arrives from the client.
     *
     * @param session the client session that sent the message
     * @param message the text message content
     */
    default void onMessage(WsSession session, String message) {}

    /**
     * Called when a binary message arrives from the client.
     *
     * @param session the client session that sent the data
     * @param data    the binary message content
     */
    default void onBinaryMessage(WsSession session, byte[] data) {}

    /**
     * Called when the WebSocket connection is closed by either party.
     *
     * @param session    the session that was closed
     * @param statusCode WebSocket close status code (1000 = normal)
     * @param reason     human-readable close reason
     */
    default void onClose(WsSession session, int statusCode, String reason) {}

    /**
     * Called when an error occurs on the WebSocket connection.
     * The session may still be open — check {@link WsSession#isOpen()}.
     *
     * @param session the session on which the error occurred
     * @param error   the error
     */
    default void onError(WsSession session, Throwable error) {}

    // ── Convenience factories ─────────────────────────────────────────────────

    /**
     * Creates a handler that only implements {@link #onMessage(WsSession, String)}.
     *
     * <pre>{@code
     *   app.ws("/echo", WsHandler.onMessage((session, msg) -> session.send(msg)));
     * }</pre>
     */
    static WsHandler onMessage(MessageListener listener) {
        return new WsHandler() {
            @Override
            public void onMessage(WsSession session, String message) {
                listener.handle(session, message);
            }
        };
    }

    /** Functional interface for the message handler convenience factory. */
    @FunctionalInterface
    interface MessageListener {
        void handle(WsSession session, String message);
    }
}
