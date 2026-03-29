package io.cafeai.observability;

/**
 * Observation strategy that creates an OpenTelemetry span for each LLM call.
 *
 * <p>Uses the global {@code OpenTelemetry} instance — configure your exporter
 * via standard OTel SDK configuration. CafeAI does not manage the SDK lifecycle.
 */
final class OtelObserveStrategy implements ObserveStrategy {

    @Override
    public String toString() { return "OtelObserveStrategy"; }
}
