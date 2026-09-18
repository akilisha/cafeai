package io.cafeai.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers one in-memory OpenTelemetry SDK as the global instance, so tests can read the spans
 * {@link ObserveBridgeImpl} produces. The global instance can be set once per JVM, and the bridge
 * looks its tracer up when the class loads, so every test calls {@link #reset()} before it creates a
 * bridge: that forces this class to initialise first.
 */
final class TestOtel {

    private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

    static {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
            .build();
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
    }

    private TestOtel() {}

    /** Clears recorded spans; call before each test. */
    static void reset() {
        EXPORTER.reset();
    }

    static List<SpanData> spans() {
        return EXPORTER.getFinishedSpanItems();
    }

    /** The only span recorded, failing loudly if there is not exactly one. */
    static SpanData onlySpan() {
        List<SpanData> spans = spans();
        if (spans.size() != 1) {
            throw new AssertionError("expected exactly one span, got " + spans.size() + ": "
                + spans.stream().map(SpanData::getName).toList());
        }
        return spans.get(0);
    }

    /** Span attributes by name, for readable assertions. */
    static Map<String, Object> attrs(SpanData span) {
        Map<String, Object> out = new LinkedHashMap<>();
        span.getAttributes().forEach((AttributeKey<?> key, Object value) -> out.put(key.getKey(), value));
        return out;
    }
}
