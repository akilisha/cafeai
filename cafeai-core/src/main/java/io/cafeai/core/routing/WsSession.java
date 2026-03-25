package io.cafeai.core.routing;

/**
 * A live WebSocket session between the server and a single client.
 *
 * <p>Obtained in {@link WsHandler#onOpen(WsSession)} and valid for the
 * lifetime of the connection. All methods are thread-safe — you may call
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
}
