package io.cafeai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal MCP client over Streamable HTTP, enough for tests: it speaks the protocol
 * on the wire (JSON-RPC, session header, SSE-or-JSON responses) the way an agent does.
 */
final class McpClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private String session;

    McpClient(String url) { this.url = url; }

    McpClient header(String name, String value) { headers.put(name, value); return this; }

    McpClient connect() throws Exception {
        JsonNode init = request("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "cafeai-test", "version", "1")));
        if (!init.has("result")) throw new IllegalStateException("initialize failed: " + init);
        notify("notifications/initialized");
        return this;
    }

    JsonNode listTools() throws Exception {
        return request("tools/list", Map.of()).path("result").path("tools");
    }

    /** The tools/call result: {@code content} and {@code isError}. */
    JsonNode call(String tool, Map<String, ?> arguments) throws Exception {
        return request("tools/call", Map.of("name", tool, "arguments", arguments)).path("result");
    }

    static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    private JsonNode request(String method, Object params) throws Exception {
        String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(),
                "method", method, "params", params));
        HttpResponse<String> response = http.send(post(body), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        String text = response.body();
        if (text.startsWith("event:") || text.startsWith("data:")) {
            // An SSE response: the JSON-RPC message is on the data line.
            for (String line : text.split("\n")) {
                if (line.startsWith("data:")) { text = line.substring(5).trim(); break; }
            }
        }
        return JSON.readTree(text);
    }

    private void notify(String method) throws Exception {
        http.send(post(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method))),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest post(String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) b.header("Mcp-Session-Id", session);
        headers.forEach(b::header);
        return b.build();
    }
}
