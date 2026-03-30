package io.cafeai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) client -- connects to an MCP server and
 * discovers its available tools.
 *
 * <p>CafeAI implements the MCP JSON-RPC protocol directly via Helidon's
 * WebClient. No third-party MCP library is used. This keeps CafeAI aligned
 * with the protocol spec rather than any library's interpretation of it,
 * and avoids dependency conflicts.
 *
 * <p>Register via {@code app.mcp(McpServer.connect(url))}:
 *
 * <pre>{@code
 *   app.mcp(McpServer.connect("http://github-mcp-server:3000"));
 *   app.mcp(McpServer.connect("http://filesystem-mcp:3001"));
 * }</pre>
 *
 * <p>On registration, CafeAI calls {@code tools/list} to discover available
 * tools and exposes them to the LLM alongside Java tools. MCP tools are
 * flagged as {@link ToolDefinition.TrustLevel#EXTERNAL} in observability traces.
 *
 * <p>Tool invocation: when the LLM requests an MCP tool, CafeAI calls
 * {@code tools/call} on the MCP server and returns the result.
 *
 * <p><strong>Protocol reference:</strong>
 * <a href="https://spec.modelcontextprotocol.io">modelcontextprotocol.io</a>
 */
public final class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong REQUEST_ID = new AtomicLong(1);

    private final String baseUrl;

    private McpServer(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    /**
     * Creates an MCP client that connects to the given server URL.
     *
     * @param url base URL of the MCP server (e.g. {@code http://mcp-server:3000})
     */
    public static McpServer connect(String url) {
        return new McpServer(url);
    }

    /**
     * Calls the MCP {@code tools/list} endpoint and returns the discovered tools.
     *
     * <p>Called by {@link CafeAIToolBridge#registerMcp(Object)} during
     * {@code app.mcp()} registration.
     */
    public List<ToolDefinition> discoverTools() {
        log.info("Discovering MCP tools from: {}", baseUrl);
        try {
            String responseBody = post("tools/list", "{}");
            return parseToolList(responseBody);
        } catch (Exception e) {
            log.error("Failed to discover tools from MCP server {}: {}", baseUrl, e.getMessage());
            throw new McpException("Cannot connect to MCP server at " + baseUrl +
                ". Check the server is running and the URL is correct.", e);
        }
    }

    /**
     * Calls the MCP {@code tools/call} endpoint to invoke a named tool.
     *
     * @param toolName  the tool name as reported by {@code tools/list}
     * @param arguments JSON string of arguments
     * @return the tool's result as a string
     */
    public String invokeTool(String toolName, String arguments) {
        try {
            String payload = buildToolCallPayload(toolName, arguments);
            String responseBody = post("tools/call", payload);
            return parseToolResult(responseBody);
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            return "ERROR: MCP tool invocation failed: " + e.getMessage();
        }
    }

    /** The base URL of this MCP server. */
    public String baseUrl() { return baseUrl; }

    // -- Private: HTTP transport (MCP over HTTP+SSE) ---------------------------

    /**
     * Sends a JSON-RPC 2.0 request to the MCP server.
     * MCP uses HTTP POST with JSON-RPC bodies.
     */
    private String post(String method, String paramsJson) throws Exception {
        String requestBody = buildJsonRpc(method, paramsJson);

        // Use Java's built-in HttpClient -- avoids an extra dependency here.
        // Helidon WebClient would be used if we needed reactive/async behaviour,
        // but synchronous is correct on virtual threads.
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        var response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpException(
                "MCP server returned HTTP " + response.statusCode() +
                " for method '" + method + "'");
        }
        return response.body();
    }

    private static String buildJsonRpc(String method, String paramsJson) throws Exception {
        // Build: {"jsonrpc":"2.0","id":N,"method":"<method>","params":<params>}
        JsonNode params = paramsJson != null && !paramsJson.isBlank()
            ? MAPPER.readTree(paramsJson)
            : MAPPER.createObjectNode();

        var node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", REQUEST_ID.getAndIncrement());
        node.put("method", method);
        node.set("params", params);
        return MAPPER.writeValueAsString(node);
    }

    private static String buildToolCallPayload(String toolName, String arguments) throws Exception {
        var params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments",
            arguments != null ? MAPPER.readTree(arguments) : MAPPER.createObjectNode());
        return MAPPER.writeValueAsString(params);
    }

    // -- Private: response parsing ---------------------------------------------

    private List<ToolDefinition> parseToolList(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode result = root.path("result");
        JsonNode toolsNode = result.path("tools");

        List<ToolDefinition> tools = new ArrayList<>();
        if (toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                String name        = toolNode.path("name").asText();
                String description = toolNode.path("description").asText();

                List<ToolDefinition.ParameterSchema> params = new ArrayList<>();
                JsonNode inputSchema = toolNode.path("inputSchema");
                JsonNode properties  = inputSchema.path("properties");

                if (properties.isObject()) {
                    properties.fields().forEachRemaining(e -> {
                        String paramName = e.getKey();
                        String paramType = e.getValue().path("type").asText("string");
                        String paramDesc = e.getValue().path("description").asText(paramName);
                        params.add(new ToolDefinition.ParameterSchema(paramName, paramType, paramDesc));
                    });
                }

                tools.add(ToolDefinition.fromMcp(name, description, params));
                log.debug("Discovered MCP tool: {} -- {}", name, description);
            }
        }

        log.info("Discovered {} tools from MCP server {}", tools.size(), baseUrl);
        return tools;
    }

    private static String parseToolResult(String responseBody) throws Exception {
        JsonNode root = MAPPER.readTree(responseBody);

        // MCP error response
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            return "ERROR: " + error.path("message").asText("MCP error");
        }

        // MCP success: result.content[0].text
        JsonNode content = root.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            JsonNode first = content.get(0);
            if ("text".equals(first.path("type").asText())) {
                return first.path("text").asText();
            }
        }

        // Fallback: return whole result as string
        return root.path("result").toString();
    }

    /** Thrown when MCP server communication fails. */
    public static final class McpException extends RuntimeException {
        public McpException(String message) { super(message); }
        public McpException(String message, Throwable cause) { super(message, cause); }
    }
}
