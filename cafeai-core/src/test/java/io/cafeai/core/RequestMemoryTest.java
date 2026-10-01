package io.cafeai.core;

import io.cafeai.core.routing.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A served request must become garbage. CafeAI once kept every request's
 * context in an app-wide map that could never release it, so a server ran out
 * of heap under steady traffic (found by the moonshot #2 benchmark).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Request memory")
class RequestMemoryTest {

    private static final int REQUESTS = 200;

    private final List<WeakReference<Request>> served = new CopyOnWriteArrayList<>();
    private CafeAI     app;
    private String     base;
    private HttpClient http;

    @BeforeAll
    void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        app = CafeAI.create();
        app.filter(CafeAI.json());   // a filter, so the shared filter/handler context is used
        app.get("/ping", (req, res, next) -> {
            served.add(new WeakReference<>(req));
            res.json(Map.of("ok", true));
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
    @DisplayName("served requests are released, not kept for the life of the app")
    void servedRequestsAreCollectable() throws Exception {
        for (int i = 0; i < REQUESTS; i++) {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/ping")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        assertThat(served).hasSize(REQUESTS);

        // A keep-alive connection may still hold its latest request; allow a few.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long alive;
        do {
            System.gc();
            Thread.sleep(50);
            alive = served.stream().filter(ref -> ref.get() != null).count();
        } while (alive > 5 && System.nanoTime() < deadline);

        assertThat(alive).as("requests still reachable after GC").isLessThanOrEqualTo(5);
    }
}
