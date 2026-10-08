package io.cafeai.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The acme-desk story, end to end, without a browser: alice (finance) and bob (staff) sign in
 * through Keycloak's login page and ask the same questions over the WebSocket chat; then an AI
 * agent with its own identity asks over MCP. The sources each one gets are PostgreSQL's decision.
 *
 * <pre>
 *   docker compose -f capstones/acme-desk/docker-compose.yml up -d
 *   ./gradlew :capstones:acme-desk:demo
 * </pre>
 */
public class DeskDemo {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> QUESTIONS = List.of(
        "What was Q3 revenue?",
        "How many days of annual leave do I get?");

    public static void main(String[] args) throws Exception {
        var app = DeskApp.start();
        try {
            for (String[] person : new String[][]{{"alice", "alice"}, {"bob", "bob"}}) {
                var browser = signIn(person[0], person[1]);
                System.out.println("\n== " + person[0] + ", signed in through Keycloak, chatting over the WebSocket");
                for (String question : QUESTIONS) show(question, askOverWebSocket(browser, question));
            }
            System.out.println("\n== an AI agent (desk-agent, staff), over MCP with its own Keycloak token");
            String agentToken = clientCredentialsToken();
            for (String question : QUESTIONS) show(question, askOverMcp(agentToken, question));
        } finally {
            app.stop();
        }
        System.exit(0);
    }

    private static void show(String question, JsonNode reply) {
        System.out.println("  Q: " + question);
        System.out.println("  A: " + reply.path("answer").asText().replaceAll("\\s+", " ").trim());
        System.out.println("     sources retrieved for " + reply.path("asker").asText() + ": " + reply.path("sources"));
    }

    // ── a browser: sign in through Keycloak's login page ───────────────────────────────

    private static HttpClient signIn(String user, String password) throws Exception {
        var browser = HttpClient.newBuilder().cookieHandler(new BrowserCookies())
            .followRedirects(HttpClient.Redirect.NEVER).build();
        String toKeycloak = location(browser, get(DeskApp.BASE + "/auth/login?return=/"));
        String loginPage = browser.send(get(toKeycloak), HttpResponse.BodyHandlers.ofString()).body();
        Matcher action = Pattern.compile("<form[^>]*action=\"([^\"]+)\"").matcher(loginPage);
        if (!action.find()) throw new IllegalStateException("No login form on Keycloak's page");
        var signedIn = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=" + password + "&credentialId="))
                .build(), HttpResponse.BodyHandlers.discarding());
        String toCallback = signedIn.headers().firstValue("Location")
            .orElseThrow(() -> new IllegalStateException("Keycloak didn't accept " + user + "'s sign-in"));
        location(browser, get(toCallback));   // back to the desk, signed in
        return browser;
    }

    private static JsonNode askOverWebSocket(HttpClient browser, String question) throws Exception {
        var reply = new CompletableFuture<String>();
        WebSocket ws = browser.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + DeskApp.PORT + "/ws"), new WebSocket.Listener() {
                private final StringBuilder text = new StringBuilder();
                @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) reply.complete(text.toString());
                    w.request(1);
                    return null;
                }
            }).get(10, TimeUnit.SECONDS);
        ws.sendText(question, true);
        String answer = reply.get(120, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        return JSON.readTree(answer);
    }

    // ── an AI agent: its own token, then the MCP endpoint ───────────────────────────────

    private static String clientCredentialsToken() throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString(
            "desk-agent:desk-agent-secret".getBytes(StandardCharsets.UTF_8));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(DeskApp.ISSUER + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(),
            HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body()).path("access_token").asText();
    }

    private static JsonNode askOverMcp(String token, String question) throws Exception {
        var mcp = new Mcp(DeskApp.BASE + "/mcp", token);
        mcp.request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "desk-agent", "version", "1")));
        mcp.notify("notifications/initialized");
        JsonNode result = mcp.request("tools/call", Map.of("name", "ask_desk", "arguments", Map.of("question", question)));
        return JSON.readTree(result.path("result").path("content").path(0).path("text").asText());
    }

    /** Just enough of an MCP client (Streamable HTTP): JSON-RPC, the session header, SSE or JSON replies. */
    private static final class Mcp {
        private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        private final String url;
        private final String token;
        private String session;
        private int ids;

        Mcp(String url, String token) {
            this.url = url;
            this.token = token;
        }

        JsonNode request(String method, Object params) throws Exception {
            var response = http.send(post(JSON.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", ++ids, "method", method, "params", params))), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("MCP " + method + ": HTTP " + response.statusCode() + " "
                    + response.headers().firstValue("WWW-Authenticate").orElse(""));
            }
            response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
            String body = response.body();
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) return JSON.readTree(line.substring(5).trim());
            }
            return JSON.readTree(body);
        }

        void notify(String method) throws Exception {
            http.send(post(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method))),
                HttpResponse.BodyHandlers.discarding());
        }

        private HttpRequest post(String body) {
            var b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (session != null) b.header("Mcp-Session-Id", session);
            return b.build();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).build();
    }

    private static String location(HttpClient client, HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.headers().firstValue("Location")
            .orElseThrow(() -> new IllegalStateException("Expected a redirect from " + request.uri()
                + ", got HTTP " + response.statusCode()));
    }

    /**
     * Cookies kept per host and sent like a browser on localhost, which sends {@code Secure}
     * cookies over plain http (Java's own CookieManager doesn't, and Keycloak's are Secure).
     */
    private static final class BrowserCookies extends CookieHandler {
        private final Map<String, Map<String, String>> byHost = new ConcurrentHashMap<>();

        @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            Map<String, String> jar = byHost.get(uri.getHost());
            if (jar == null || jar.isEmpty()) return Map.of();
            StringBuilder cookie = new StringBuilder();
            jar.forEach((k, v) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            return Map.of("Cookie", List.of(cookie.toString()));
        }

        @Override public void put(URI uri, Map<String, List<String>> headers) {
            Map<String, String> jar = byHost.computeIfAbsent(uri.getHost(), h -> new ConcurrentHashMap<>());
            headers.forEach((name, values) -> {
                if (name == null || !name.equalsIgnoreCase("Set-Cookie")) return;
                for (String c : values) {
                    String pair = c.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String value = pair.substring(eq + 1).trim();
                    if (value.isEmpty() || c.toLowerCase().contains("max-age=0")) jar.remove(pair.substring(0, eq).trim());
                    else jar.put(pair.substring(0, eq).trim(), value);
                }
            });
        }
    }
}
