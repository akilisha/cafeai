package io.cafeai.core;

import io.cafeai.core.mcp.McpModuleNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("app.port(), app.helidon().bypass(...), app.mcp() without its module")
class HelidonBypassAndPortTest {

    @Test @DisplayName("port() is -1 before listen and the real port after listen(0)")
    void port() throws Exception {
        var app = CafeAI.create();
        assertThat(app.port()).isEqualTo(-1);
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(app.port()).isPositive();
        } finally {
            app.stop();
        }
        assertThat(app.port()).isEqualTo(-1);
    }

    @Test @DisplayName("CafeAI's filters step aside for a bypassed path, and still run everywhere else")
    void bypass() throws Exception {
        var app = CafeAI.create();
        List<String> filtered = new CopyOnWriteArrayList<>();
        app.filter(CafeAI.json());
        app.filter((req, res, next) -> { filtered.add(req.path()); next.run(); });
        app.post("/echo", (req, res, next) -> res.json(Map.of("got", String.valueOf(req.body("x")))));
        // A raw Helidon route that reads the body itself -- the body parser must not have consumed it.
        app.helidon()
           .routing(r -> r.post("/raw/echo", (req, res) -> res.send("raw:" + req.content().as(String.class))))
           .bypass("/raw");

        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            String base = "http://localhost:" + app.port();
            assertThat(post(base + "/raw/echo", "{\"x\":1}")).isEqualTo("raw:{\"x\":1}");
            assertThat(post(base + "/echo", "{\"x\":2}")).contains("\"got\":\"2\"");
            assertThat(filtered).containsExactly("/echo");
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("app.mcp() without cafeai-mcp names the dependency to add")
    void mcpNeedsItsModule() {
        assertThatThrownBy(() -> CafeAI.create().mcp())
                .isInstanceOf(McpModuleNotFoundException.class)
                .hasMessageContaining("com.akilisha.oss:cafeai-mcp");
    }

    private static String post(String url, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
