package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Out-of-process MCP (Model Context Protocol) server connection.
 *
 * <p>Probes the MCP server's health endpoint, then registers its tools
 * with the application via {@code app.mcp()}.
 *
 * <p>Requires {@code cafeai-tools} on the classpath for the MCP client
 * implementation.
 *
 * <pre>{@code
 *   app.connect(McpEndpoint.at("http://github-mcp:3000"));
 *   app.connect(McpEndpoint.at("http://filesystem-mcp:3001"));
 *   app.connect(McpEndpoint.at("http://custom-mcp:4000")
 *       .onUnavailable(Fallback.ignore()));  // optional tool server
 * }</pre>
 */
public final class McpEndpoint implements Connection {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);

    private final String url;

    private McpEndpoint(String url) {
        this.url = url.endsWith("/") ? url.substring(0, url.length()-1) : url;
    }

    /** Creates an MCP endpoint connection targeting the given URL. */
    public static McpEndpoint at(String url) {
        return new McpEndpoint(url);
    }

    @Override public String name()      { return "McpEndpoint(" + url + ")"; }
    @Override public ServiceType type() { return ServiceType.MCP; }

    @Override
    public HealthStatus probe() {
        long start = System.currentTimeMillis();
        try {
            // MCP servers typically respond to a JSON-RPC ping or a plain HTTP GET
            var client   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
            var request  = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long latency = System.currentTimeMillis() - start;

            // Any 2xx or 4xx means the server is up (4xx = up but rejected our GET)
            int status = response.statusCode();
            if (status < 500) {
                return HealthStatus.reachable(name(), latency);
            }
            return HealthStatus.degraded(name(), "Server returned HTTP " + status);
        } catch (Exception e) {
            return HealthStatus.unreachable(name(), e.getMessage());
        }
    }

    @Override
    public void register(CafeAI app) {
        try {
            Class<?> mcpClass = Class.forName("io.cafeai.tools.McpServer");
            Object server = mcpClass.getMethod("connect", String.class).invoke(null, url);
            app.mcp(server);
            log.info("Connected: {} → registered as MCP server", name());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "McpEndpoint connection requires cafeai-tools on the classpath. " +
                "Add: implementation 'io.cafeai:cafeai-tools'", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to register MCP endpoint: " + e.getMessage(), e);
        }
    }
}
