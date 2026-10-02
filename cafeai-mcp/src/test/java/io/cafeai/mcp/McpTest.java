package io.cafeai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.cafeai.core.CafeAI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("app.mcp()")
class McpTest {

    public record NewOrder(String customer, int quantity) { }
    public record Search(String text) { }

    public static class Calculator {
        @Tool("Adds two whole numbers")
        public long add(@P("the first number") long a, @P("the second number") long b) { return a + b; }

        @Tool("Divides a by b")
        public long divide(long a, long b) { return a / b; }
    }

    private CafeAI app;
    private String url;
    private final List<String> seenByFilter = new CopyOnWriteArrayList<>();

    @BeforeAll
    void start() throws Exception {
        app = CafeAI.create();
        app.filter(CafeAI.json());                               // the body parser that must not touch /mcp
        app.filter((req, res, next) -> { seenByFilter.add(req.path()); next.run(); });
        // An app-level rule every client is held to, MCP or not.
        app.filter((req, res, next) -> {
            String q = req.query("text");
            if (q != null && q.contains("DROP TABLE")) { res.status(400).json(Map.of("error", "rejected")); return; }
            next.run();
        });

        app.get("/orders/:id", (req, res, next) -> res.json(Map.of("id", req.params("id"), "status", "shipped")));
        app.post("/orders", (req, res, next) -> res.status(201).json(Map.of(
                "created", true, "customer", req.body("customer"), "quantity", req.body("quantity"))));
        app.get("/search", (req, res, next) -> res.json(Map.of("results", List.of(req.query("text") + " #1"))));
        app.get("/me", (req, res, next) -> {
            String auth = req.header("Authorization");
            if (!"Bearer good-token".equals(auth)) { res.status(401).json(Map.of("error", "unauthorized")); return; }
            res.json(Map.of("user", "ada", "tenant", String.valueOf(req.header("X-Tenant"))));
        });
        app.get("/broken", (req, res, next) -> { throw new IllegalStateException("kaput"); });

        app.mcp()
           .tool("get_order", "Look up an order by its id", "GET /orders/:id")
           .tool("create_order", "Create an order", "POST /orders", NewOrder.class)
           .tool("search", "Search the catalogue", "GET /search", Search.class)
           .tool("whoami", "Who the caller is", "GET /me")
           .tool("broken", "Always fails", "GET /broken")
           .tools(new Calculator())
           .forwardHeaders("X-Tenant");

        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        url = "http://localhost:" + app.port() + "/mcp";
    }

    @AfterAll
    void stop() {
        if (app != null) app.stop();
    }

    @Test @DisplayName("tools/list describes every tool, with path parameters as required strings")
    void listsTools() throws Exception {
        JsonNode tools = new McpClient(url).connect().listTools();

        assertThat(tools).extracting(t -> t.path("name").asText())
                .containsExactlyInAnyOrder("get_order", "create_order", "search", "whoami", "broken", "add", "divide");
        JsonNode getOrder = find(tools, "get_order");
        assertThat(getOrder.path("description").asText()).isEqualTo("Look up an order by its id");
        assertThat(getOrder.path("inputSchema").path("properties").path("id").path("type").asText()).isEqualTo("string");
        assertThat(getOrder.path("inputSchema").path("required")).extracting(JsonNode::asText).containsExactly("id");

        JsonNode create = find(tools, "create_order").path("inputSchema").path("properties");
        assertThat(create.path("customer").path("type").asText()).isEqualTo("string");
        assertThat(create.path("quantity").path("type").asText()).isEqualTo("integer");

        JsonNode add = find(tools, "add");
        assertThat(add.path("description").asText()).isEqualTo("Adds two whole numbers");
        assertThat(add.path("inputSchema").path("properties").path("a").path("description").asText()).isEqualTo("the first number");
    }

    @Test @DisplayName("a GET route tool fills in its path parameter and returns the route's body")
    void getRoute() throws Exception {
        JsonNode result = new McpClient(url).connect().call("get_order", Map.of("id", "A-17"));
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(McpClient.text(result)).contains("\"id\":\"A-17\"").contains("shipped");
    }

