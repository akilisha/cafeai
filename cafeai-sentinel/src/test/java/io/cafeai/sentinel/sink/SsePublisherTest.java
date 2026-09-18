package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.IncidentEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SsePublisherTest {

    @Test
    void publishesEventJsonToEveryConnectedClient() {
        try (SsePublisher sse = new SsePublisher()) {
            CollectingSubscriber one = subscribe(sse);
            CollectingSubscriber two = subscribe(sse);
            awaitTrue(() -> sse.clientCount() == 2);

            sse.publish(SinkTestSupport.event(IncidentEvent.Type.OPENED));

            awaitTrue(() -> !one.items.isEmpty() && !two.items.isEmpty());
            assertThat(one.items.get(0)).contains("\"type\":\"OPENED\"").contains("inc-abc12345");
            assertThat(two.items.get(0)).isEqualTo(one.items.get(0));
        }
    }

    @Test
    void aCancelledClientIsDropped() {
        try (SsePublisher sse = new SsePublisher()) {
            CollectingSubscriber sub = subscribe(sse);
            // The client is counted when registered, which can be just before onSubscribe hands
            // this subscriber its subscription.
            awaitTrue(() -> sse.clientCount() == 1 && sub.subscription != null);

            sub.subscription.cancel();
            sse.publish(SinkTestSupport.event(IncidentEvent.Type.RESOLVED));

            awaitTrue(() -> sse.clientCount() == 0);
        }
    }

    private static CollectingSubscriber subscribe(SsePublisher sse) {
        CollectingSubscriber sub = new CollectingSubscriber();
        sse.stream().subscribe(sub);
        return sub;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (c.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("condition not met within 2s");
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<String> {
        final List<String> items = new CopyOnWriteArrayList<>();
        volatile Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(String item) {
            items.add(item);
        }

        @Override public void onError(Throwable t) {
        }

        @Override public void onComplete() {
        }
    }
}
