package io.cafeai.flight;

import java.time.Duration;

/**
 * A named group of related JFR events, so {@link FlightBridge} callers don't
 * need to know raw JFR event-name strings.
 *
 * <p>{@link FlightBridge.Builder#categories(FlightCategory...)} defaults to
 * {@link #GC} and {@link #VIRTUAL_THREADS} -- the two most likely to explain
 * a production slowdown in a virtual-thread-heavy Helidon SE application.
 * {@link #ALLOCATION}, {@link #CPU} and {@link #IO} are higher-volume and
 * opt-in.
 */
public enum FlightCategory {

    /** GC pauses and collection cycles. Always recorded -- pauses are rare enough not to need a threshold. */
    GC(Duration.ZERO, "jdk.GCPhasePause", "jdk.GarbageCollection"),

    /**
     * A virtual thread pinned to its carrier (e.g. inside a {@code synchronized} block), and a
     * virtual thread that failed to be submitted to its scheduler (carrier/resource exhaustion).
     * The single highest-value category for a virtual-thread-heavy framework.
     */
    VIRTUAL_THREADS(Duration.ofMillis(20), "jdk.VirtualThreadPinned", "jdk.VirtualThreadSubmitFailed"),

    /** Lock contention and thread parking. */
    CONTENTION(Duration.ofMillis(10), "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark"),

    /** Sampled object allocation. No natural duration -- a sampling event, not a timed one. */
    ALLOCATION(null, "jdk.ObjectAllocationSample"),

    /** Periodic JVM/machine CPU load samples (roughly once a second). No duration threshold applies. */
    CPU(null, "jdk.CPULoad"),

    /** Socket and file I/O. */
    IO(Duration.ofMillis(5), "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite");

    private final Duration defaultThreshold;
    private final String[] events;

    FlightCategory(Duration defaultThreshold, String... events) {
        this.defaultThreshold = defaultThreshold;
        this.events = events;
    }

    /** The raw JFR event names this category enables. */
    public String[] events() {
        return events.clone();
    }

    /**
     * This category's default duration threshold, or {@code null} if its events have no natural
     * duration (a sampling or periodic event) and should be enabled without one.
     */
    public Duration defaultThreshold() {
        return defaultThreshold;
    }
}
