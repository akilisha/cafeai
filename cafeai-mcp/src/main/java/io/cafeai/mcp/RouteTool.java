package io.cafeai.mcp;

import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.audit.AuditSink;
import io.helidon.extensions.mcp.server.McpTool;
import io.helidon.extensions.mcp.server.McpToolRequest;
import io.helidon.extensions.mcp.server.McpToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An app route served as an MCP tool. A call becomes a real HTTP request to the route
 * on this server, so every filter, guardrail and check on the route runs as for any
 * other client; the caller's forwarded headers go with it. Each call is an audit record.
 */
final class RouteTool implements McpTool {

    private static final Pattern PARAM = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String name;
    private final String description;
    private final String method;
    private final String pathTemplate;
    private final List<String> pathParams = new ArrayList<>();
    private final String schema;
    private final IntSupplier port;
    private final AuditSink audit;

    RouteTool(String name, String description, String route, Class<?> input, IntSupplier port, AuditSink audit) {
        this.name = name;
        this.audit = audit;
        this.description = description;
        this.port = port;

        String[] parts = route.trim().split("\\s+", 2);
        if (parts.length != 2 || !parts[1].startsWith("/")) {
            throw new IllegalArgumentException("A route is a method and a path, e.g. \"GET /orders/:id\" -- not \"" + route + "\"");
        }
        this.method = parts[0].toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("Unsupported method in route \"" + route + "\"");
        }
        this.pathTemplate = parts[1];
        Matcher m = PARAM.matcher(pathTemplate);
        while (m.find()) pathParams.add(m.group(1));

        Map<String, Object> schema = input == null ? Json.emptyObjectSchema() : Json.schemaOf(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.getOrDefault("properties", Map.of()));
        @SuppressWarnings("unchecked")
        List<Object> required = new ArrayList<>((List<Object>) schema.getOrDefault("required", List.of()));
        for (String p : pathParams.reversed()) {
            if (!properties.containsKey(p)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", "string");
                properties = prepend(properties, p, s);
            }
            if (!required.contains(p)) required.addFirst(p);
        }
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        this.schema = Json.write(schema);
    }

    private static Map<String, Object> prepend(Map<String, Object> map, String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        out.putAll(map);
        return out;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public String schema() { return schema; }

    @Override
    public McpToolResult tool(McpToolRequest request) {
        long start = System.nanoTime();
        McpToolResult result = call(request);
        audit.record(AuditEvent.ToolCall.now(name, AuditEvent.ToolCall.Via.MCP, result.error(),
                Duration.ofNanos(System.nanoTime() - start)));
        return result;
    }

    private McpToolResult call(McpToolRequest request) {
        Map<String, Object> args = Json.arguments(request.arguments());

        String path = pathTemplate;
        for (String p : pathParams) {
            Object value = args.remove(p);
            if (value == null) return error("missing argument \"" + p + "\"");
            path = path.replace(":" + p, URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20"));
        }

        int port = this.port.getAsInt();
        if (port < 0) return error("the server is not running");
        HttpRequest.Builder http = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60));
        if (BODY_METHODS.contains(method)) {
            http.uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.write(args)));
        } else {
            http.uri(URI.create("http://localhost:" + port + path + query(args)))
                .method(method, HttpRequest.BodyPublishers.noBody());
        }
        request.requestContext().get(ForwardedHeaders.class)
                .ifPresent(f -> f.headers().forEach(http::header));

        try {
            HttpResponse<String> response = HTTP.send(http.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() >= 400) {
                return error("HTTP " + response.statusCode() + (body.isBlank() ? "" : ": " + body));
            }
            return McpToolResult.builder().addTextContent(body).build();
        } catch (IOException e) {
            return error("the route could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("interrupted");
        }
    }

    private static String query(Map<String, Object> args) {
        if (args.isEmpty()) return "";
        StringBuilder q = new StringBuilder();
        args.forEach((k, v) -> {
            if (v == null) return;
            List<?> values = v instanceof List<?> list ? list : List.of(v);
            for (Object value : values) {
                q.append(q.isEmpty() ? '?' : '&')
                 .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                 .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            }
        });
        return q.toString();
    }

    private static McpToolResult error(String message) {
        return McpToolResult.builder().addTextContent(message).error(true).build();
    }

    /** The MCP caller's headers that go with each route call; put in the request context by a filter. */
    record ForwardedHeaders(Map<String, String> headers) { }
}
