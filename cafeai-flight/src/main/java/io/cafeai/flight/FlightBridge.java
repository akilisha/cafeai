package io.cafeai.flight;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.spi.CafeAIModule;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bridges Java Flight Recorder events to OpenTelemetry metrics.
 *
 * <p>Independent of {@code cafeai-observability}: both simply call
 * {@link GlobalOpenTelemetry#get()} and share whatever exporter the
 * application registers -- CafeAI does not manage the OTel SDK lifecycle
 * for either module. Configure your own exporter the same way you would
 * for {@code cafeai-observability}.
 *
 * <p>Not wired into {@code app.listen()}/{@code app.stop()} -- no such
 * generic lifecycle-registration mechanism exists in CafeAI today. Start
 * and stop it yourself:
 *
 * <pre>{@code
 *   var flight = FlightBridge.builder().build();
 *   flight.start();
 *   // ... app.listen(...) ...
 *   Runtime.getRuntime().addShutdownHook(new Thread(flight::close));
 * }</pre>
 */
public final class FlightBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlightBridge.class);

    /** Default duration threshold for categories without a more specific built-in default. See {@link FlightCategory}. */
    public static final ConfigKey<Duration> DEFAULT_THRESHOLD = ConfigKey.of(
        "cafeai.flight.threshold", Duration.class, Duration.ofMillis(20),
        "Default JFR event duration threshold below which an event is not recorded, " +
        "for categories without a more specific built-in default.");

    private final RecordingStream stream;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FlightBridge(RecordingStream stream) {
        this.stream = stream;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts the underlying {@link RecordingStream}, non-blocking. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("FlightBridge is already started.");
        }
        stream.startAsync();
        log.info("FlightBridge: started");
    }

    /** Stops the recording stream. Idempotent -- safe from a JVM shutdown hook, safe to call twice. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stream.close();
            log.debug("FlightBridge: closed");
        }
    }

    // -- Builder -----------------------------------------------------------------

    public static final class Builder {
        private List<FlightCategory> categories = List.of();
        private Duration thresholdOverride;
        private final Map<String, Duration> rawEvents = new LinkedHashMap<>();
        private String meterName = "io.cafeai.flight";

        /** Categories to enable. Default: {@link FlightCategory#GC}, {@link FlightCategory#VIRTUAL_THREADS}. */
        public Builder categories(FlightCategory... categories) {
            this.categories = List.of(categories);
            return this;
        }

        /**
         * Overrides every enabled category's duration threshold with this one value.
         * Does not affect categories with no natural duration ({@link FlightCategory#ALLOCATION},
         * {@link FlightCategory#CPU}), which are never threshold-gated.
         */
        public Builder threshold(Duration threshold) {
            this.thresholdOverride = threshold;
            return this;
        }

        /**
         * Enables a raw JFR event {@link FlightCategory} doesn't wrap, recorded as a generic
         * duration histogram (the event name is attached as an attribute).
         */
        public Builder rawEvent(String jfrEventName, Duration threshold) {
            this.rawEvents.put(jfrEventName, threshold);
            return this;
        }

        /** The OTel instrumentation scope name. Default {@code "io.cafeai.flight"}. */
        public Builder meterName(String meterName) {
            this.meterName = meterName;
            return this;
        }

        /** Builds the bridge. Not started -- call {@link FlightBridge#start()} explicitly. */
        public FlightBridge build() {
            List<FlightCategory> resolved = categories.isEmpty()
                ? List.of(FlightCategory.GC, FlightCategory.VIRTUAL_THREADS)
                : categories;

            Meter meter = GlobalOpenTelemetry.get().meterBuilder(meterName)
                .setInstrumentationVersion(CafeAIModule.versionOf(FlightBridge.class))
                .build();

            RecordingStream stream = new RecordingStream();
            Map<String, Boolean> registered = new LinkedHashMap<>();

            for (FlightCategory category : resolved) {
                for (String eventName : category.events()) {
                    if (registered.putIfAbsent(eventName, true) != null) continue;
                    register(stream, meter, eventName, resolveThreshold(category));
                }
            }
            for (Map.Entry<String, Duration> raw : rawEvents.entrySet()) {
                if (registered.putIfAbsent(raw.getKey(), true) != null) continue;
                register(stream, meter, raw.getKey(), raw.getValue());
            }

            return new FlightBridge(stream);
        }

        private Duration resolveThreshold(FlightCategory category) {
            if (category.defaultThreshold() == null) return null;
            if (thresholdOverride != null) return thresholdOverride;
            return category.defaultThreshold();
        }

        private static void register(RecordingStream stream, Meter meter, String eventName, Duration threshold) {
            if (threshold != null) {
                stream.enable(eventName).withThreshold(threshold);
            } else {
                stream.enable(eventName);
            }
            Consumer<RecordedEvent> mapping = mappingFor(eventName, meter);
            stream.onEvent(eventName, mapping);
        }
    }

    // -- JFR event -> OTel metric mapping -----------------------------------------
    //
    // New ground -- no established JFR-event-to-OTel-metric mapping convention exists
    // (confirmed by research). Field extraction is defensive (hasField checks) since
    // exact field sets are per-event-type and best verified against a real JFR event
    // stream rather than assumed; a missing field degrades to an omitted attribute or
    // a zero value, never an exception.

    private static Consumer<RecordedEvent> mappingFor(String eventName, Meter meter) {
        return switch (eventName) {
            case "jdk.GCPhasePause" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.gc.pause").setUnit("ms").build();
                yield event -> h.record(millis(event), attrs("phase", stringField(event, "name")));
            }
            case "jdk.GarbageCollection" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.gc.duration").setUnit("ms").build();
                yield event -> h.record(millis(event), attrs("cause", stringField(event, "cause")));
            }
            case "jdk.VirtualThreadPinned" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.vthread.pinned").setUnit("ms").build();
                yield event -> h.record(millis(event), Attributes.empty());
            }
            case "jdk.VirtualThreadSubmitFailed" -> {
                LongCounter c = meter.counterBuilder("cafeai.flight.vthread.submit_failed").build();
                yield event -> c.add(1);
            }
            case "jdk.JavaMonitorEnter" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.monitor.enter").setUnit("ms").build();
                yield event -> h.record(millis(event), attrs("monitorClass", classField(event, "monitorClass")));
            }
            case "jdk.JavaMonitorWait" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.monitor.wait").setUnit("ms").build();
                yield event -> h.record(millis(event), Attributes.empty());
            }
            case "jdk.ThreadPark" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.thread.park").setUnit("ms").build();
                yield event -> h.record(millis(event), Attributes.empty());
            }
            case "jdk.ObjectAllocationSample" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.allocation.bytes").setUnit("By").build();
                yield event -> h.record(longField(event, "weight"), attrs("objectClass", classField(event, "objectClass")));
            }
            case "jdk.CPULoad" -> {
                // Recorded as a histogram of instantaneous samples -- OTel's synchronous Gauge
                // instrument isn't relied on here to avoid pinning to a specific SDK version's API shape.
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.cpu.load").build();
                yield event -> {
                    h.record(doubleField(event, "jvmUser"), attrs("scope", "jvm"));
                    h.record(doubleField(event, "machineTotal"), attrs("scope", "machine"));
                };
            }
            case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" -> {
                DoubleHistogram h = meter.histogramBuilder("cafeai.flight.io.duration").setUnit("ms").build();
                yield event -> h.record(millis(event), attrs("operation", eventName));
            }
            default -> genericMapping(eventName, meter);
        };
    }

    private static Consumer<RecordedEvent> genericMapping(String eventName, Meter meter) {
        DoubleHistogram h = meter.histogramBuilder("cafeai.flight.raw").setUnit("ms").build();
        return event -> h.record(millis(event), attrs("event", eventName));
    }

    // -- Safe field extraction -----------------------------------------------------

    private static double millis(RecordedEvent event) {
        try {
            return event.getDuration().toNanos() / 1_000_000.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String stringField(RecordedEvent event, String field) {
        if (!event.hasField(field)) return "unknown";
        Object value = event.getValue(field);
        return value != null ? value.toString() : "unknown";
    }

    private static String classField(RecordedEvent event, String field) {
        if (!event.hasField(field)) return "unknown";
        Object value = event.getValue(field);
        if (value instanceof RecordedClass rc) return rc.getName();
        return value != null ? value.toString() : "unknown";
    }

    private static long longField(RecordedEvent event, String field) {
        try {
            return event.hasField(field) ? event.getLong(field) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double doubleField(RecordedEvent event, String field) {
        try {
            return event.hasField(field) ? event.getDouble(field) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static Attributes attrs(String key, String value) {
        return Attributes.of(AttributeKey.stringKey(key), value);
    }
}
