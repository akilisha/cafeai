package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CafeAI and Anthropic's real {@code ant} CLI sharing one profile: each reads the token the other
 * renewed. Runs only when {@code ANT_BIN} names Anthropic's {@code ant} binary; nothing reaches
 * Anthropic, the token endpoint is a local fake.
 */
@DisplayName("AnthropicProfile with Anthropic's real ant CLI (needs ANT_BIN)")
class AntInteropTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    private HttpServer server;
    private final AtomicInteger renewals = new AtomicInteger();
    private String ant;

    @BeforeEach
    void start() throws IOException {
        ant = System.getenv("ANT_BIN");
        assumeTrue(ant != null && Files.isRegularFile(Path.of(ant)), "ANT_BIN is not set to Anthropic's ant binary");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/oauth/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = renewals.incrementAndGet();
            byte[] body = ("{\"access_token\":\"sk-ant-oat01-renewal-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                    + "\"refresh_token\":\"refresh-" + (n + 1) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        Files.createDirectories(dir.resolve("configs"));
        Files.createDirectories(dir.resolve("credentials"));
        Files.writeString(dir.resolve("configs/default.json"), "{\"version\":\"1.0\",\"authentication\":"
                + "{\"type\":\"user_oauth\",\"client_id\":\"cid\"},\"base_url\":\"http://127.0.0.1:"
                + server.getAddress().getPort() + "\"}");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private void expired(String token) throws IOException {
        Files.writeString(dir.resolve("credentials/default.json"), "{\"version\":\"1.0\",\"type\":\"oauth_token\","
                + "\"access_token\":\"" + token + "\",\"expires_at\":" + Instant.now().minusSeconds(60).getEpochSecond()
                + ",\"refresh_token\":\"refresh-1\",\"organization_name\":\"Acme\",\"account_email\":\"alice@acme.example\"}");
    }

    private String antPrintsToken() throws Exception {
        var builder = new ProcessBuilder(List.of(ant, "auth", "print-credentials", "--access-token"));
        builder.environment().put("ANTHROPIC_CONFIG_DIR", dir.toString());
        builder.environment().remove("ANTHROPIC_PROFILE");
        builder.environment().remove("ANTHROPIC_API_KEY");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return out;
    }

    private AnthropicProfile profile() {
        return AnthropicProfile.active(Map.of("ANTHROPIC_CONFIG_DIR", dir.toString())::get).orElseThrow();
    }

    @Test @DisplayName("CafeAI renews; ant then prints CafeAI's token without renewing again")
    void cafeaiThenAnt() throws Exception {
        expired("sk-ant-oat01-old");
        assertThat(profile().credentials().token()).isEqualTo("sk-ant-oat01-renewal-1");
        assertThat(antPrintsToken()).isEqualTo("sk-ant-oat01-renewal-1");
        assertThat(renewals).hasValue(1);
        var stored = JSON.readTree(dir.resolve("credentials/default.json").toFile());
        assertThat(stored.get("account_email").asText()).isEqualTo("alice@acme.example");
    }

    @Test @DisplayName("ant renews; CafeAI then uses ant's token without renewing again")
    void antThenCafeai() throws Exception {
        expired("sk-ant-oat01-old");
        assertThat(antPrintsToken()).isEqualTo("sk-ant-oat01-renewal-1");
        assertThat(profile().credentials().token()).isEqualTo("sk-ant-oat01-renewal-1");
        assertThat(renewals).hasValue(1);
    }
}
