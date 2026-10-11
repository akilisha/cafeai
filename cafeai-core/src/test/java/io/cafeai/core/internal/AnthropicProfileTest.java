package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.ai.Anthropic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AnthropicProfile: the ant auth login sign-in, read and renewed as Anthropic's SDKs do")
class AnthropicProfileTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @TempDir Path dir;
    private final Map<String, String> env = new HashMap<>();
    private HttpServer server;
    private final List<JsonNode> refreshes = new CopyOnWriteArrayList<>();
    private final List<String> betas = new CopyOnWriteArrayList<>();
    private final List<String[]> messages = new CopyOnWriteArrayList<>();   // {authorization, x-api-key}
    private volatile int refreshStatus = 200;
    private volatile Runnable duringRefresh = () -> { };

    @BeforeEach
    void start() throws IOException {
        env.put("ANTHROPIC_CONFIG_DIR", dir.toString());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/oauth/token", exchange -> {
            refreshes.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            betas.add(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            duringRefresh.run();
            if (refreshStatus != 200) {
                send(exchange, refreshStatus, "{\"error\":\"invalid_grant\"}");
                return;
            }
            send(exchange, 200, "{\"access_token\":\"sk-ant-oat01-renewed-" + refreshes.size() + "\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":7200,\"refresh_token\":\"rotated-" + refreshes.size() + "\",\"scope\":\"workspace:developer\"}");
        });
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            messages.add(new String[] {exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-api-key")});
            send(exchange, 200, "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        LangchainBridge.environment = System::getenv;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void profile(String name, String clientId, String baseUrl) throws IOException {
        Files.createDirectories(dir.resolve("configs"));
        var auth = JSON.createObjectNode().put("type", "user_oauth");
        if (clientId != null) auth.put("client_id", clientId);
        var config = JSON.createObjectNode().put("version", "1.0");
        config.set("authentication", auth);
        if (baseUrl != null) config.put("base_url", baseUrl);
        Files.writeString(dir.resolve("configs").resolve(name + ".json"), config.toString());
    }

    private void credentials(String name, String accessToken, Instant expires) throws IOException {
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("credentials").resolve(name + ".json"),
                "{\"version\":\"1.0\",\"type\":\"oauth_token\",\"access_token\":\"" + accessToken + "\",\"expires_at\":"
                + expires.getEpochSecond() + ",\"refresh_token\":\"refresh-1\",\"organization_name\":\"Acme\","
                + "\"account_email\":\"alice@acme.example\",\"workspace_id\":\"wrkspc_01\",\"workspace_name\":\"Engineering\"}");
    }

    private JsonNode stored(String name) throws IOException {
        return JSON.readTree(dir.resolve("credentials").resolve(name + ".json").toFile());
    }

    private AnthropicProfile active() {
        return AnthropicProfile.active(env::get).orElseThrow();
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    // ── where the profile is ────────────────────────────────────────────────────

    @Test @DisplayName("the directory: ANTHROPIC_CONFIG_DIR, else the platform's (APPDATA on Windows, XDG elsewhere)")
    void directory() {
        assertThat(AnthropicProfile.dir(env::get)).isEqualTo(dir);
        UnaryOperator<String> platform = name -> Map.of("APPDATA", "C:\\Users\\a\\AppData\\Roaming",
                "XDG_CONFIG_HOME", "/home/a/.xdg").get(name);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        assertThat(AnthropicProfile.dir(platform)).isEqualTo(windows
                ? Path.of("C:\\Users\\a\\AppData\\Roaming", "Anthropic") : Path.of("/home/a/.xdg", "anthropic"));
    }

    @Test @DisplayName("the profile: ANTHROPIC_PROFILE, else active_config, else default; none is not an error")
    void whichProfile() throws IOException {
        assertThat(AnthropicProfile.active(env::get)).isEmpty();

        profile("default", "cid", null);
        profile("work", "cid", null);
        assertThat(active().name()).isEqualTo("default");

        Files.writeString(dir.resolve("active_config"), "work\n");
        assertThat(active().name()).isEqualTo("work");

        env.put("ANTHROPIC_PROFILE", "default");
        assertThat(active().name()).isEqualTo("default");

        env.put("ANTHROPIC_PROFILE", "missing");
        assertThatThrownBy(() -> AnthropicProfile.active(env::get))
                .hasMessageContaining("ANTHROPIC_PROFILE").hasMessageContaining("ant auth login --profile missing");
    }

    @Test @DisplayName("a federation profile is recognised, and refused as a sign-in with a pointer to AnthropicFederation")
    void federationProfile() throws IOException {
        Files.createDirectories(dir.resolve("configs"));
        Files.writeString(dir.resolve("configs/default.json"),
                "{\"authentication\":{\"type\":\"oidc_federation\",\"federation_rule_id\":\"fdrl_1\"}}");
        assertThat(active().isSignIn()).isFalse();
        assertThatThrownBy(() -> active().credentials()).hasMessageContaining("AnthropicFederation");
    }

    // ── the token ───────────────────────────────────────────────────────────────

    @Test @DisplayName("a token with more than two minutes left is used as it is, with no renewal")
    void fresh() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-current", NOW.plusSeconds(600));
        assertThat(active().credentials(at(NOW)).token()).isEqualTo("sk-ant-oat01-current");
        assertThat(refreshes).isEmpty();
    }

    @Test @DisplayName("near expiry it is renewed as Anthropic's SDKs do, and written back keeping the other fields")
    void renewed() throws IOException {
        profile("default", "cid-123", base());
        credentials("default", "sk-ant-oat01-old", NOW.plusSeconds(90));

        assertThat(active().credentials(at(NOW)).token()).isEqualTo("sk-ant-oat01-renewed-1");

        assertThat(refreshes).singleElement().satisfies(r -> {
            assertThat(r.get("grant_type").asText()).isEqualTo("refresh_token");
            assertThat(r.get("refresh_token").asText()).isEqualTo("refresh-1");
            assertThat(r.get("client_id").asText()).isEqualTo("cid-123");
        });
        assertThat(betas).containsExactly("oauth-2025-04-20");
        JsonNode saved = stored("default");
        assertThat(saved.get("access_token").asText()).isEqualTo("sk-ant-oat01-renewed-1");
        assertThat(saved.get("refresh_token").asText()).isEqualTo("rotated-1");
        assertThat(saved.get("expires_at").asLong()).isEqualTo(NOW.plusSeconds(7200).getEpochSecond());
        assertThat(saved.get("type").asText()).isEqualTo("oauth_token");
        assertThat(saved.get("account_email").asText()).isEqualTo("alice@acme.example");
        assertThat(saved.get("workspace_name").asText()).isEqualTo("Engineering");
    }

    @Test @DisplayName("an expired token with no client id can't be renewed: sign in again")
    void cannotRenew() throws IOException {
        profile("default", null, base());
        credentials("default", "sk-ant-oat01-old", NOW.minusSeconds(10));
        assertThatThrownBy(() -> active().credentials(at(NOW)).token())
                .hasMessageContaining("has expired").hasMessageContaining("cafeai login claude");
        assertThat(refreshes).isEmpty();
    }

    @Test @DisplayName("renewal refused because ant renewed first: the token it saved is used")
    void someoneElseRenewed() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-old", NOW.minusSeconds(10));
        refreshStatus = 400;
        duringRefresh = () -> {
            try {
                credentials("default", "sk-ant-oat01-from-ant", NOW.plusSeconds(3600));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        assertThat(active().credentials(at(NOW)).token()).isEqualTo("sk-ant-oat01-from-ant");
    }

    @Test @DisplayName("renewal refused and nothing newer saved: sign in again")
    void refused() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-old", NOW.minusSeconds(10));
        refreshStatus = 400;
        assertThatThrownBy(() -> active().credentials(at(NOW)).token())
                .hasMessageContaining("refused").hasMessageContaining("invalid_grant")
                .hasMessageContaining("cafeai login claude");
    }

    @Test @DisplayName("the refresh token only goes over https, or plain http to this machine")
    void httpsOnly() throws IOException {
        profile("default", "cid", "http://claude.example.com");
        credentials("default", "sk-ant-oat01-old", NOW.minusSeconds(10));
        assertThatThrownBy(() -> active().credentials(at(NOW)).token()).hasMessageContaining("not https");
    }

    @Test @DisplayName("signed out: no credentials file, a clear message; signOut keeps the profile")
    void signedOut() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-x", NOW.plusSeconds(600));
        assertThat(active().signOut()).isTrue();
        assertThat(active().signOut()).isFalse();
        assertThat(dir.resolve("configs/default.json")).exists();
        assertThatThrownBy(() -> active().credentials(at(NOW)).token()).hasMessageContaining("cafeai login claude");
    }

    // ── through the Anthropic provider ──────────────────────────────────────────

    @Test @DisplayName("Anthropic.of with no key: the sign-in's token goes as Bearer, to the profile's base URL")
    void providerUsesTheSignIn() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-signed-in", Instant.now().plusSeconds(3600));
        LangchainBridge.environment = env::get;

        String answer = LangchainBridge.INSTANCE.modelFor(Anthropic.of("claude-signin-1")).chat("hi");

        assertThat(answer).isEqualTo("ok");
        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m[0]).isEqualTo("Bearer sk-ant-oat01-signed-in");
            assertThat(m[1]).isNull();
        });
    }

    @Test @DisplayName("ANTHROPIC_API_KEY wins over the sign-in, as in Anthropic's SDKs")
    void apiKeyWins() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-signed-in", Instant.now().plusSeconds(3600));
        env.put("ANTHROPIC_API_KEY", "sk-ant-api-key");
        LangchainBridge.environment = env::get;

        LangchainBridge.INSTANCE.modelFor(Anthropic.of("claude-signin-2").withBaseUrl(base())).chat("hi");

        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m[0]).isNull();
            assertThat(m[1]).isEqualTo("sk-ant-api-key");
        });
    }

    @Test @DisplayName("an Anthropic-compatible host (DeepSeek, Kimi) never gets the Claude sign-in's token")
    void otherHostsNever() throws IOException {
        profile("default", "cid", base());
        credentials("default", "sk-ant-oat01-signed-in", Instant.now().plusSeconds(3600));
        LangchainBridge.environment = env::get;

        assertThatThrownBy(() -> LangchainBridge.INSTANCE
                .modelFor(Anthropic.of("deepseek-signin-4").withBaseUrl(base() + "/anthropic")))
                .hasMessageContaining("ANTHROPIC_API_KEY");
        assertThat(messages).isEmpty();
    }

    @Test @DisplayName("with no key and no sign-in, the message offers cafeai login claude first")
    void nothing() {
        LangchainBridge.environment = env::get;
        assertThatThrownBy(() -> LangchainBridge.INSTANCE.modelFor(Anthropic.of("claude-signin-5")))
                .hasMessageContaining("cafeai login claude").hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
