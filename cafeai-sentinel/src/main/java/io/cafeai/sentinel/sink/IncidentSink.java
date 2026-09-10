package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.IncidentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where coalesced incident lifecycle events go — a log, an SSE stream, a webhook,
 * or your own implementation. Hand one to
 * {@link io.cafeai.sentinel.IncidentTracker#onIncident(Consumer)}.
 *
 * <p>Delivery is fire-and-forget (ROADMAP-18): no replay, no persistence, no
 * ordering guarantee across sinks. A sink is called from the tracker's emit path
 * — on the informer thread, sometimes under the tracker lock — so
 * {@link #publish} <strong>must not block</strong>. Sinks that do I/O
 * ({@link WebhookSink}) hand off to their own thread; {@link SsePublisher} offers
 * without blocking.
 */
@FunctionalInterface
public interface IncidentSink extends Consumer<IncidentEvent> {

    void publish(IncidentEvent event);

    @Override
    default void accept(IncidentEvent event) {
        publish(event);
    }

    /** Fan out to several sinks in order; a throwing sink is logged and skipped. */
    static IncidentSink of(IncidentSink... sinks) {
        List<IncidentSink> list = List.of(sinks);
        Logger log = LoggerFactory.getLogger(IncidentSink.class);
        return event -> {
            for (IncidentSink sink : list) {
                try {
                    sink.publish(event);
                } catch (RuntimeException e) {
                    log.warn("sink {} threw for {} {}",
                            sink.getClass().getSimpleName(), event.type(), event.incident().id(), e);
                }
            }
        };
    }
}
