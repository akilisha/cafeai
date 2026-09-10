package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.IncidentEvent;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * An {@link IncidentSink} that fans incident events out to any number of live
 * HTTP clients as Server-Sent Events. Each event is serialised with
 * {@link IncidentJson#event} and pushed to every connected client.
 *
 * <p>Wire it into a route by handing {@link #stream()} to CafeAI's
 * {@code res.stream(...)}, which writes each item as a {@code data:} frame and
 * holds the connection open until the client disconnects:
 *
 * <pre>{@code
 *   var sse = new SsePublisher();
 *   tracker.onIncident(IncidentSink.of(new LogSink(), sse));
 *   app.get("/incidents/stream", (req, res, next) -> res.stream(sse.stream()));
 * }</pre>
 *
 * <p>Best-effort, per the ROADMAP-18 decision: no replay and no per-client
 * buffering of history — a client that connects late misses earlier incidents,
 * and {@link #publish} drops the event for any client too slow to keep up rather
 * than blocking the pipeline.
 */
public final class SsePublisher implements IncidentSink, AutoCloseable {

    private final SubmissionPublisher<String> publisher = new SubmissionPublisher<>();

    @Override
    public void publish(IncidentEvent event) {
        // offer, don't submit: never block the tracker's emit thread on a slow client
        publisher.offer(IncidentJson.event(event), (subscriber, item) -> false);
    }

    /** A fresh source of SSE frames for one client — pass to {@code res.stream(...)}. */
    public Flow.Publisher<String> stream() {
        return publisher;
    }

    /** How many clients are currently connected. */
    public int clientCount() {
        return publisher.getNumberOfSubscribers();
    }

    @Override
    public void close() {
        publisher.close();
    }
}
