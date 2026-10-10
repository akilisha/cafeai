package io.cafeai.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for an Anthropic-compatible Messages endpoint (the Claude API, Claude in Microsoft
 * Foundry, a gateway). It answers {@code POST <prefix>/v1/messages}, plain or streamed, and
 * records every credential header of every call, so a test can see which credential each call
 * carried, in which header, and that there was no other.
 */
final class FakeAnthropicServer implements AutoCloseable {

    /** One call: its path, every credential header it carried, and whether it streamed. */
    record Call(String path, List<String> authorization, List<String> xApiKey, List<String> apiKey, boolean streamed) {
        /** The single bearer token, failing unless it was the only credential. */
        String bearer() {
            if (authorization.size() != 1 || !xApiKey.isEmpty() || !apiKey.isEmpty()) {
                throw new AssertionError("Not exactly one bearer credential: " + this);
            }
            if (!authorization.get(0).startsWith("Bearer ")) throw new AssertionError("Not a bearer token: " + this);
            return authorization.get(0).substring(7);
        }
    }

    private final HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();

    FakeAnthropicServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::messages);
        server.start();
    }

    /** The endpoint's address, without {@code /v1}: as an Anthropic-compatible API documents it. */
    String baseUrl(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    private void messages(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = body.replace(" ", "").contains("\"stream\":true");
        var h = exchange.getRequestHeaders();
        calls.add(new Call(exchange.getRequestURI().getPath(),
                List.copyOf(h.getOrDefault("Authorization", List.of())),
                List.copyOf(h.getOrDefault("x-api-key", List.of())),
                List.copyOf(h.getOrDefault("api-key", List.of())), stream));
        if (!exchange.getRequestURI().getPath().endsWith("/v1/messages")) {
            send(exchange, 404, "application/json", "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\"}}");
            return;
        }
        if (!stream) {
            send(exchange, 200, "application/json", "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                    + "\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}");
            return;
        }
        String events = event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"m\",\"content\":[],\"stop_reason\":null,"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}")
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
        send(exchange, 200, "text/event-stream", events);
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
