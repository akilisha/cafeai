package io.cafeai.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code res.beforeSend(...)} -- the hook that runs right before a response commits,
 * added specifically because code after {@code next.run()} usually runs too late (see
 * {@code Middleware.cookieSession(...)}, the feature this primitive exists for).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseBeforeSendTest {

    private CafeAI     app;
    private String     base;
    private HttpClient http;

    @BeforeAll
    void start() throws Exception {
        int port = freePort();
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        app = CafeAI.create();

        app.get("/ordered", (req, res, next) -> {
            res.beforeSend(() -> res.append("X-Order", "1"));
            res.beforeSend(() -> res.append("X-Order", "2"));
            res.json(Map.of("status", "ok"));
        });

        app.get("/throwing", (req, res, next) -> {
            res.beforeSend(() -> { throw new RuntimeException("boom"); });
            res.json(Map.of("should", "not appear"));
        });

        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
    }

    @AfterAll
    void stop() {
        if (app != null) app.stop();
    }

    @Test
    @DisplayName("hooks run, in registration order, before the response is sent")
    void hooksRunInOrderBeforeSend() throws Exception {
        var res = get("/ordered");

        assertThat(res.headers().firstValue("X-Order")).contains("1, 2");
        assertThat(res.body()).contains("\"status\":\"ok\"");
    }

    @Test
    @DisplayName("a hook that throws prevents the body from being sent -- surfaces as 500, not a partial response")
    void throwingHookPreventsTheBodyFromBeingSent() throws Exception {
        var res = get("/throwing");

        assertThat(res.statusCode()).isEqualTo(500);
        assertThat(res.body()).doesNotContain("should not appear");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