    @Test @DisplayName("a POST route tool sends its arguments as the JSON body, with CafeAI.json() installed")
    void postRoute() throws Exception {
        JsonNode result = new McpClient(url).connect().call("create_order", Map.of("customer", "Ada", "quantity", 3));
        assertThat(result.path("isError").asBoolean()).isFalse();
        // req.body(key) reads a field as text, so the route echoes the quantity as "3".
        assertThat(McpClient.text(result)).contains("\"customer\":\"Ada\"").contains("\"quantity\":\"3\"");
    }

    @Test @DisplayName("the app's own filters run on the route call -- and never on /mcp itself")
    void filtersApplyToRoutesNotTheEndpoint() throws Exception {
        seenByFilter.clear();
        var client = new McpClient(url).connect();

        JsonNode ok = client.call("search", Map.of("text", "jackets"));
        JsonNode rejected = client.call("search", Map.of("text", "x'; DROP TABLE orders;--"));

        assertThat(McpClient.text(ok)).contains("jackets #1");
        assertThat(rejected.path("isError").asBoolean()).isTrue();
        assertThat(McpClient.text(rejected)).startsWith("HTTP 400").contains("rejected");
        assertThat(seenByFilter).containsOnly("/search").hasSize(2);
    }

    @Test @DisplayName("the caller's Authorization header goes with the call, so a protected route stays protected")
    void authForwarded() throws Exception {
        JsonNode withToken = new McpClient(url).header("Authorization", "Bearer good-token").header("X-Tenant", "acme")
                .connect().call("whoami", Map.of());
        JsonNode without = new McpClient(url).connect().call("whoami", Map.of());

        assertThat(withToken.path("isError").asBoolean()).isFalse();
        assertThat(McpClient.text(withToken)).contains("\"user\":\"ada\"").contains("\"tenant\":\"acme\"");
        assertThat(without.path("isError").asBoolean()).isTrue();
        assertThat(McpClient.text(without)).startsWith("HTTP 401");
    }

    @Test @DisplayName("a failing route is an error result carrying its status")
    void routeErrors() throws Exception {
        var client = new McpClient(url).connect();
        JsonNode broken = client.call("broken", Map.of());
        JsonNode missingArg = client.call("get_order", Map.of());

        assertThat(broken.path("isError").asBoolean()).isTrue();
        assertThat(McpClient.text(broken)).startsWith("HTTP 500");
        assertThat(missingArg.path("isError").asBoolean()).isTrue();
        assertThat(McpClient.text(missingArg)).isEqualTo("missing argument \"id\"");
    }

    @Test @DisplayName("@Tool objects are served too, and a throwing tool is an error result")
    void objectTools() throws Exception {
        var client = new McpClient(url).connect();
        assertThat(McpClient.text(client.call("add", Map.of("a", 40, "b", 2)))).isEqualTo("42");

        JsonNode divideByZero = client.call("divide", Map.of("a", 1, "b", 0));
        assertThat(divideByZero.path("isError").asBoolean()).isTrue();
        assertThat(McpClient.text(divideByZero)).contains("ArithmeticException");
    }

    @Test @DisplayName("tool names must be unique, and a route must be a method and a path")
    void configurationErrors() {
        var other = CafeAI.create();
        var mcp = other.mcp().tool("a", "first", "GET /a");
        assertThatThrownBy(() -> mcp.tool("a", "second", "GET /b")).hasMessageContaining("both named \"a\"");
        assertThatThrownBy(() -> mcp.tool("c", "bad", "/orders")).hasMessageContaining("method and a path");
        assertThat(other.mcp()).isSameAs(mcp);
    }

    private static JsonNode find(JsonNode tools, String name) {
        for (JsonNode t : tools) if (t.path("name").asText().equals(name)) return t;
        throw new AssertionError("no tool " + name + " in " + tools);
    }
}
