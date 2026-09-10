package io.cafeai.sentinel.sink;

import com.sun.net.httpserver.HttpServer;
import io.cafeai.sentinel.incident.IncidentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSinkTest {

    private HttpServer server;
    private final BlockingQueue<String> bodies = new ArrayBlockingQueue<>(8);
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.offer(body);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    void postsIncidentJsonToTheUrl() throws Exception {
        try (WebhookSink sink = new WebhookSink(url())) {
            sink.publish(SinkTestSupport.event(IncidentEvent.Type.INVESTIGATED));

            String body = bodies.poll(3, TimeUnit.SECONDS);
            assertThat(body).isNotNull();
            assertThat(body).contains("\"type\":\"INVESTIGATED\"").contains("Deployment/web");
        }
    }

    @Test
    void retriesOnceOnServerError() throws Exception {
        status = 500;
        try (WebhookSink sink = new WebhookSink(url())) {
            sink.publish(SinkTestSupport.event(IncidentEvent.Type.OPENED));

            assertThat(bodies.poll(3, TimeUnit.SECONDS)).isNotNull();  // attempt 1
            assertThat(bodies.poll(3, TimeUnit.SECONDS)).isNotNull();  // attempt 2
            assertThat(bodies.poll(500, TimeUnit.MILLISECONDS)).isNull(); // no third
        }
    }
}
