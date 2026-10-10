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
 * A stand-in for an OpenAI-compatible model endpoint (a company's model server or gateway). It
 * answers {@code POST /v1/chat/completions}, plain or streamed, and records the
 * {@code Authorization} headers of every call, so a test can see which credential each call
 * carried, and that there was exactly one.
 */
final class FakeModelServer implements AutoCloseable {

    /** One call: every Authorization header it carried, and whether it streamed. */
    record Call(List<String> authorization, boolean streamed) {
        /** The single bearer token, failing if there wasn't exactly one Authorization header. */
        String bearer() {
            if (authorization.size() != 1) throw new AssertionError("Authorization headers: " + authorization);
            String h = authorization.get(0);
            if (!h.startsWith("Bearer ")) throw new AssertionError("Not a bearer token: " + h);
            return h.substring(7);
        }
    }

    private final HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile boolean rateLimited;
    /** The {@code Retry-After} a rate-limited answer carries, or {@code null} for none. */
    volatile String retryAfter;

    FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", this::chat);
        server.start();
    }

    /** The base URL to give the provider. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void chat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = body.replace(" ", "").contains("\"stream\":true");
        List<String> auth = exchange.getRequestHeaders().getOrDefault("Authorization", List.of());
        calls.add(new Call(List.copyOf(auth), stream));

        if (rateLimited) {
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            send(exchange, 429, "application/json",
                    "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_exceeded\"}}");
            return;
        }
        if (!stream) {
            send(exchange, 200, "application/json", "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":0,"
                    + "\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}");
            return;
        }
        String chunk = "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}";
        String last = "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}";
        send(exchange, 200, "text/event-stream",
                "data: " + chunk + "\n\n" + "data: " + last + "\n\n" + "data: [DONE]\n\n");
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
