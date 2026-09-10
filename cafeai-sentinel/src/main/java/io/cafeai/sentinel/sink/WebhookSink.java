package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.IncidentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POSTs each incident event as JSON ({@link IncidentJson#event}) to a URL.
 * Fire-and-forget: the {@link #publish} call hands off to a single background
 * thread and returns immediately, so a slow endpoint never stalls the pipeline.
 * One retry on failure, then the event is dropped with a warning — no queue, no
 * replay.
 */
public final class WebhookSink implements IncidentSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebhookSink.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 2;

    private final URI url;
    private final HttpClient http;
    private final ExecutorService worker;

    public WebhookSink(String url) {
        this.url = URI.create(Objects.requireNonNull(url, "url"));
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sentinel-webhook");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void publish(IncidentEvent event) {
        String body = IncidentJson.event(event);
        String id = event.incident().id();
        worker.execute(() -> post(body, id));
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private void post(String body, String incidentId) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) {
                    return;
                }
                log.warn("webhook for {} returned {} (attempt {}/{})",
                        incidentId, response.statusCode(), attempt, MAX_ATTEMPTS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("webhook for {} failed (attempt {}/{}): {}",
                        incidentId, attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        log.warn("webhook for {} dropped after {} attempts", incidentId, MAX_ATTEMPTS);
    }
}
