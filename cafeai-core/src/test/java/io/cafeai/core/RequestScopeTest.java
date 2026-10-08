package io.cafeai.core;

import io.cafeai.core.identity.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestScope carries the current request onto other threads")
class RequestScopeTest {

    @Test @DisplayName("a task on a carrying executor sees the request's identity; a plain one doesn't")
    void carriesTheCaller() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var app = CafeAI.create();
        app.filter((req, res, next) -> {
            req.setAttribute(Attributes.IDENTITY, Identity.builder("https://issuer.example.com", "alice")
                    .expiresAt(Instant.now().plusSeconds(60)).build());
            next.run();
        });
        app.get("/", (req, res, next) -> {
            try {
                var carried = new CompletableFuture<String>();
                RequestScope.carrying(pool).execute(() ->
                        carried.complete(Identity.current().map(Identity::subject).orElse("none")));
                var plain = new CompletableFuture<String>();
                pool.execute(() -> plain.complete(Identity.current().map(Identity::subject).orElse("none")));
                String wrapped = pool.submit(RequestScope.wrap(
                        () -> Identity.current().map(Identity::subject).orElse("none"))).get();
                res.send(carried.get(5, TimeUnit.SECONDS) + "," + plain.get(5, TimeUnit.SECONDS) + "," + wrapped);
            } catch (Exception e) {
                res.status(500).send(e.toString());
            }
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(body).isEqualTo("alice,none,alice");
        } finally {
            app.stop();
            pool.shutdownNow();
        }
    }

    @Test @DisplayName("outside any request, tasks are passed through unchanged")
    void outsideARequest() {
        Runnable task = () -> { };
        assertThat(RequestScope.wrap(task)).isSameAs(task);
    }
}
