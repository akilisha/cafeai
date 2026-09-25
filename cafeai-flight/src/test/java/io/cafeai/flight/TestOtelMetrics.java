package io.cafeai.flight;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import java.util.Collection;

/**
 * Registers one in-memory OpenTelemetry SDK as the global instance, so tests can read the
 * metrics {@link FlightBridge} produces. The global instance can be set once per JVM, and
 * {@code FlightBridge.Builder.build()} looks it up when a bridge is built, so tests rely on
 * this class initialising (via a reference to it) before any bridge is built.
 */
final class TestOtelMetrics {

    private static final InMemoryMetricReader READER = InMemoryMetricReader.create();

    static {
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(READER)
            .build();
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).buildAndRegisterGlobal();
    }

    private TestOtelMetrics() {}

    static Collection<MetricData> metrics() {
        return READER.collectAllMetrics();
    }

    static boolean hasMetric(String name) {
        return metrics().stream().anyMatch(m -> m.getName().equals(name));
    }

    static MetricData metric(String name) {
        return metrics().stream()
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no metric named " + name + "; got: "
                + metrics().stream().map(MetricData::getName).toList()));
    }

    /** Sum of recorded counts across a histogram metric's data points. */
    static long histogramCount(String name) {
        MetricData data = metric(name);
        long total = 0;
        for (HistogramPointData point : data.getHistogramData().getPoints()) {
            total += point.getCount();
        }
        return total;
    }
}
