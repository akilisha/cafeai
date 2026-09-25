package io.cafeai.flight;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code FlightBridge} -- JFR event capture through to an OTel metric recording.
 *
 * <p>Uses {@link TestOtelMetrics} (a real in-memory OTel SDK registered as the JVM global)
 * rather than mocks -- the thing worth proving is the real
 * enable/subscribe/record pipeline against a real {@code RecordingStream}, not a
 * unit in isolation.
 */
class FlightBridgeTest {

    @Test
    void rawEventFiresAndIsRecordedAsAMetric() throws Exception {
        TestOtelMetrics.metrics(); // force TestOtelMetrics' static init before any bridge is built
        new FlightBridgeTestEvent().commit(); // force JFR to register the event type

        FlightBridge bridge = FlightBridge.builder()
            .rawEvent("cafeai.flight.test.TestEvent", Duration.ZERO)
            .build();
        bridge.start();
        try {
            new FlightBridgeTestEvent().commit();
            awaitMetric("cafeai.flight.raw");
        } finally {
            bridge.close();
        }
    }

    @Test
    void virtualThreadPinningIsRecorded() throws Exception {
        TestOtelMetrics.metrics();

        FlightBridge bridge = FlightBridge.builder()
            .categories(FlightCategory.VIRTUAL_THREADS)
            .threshold(Duration.ZERO)
            .build();
        bridge.start();
        try {
            Object lock = new Object();
            // synchronized pins a virtual thread to its carrier (pre-JEP 491; this project
            // targets Java 23, where synchronized still pins) -- sleeping while holding it
            // gives jdk.VirtualThreadPinned a real duration to fire on.
            Thread vt = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            vt.join(Duration.ofSeconds(5).toMillis());

            awaitMetric("cafeai.flight.vthread.pinned");
        } finally {
            bridge.close();
        }
    }

    @Test
    void closeIsIdempotentAndStopsRecording() throws Exception {
        TestOtelMetrics.metrics();
        new FlightBridgeTestEvent().commit();

        FlightBridge bridge = FlightBridge.builder()
            .rawEvent("cafeai.flight.test.TestEvent", Duration.ZERO)
            .build();
        bridge.start();
        new FlightBridgeTestEvent().commit();
        awaitMetric("cafeai.flight.raw");
        long countAfterFirstEvent = TestOtelMetrics.histogramCount("cafeai.flight.raw");

        bridge.close();
        bridge.close(); // idempotent -- must not throw

        new FlightBridgeTestEvent().commit();
        Thread.sleep(500); // bounded wait to prove absence, not presence -- nothing should arrive
        assertThat(TestOtelMetrics.histogramCount("cafeai.flight.raw")).isEqualTo(countAfterFirstEvent);
    }

    @Test
    void startingTwiceThrows() {
        FlightBridge bridge = FlightBridge.builder().build();
        bridge.start();
        try {
            assertThatThrownBy(bridge::start).isInstanceOf(IllegalStateException.class);
        } finally {
            bridge.close();
        }
    }

    /** Bounded poll -- RecordingStream delivers events on its own background thread, asynchronously. */
    private static void awaitMetric(String name) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (TestOtelMetrics.hasMetric(name)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("metric " + name + " did not appear within 10s; got: "
            + TestOtelMetrics.metrics().stream().map(m -> m.getName()).toList());
    }
}
