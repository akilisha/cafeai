package io.cafeai.core.mcp;

/**
 * What {@code app.mcp()} serves: the tools AI agents can call over MCP, and where.
 *
 * <p>A <b>route tool</b> is one of the app's own routes. Calling it sends a real HTTP
 * request to that route on this server, so everything already on the route runs as
 * for any other client -- filters, guardrails, authentication, sessions, rate limits,
 * observability. The caller's {@code Authorization} header goes with it, so a
 * protected route stays protected. The route's response body is the tool's result; a
 * 4xx or 5xx status makes it an error result.
 *
 * <p>An <b>object tool</b> is a LangChain4j {@code @Tool} method, the same kind agents use.
 *
 * <p>The MCP endpoint itself (default {@code /mcp}) is served by Helidon's MCP server,
 * which owns the protocol; CafeAI's own filters do not run on it.
 *
 * <p>Configure before {@code app.listen(...)}.
 */
public interface McpConfig {

    /**
     * Serves a route as a tool that takes no arguments beyond its path parameters.
     *
     * @param name        the tool's name, e.g. {@code "get_order"}
     * @param description what it does, for the agent deciding whether to call it
     * @param route       method and path, e.g. {@code "GET /orders/:id"}; each {@code :param}
     *                    becomes a required string argument
     */
    McpConfig tool(String name, String description, String route);

    /**
     * Serves a route as a tool whose arguments are described by {@code input}, a record
     * or class. For POST, PUT and PATCH they are sent as the JSON body; for GET and
     * DELETE, as query parameters. Path parameters come first, as required strings.
     */
    McpConfig tool(String name, String description, String route, Class<?> input);

    /** Serves every {@code @Tool} method of these objects as a tool. */
    McpConfig tools(Object... toolObjects);

    /** Where the MCP endpoint is served. Default {@code /mcp}. */
    McpConfig path(String path);

    /**
     * Request headers passed from the MCP caller to route tools, besides
     * {@code Authorization} (always passed), e.g. {@code "X-Tenant-Id"}.
     */
    McpConfig forwardHeaders(String... headerNames);

    /** The server name and version agents see. Default {@code "cafeai"} and the CafeAI version. */
    McpConfig server(String name, String version);
}
