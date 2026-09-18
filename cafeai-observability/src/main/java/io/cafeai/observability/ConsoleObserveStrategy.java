package io.cafeai.observability;


/**
 * Observation strategy that writes structured, human-readable output
 * to the console for each LLM call.
 *
 * <p>Designed for development and local debugging. In production,
 * use {@link OtelObserveStrategy} which exports to your observability
 * stack.
 */
final class ConsoleObserveStrategy implements ObserveStrategy {


    @Override
    public String toString() { return "ConsoleObserveStrategy"; }
}
